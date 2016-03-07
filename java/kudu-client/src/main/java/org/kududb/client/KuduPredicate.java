// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.kududb.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedBytes;
import com.google.protobuf.ByteString;
import org.kududb.ColumnSchema;
import org.kududb.Common;
import org.kududb.Schema;
import org.kududb.Type;
import org.kududb.annotations.InterfaceAudience;
import org.kududb.annotations.InterfaceStability;

import java.util.Arrays;

/**
 * A predicate which can be used to filter rows based on the value of a column.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class KuduPredicate {

  /**
   * The predicate type.
   */
  @InterfaceAudience.Private
  enum PredicateType {
    /** A predicate which filters all rows. */
    NONE,
    /** A predicate which filters all rows not equal to a value. */
    EQUALITY,
    /** A predicate which filters all rows not in a range. */
    RANGE,
    /** A predicate which filters all null rows. */
    IS_NOT_NULL,
  }

  /**
   * The comparison operator of a predicate.
   */
  @InterfaceAudience.Public
  @InterfaceStability.Evolving
  public enum ComparisonOp {
    GREATER,
    GREATER_EQUAL,
    EQUAL,
    LESS,
    LESS_EQUAL,
  }

  private final PredicateType type;
  private final ColumnSchema column;

  /**
   * The inclusive lower bound value if this is a Range predicate, or
   * the createEquality value if this is an Equality predicate.
   */
  private final byte[] lower;

  /** The exclusive upper bound value if this is a Range predicate. */
  private final byte[] upper;

  /**
   * Creates a new {@code KuduPredicate} on a boolean column.
   * @param column the column schema
   * @param op the comparison operation
   * @param value the value to compare against
   */
  public static KuduPredicate newComparisonPredicate(ColumnSchema column,
                                                     ComparisonOp op,
                                                     boolean value) {
    checkColumn(column, Type.BOOL);
    // Create the comparison predicate. Range predicates on boolean values can
    // always be converted to either an equality, an IS NOT NULL (filtering only
    // null values), or NONE (filtering all values).
    switch (op) {
      case GREATER: {
        // b > true  -> b NONE
        // b > false -> b = true
        if (value) {
          return none(column);
        } else {
          return new KuduPredicate(PredicateType.EQUALITY, column, Bytes.fromBoolean(true), null);
        }
      }
      case GREATER_EQUAL: {
        // b >= true  -> b = true
        // b >= false -> b IS NOT NULL
        if (value) {
          return new KuduPredicate(PredicateType.EQUALITY, column, Bytes.fromBoolean(true), null);
        } else {
          return newIsNotNullPredicate(column);
        }
      }
      case EQUAL: return new KuduPredicate(PredicateType.EQUALITY, column,
                                           Bytes.fromBoolean(value), null);
      case LESS: {
        // b < true  -> b NONE
        // b < false -> b = true
        if (value) {
          return new KuduPredicate(PredicateType.EQUALITY, column, Bytes.fromBoolean(false), null);
        } else {
          return none(column);
        }
      }
      case LESS_EQUAL: {
        // b <= true  -> b IS NOT NULL
        // b <= false -> b = false
        if (value) {
          return newIsNotNullPredicate(column);
        } else {
          return new KuduPredicate(PredicateType.EQUALITY, column, Bytes.fromBoolean(false), null);
        }
      }
      default: throw new RuntimeException("unknown comparison op");
    }
  }

  /**
   * Creates a new comparison predicate on an integer or timestamp column.
   * @param column the column schema
   * @param op the comparison operation
   * @param value the value to compare against
   */
  public static KuduPredicate newComparisonPredicate(ColumnSchema column,
                                                     ComparisonOp op,
                                                     long value) {
    checkColumn(column, Type.INT8, Type.INT16, Type.INT32, Type.INT64, Type.TIMESTAMP);
    Preconditions.checkArgument(value <= maxIntValue(column.getType()) &&
                                    value >= minIntValue(column.getType()),
                                "integer value out of range for %s column: %s",
                                column.getType(), value);

    if (op == ComparisonOp.LESS_EQUAL) {
      if (value == maxIntValue(column.getType())) {
        // If the value can't be incremented because it is at the top end of the
        // range, then substitute the predicate with an IS NOT NULL predicate.
        // This has the same effect as an inclusive upper bound on the maximum
        // value. If the column is not nullable then the IS NOT NULL predicate
        // is ignored.
        return newIsNotNullPredicate(column);
      }
      value += 1;
    } else if (op == ComparisonOp.GREATER) {
      if (value == maxIntValue(column.getType())) {
        return none(column);
      }
      value += 1;
    }

    byte[] bytes;
    switch (column.getType()) {
      case INT8: {
        bytes = new byte[] { (byte) value };
        break;
      }
      case INT16: {
        bytes = Bytes.fromShort((short) value);
        break;
      }
      case INT32: {
        bytes = Bytes.fromInt((int) value);
        break;
      }
      case INT64:
      case TIMESTAMP: {
        bytes = Bytes.fromLong(value);
        break;
      }
      default: throw new RuntimeException("already checked");
    }
    switch (op) {
      case GREATER:
      case GREATER_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, bytes, null);
      case EQUAL: return new KuduPredicate(PredicateType.EQUALITY, column, bytes, null);
      case LESS:
      case LESS_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, null, bytes);
      default: throw new RuntimeException("unknown comparison op");
    }
  }

  /**
   * Creates a new comparison predicate on a float column.
   * @param column the column schema
   * @param op the comparison operation
   * @param value the value to compare against
   */
  public static KuduPredicate newComparisonPredicate(ColumnSchema column,
                                                     ComparisonOp op,
                                                     float value) {
    checkColumn(column, Type.FLOAT);
    if (op == ComparisonOp.LESS_EQUAL) {
      if (value == Float.POSITIVE_INFINITY) {
        return newIsNotNullPredicate(column);
      }
      value = Math.nextAfter(value, Float.POSITIVE_INFINITY);
    } else if (op == ComparisonOp.GREATER) {
      if (value == Float.POSITIVE_INFINITY) {
        return none(column);
      }
      value = Math.nextAfter(value, Float.POSITIVE_INFINITY);
    }

    byte[] bytes = Bytes.fromFloat(value);
    switch (op) {
      case GREATER:
      case GREATER_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, bytes, null);
      case EQUAL: return new KuduPredicate(PredicateType.EQUALITY, column, bytes, null);
      case LESS:
      case LESS_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, null, bytes);
      default: throw new RuntimeException("unknown comparison op");
    }
  }

  /**
   * Creates a new comparison predicate on a double column.
   * @param column the column schema
   * @param op the comparison operation
   * @param value the value to compare against
   */
  public static KuduPredicate newComparisonPredicate(ColumnSchema column,
                                                     ComparisonOp op,
                                                     double value) {
    checkColumn(column, Type.DOUBLE);
    if (op == ComparisonOp.LESS_EQUAL) {
      if (value == Double.POSITIVE_INFINITY) {
        return newIsNotNullPredicate(column);
      }
      value = Math.nextAfter(value, Double.POSITIVE_INFINITY);
    } else if (op == ComparisonOp.GREATER) {
      if (value == Double.POSITIVE_INFINITY) {
        return none(column);
      }
      value = Math.nextAfter(value, Double.POSITIVE_INFINITY);
    }

    byte[] bytes = Bytes.fromDouble(value);
    switch (op) {
      case GREATER:
      case GREATER_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, bytes, null);
      case EQUAL: return new KuduPredicate(PredicateType.EQUALITY, column, bytes, null);
      case LESS:
      case LESS_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, null, bytes);
      default: throw new RuntimeException("unknown comparison op");
    }
  }

  /**
   * Creates a new comparison predicate on a string column.
   * @param column the column schema
   * @param op the comparison operation
   * @param value the value to compare against
   */
  public static KuduPredicate newComparisonPredicate(ColumnSchema column,
                                                     ComparisonOp op,
                                                     String value) {
    checkColumn(column, Type.STRING);

    byte[] bytes = Bytes.fromString(value);
    if (op == ComparisonOp.LESS_EQUAL || op == ComparisonOp.GREATER) {
      bytes = Arrays.copyOf(bytes, bytes.length + 1);
    }

    switch (op) {
      case GREATER:
      case GREATER_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, bytes, null);
      case EQUAL: return new KuduPredicate(PredicateType.EQUALITY, column, bytes, null);
      case LESS:
      case LESS_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, null, bytes);
      default: throw new RuntimeException("unknown comparison op");
    }
  }

  /**
   * Creates a new comparison predicate on a binary column.
   * @param column the column schema
   * @param op the comparison operation
   * @param value the value to compare against
   */
  public static KuduPredicate newComparisonPredicate(ColumnSchema column,
                                                     ComparisonOp op,
                                                     byte[] value) {
    checkColumn(column, Type.BINARY);

    if (op == ComparisonOp.LESS_EQUAL || op == ComparisonOp.GREATER) {
      value = Arrays.copyOf(value, value.length + 1);
    }

    switch (op) {
      case GREATER:
      case GREATER_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, value, null);
      case EQUAL: return new KuduPredicate(PredicateType.EQUALITY, column, value, null);
      case LESS:
      case LESS_EQUAL: return new KuduPredicate(PredicateType.RANGE, column, null, value);
      default: throw new RuntimeException("unknown comparison op");
    }
  }

  /**
   * @param type the predicate type
   * @param column the column to which the predicate applies
   * @param lower the lower bound serialized value if this is a Range predicate,
   *              or the equality value if this is an Equality predicate
   * @param upper the upper bound serialized value if this is an Equality predicate
   */
  @VisibleForTesting
  KuduPredicate(PredicateType type, ColumnSchema column, byte[] lower, byte[] upper) {
    this.type = type;
    this.column = column;
    this.lower = lower;
    this.upper = upper;
  }

  /**
   * Factory function for a {@code None} predicate.
   * @param column the column to which the predicate applies
   * @return a None predicate
   */
  @VisibleForTesting
  static KuduPredicate none(ColumnSchema column) {
    return new KuduPredicate(PredicateType.NONE, column, null, null);
  }

  /**
   * Factory function for an {@code IS NOT NULL} predicate.
   * @param column the column to which the predicate applies
   * @return a {@code IS NOT NULL} predicate
   */
  @VisibleForTesting
  static KuduPredicate newIsNotNullPredicate(ColumnSchema column) {
    return new KuduPredicate(PredicateType.IS_NOT_NULL, column, null, null);
  }

  /**
   * @return the type of this predicate
   */
  PredicateType getType() {
    return type;
  }

  /**
   * Merges another {@code ColumnPredicate} into this one, returning a new
   * {@code ColumnPredicate} which matches the logical intersection ({@code AND})
   * of the input predicates.
   * @param other the predicate to merge with this predicate
   * @return a new predicate that is the logical intersection
   */
  KuduPredicate merge(KuduPredicate other) {
    Preconditions.checkArgument(column.equals(other.column),
                                "predicates from different columns may not be merged");
    if (type == PredicateType.NONE || other.type == PredicateType.NONE) {
      return none(column);
    }

    if (type == PredicateType.IS_NOT_NULL) {
      // NOT NULL is less selective than all other predicate types, so the
      // intersection of NOT NULL with any other predicate is just the other
      // predicate.
      //
      // Note: this will no longer be true when an IS NULL predicate type is
      // added.
      return other;
    }

    if (type == PredicateType.EQUALITY) {
      if (other.type == PredicateType.EQUALITY) {
        if (compare(lower, other.lower) != 0) {
          return none(this.column);
        } else {
          return this;
        }
      } else {
        if ((other.lower == null || compare(lower, other.lower) >= 0) &&
            (other.upper == null || compare(lower, other.upper) < 0)) {
          return this;
        } else {
          return none(this.column);
        }
      }
    } else {
      if (other.type == PredicateType.EQUALITY) {
        return other.merge(this);
      } else {
        byte[] newLower = other.lower == null ||
            (lower != null && compare(lower, other.lower) >= 0) ? lower : other.lower;
        byte[] newUpper = other.upper == null ||
            (upper != null && compare(upper, other.upper) <= 0) ? upper : other.upper;
        if (newLower != null && newUpper != null && compare(newLower, newUpper) >= 0) {
          return none(column);
        } else {
          if (newLower != null && newUpper != null && areConsecutive(newLower, newUpper)) {
            return new KuduPredicate(PredicateType.EQUALITY, column, newLower, null);
          } else {
            return new KuduPredicate(PredicateType.RANGE, column, newLower, newUpper);
          }
        }
      }
    }
  }

  /**
   * @return the schema of the predicate column
   */
  ColumnSchema getColumn() {
    return column;
  }

  /**
   * Convert the predicate to the protobuf representation.
   * @return the protobuf message for this predicate.
   */
  @InterfaceAudience.Private
  public Common.ColumnPredicatePB toPB() {
    Common.ColumnPredicatePB.Builder builder = Common.ColumnPredicatePB.newBuilder();
    builder.setColumn(column.getName());

    switch (type) {
      case EQUALITY: {
        builder.getEqualityBuilder().setValue(ByteString.copyFrom(lower));
        break;
      }
      case RANGE: {
        Common.ColumnPredicatePB.Range.Builder b = builder.getRangeBuilder();
        if (lower != null) {
          b.setLower(ByteString.copyFrom(lower));
        }
        if (upper != null) {
          b.setUpper(ByteString.copyFrom(upper));
        }
        break;
      }
      case IS_NOT_NULL: {
        builder.setIsNotNull(builder.getIsNotNullBuilder());
        break;
      }
      case NONE: throw new IllegalStateException(
          "can not convert None predicate to protobuf message");
      default: throw new IllegalArgumentException(
          String.format("unknown predicate type: %s", type));
    }
    return builder.build();
  }

  /**
   * Convert a column predicate protobuf message into a predicate.
   * @return a predicate
   */
  @InterfaceAudience.Private
  public static KuduPredicate fromPB(Schema schema, Common.ColumnPredicatePB pb) {
    ColumnSchema column = schema.getColumn(pb.getColumn());
    switch (pb.getPredicateCase()) {
      case EQUALITY: {
        return new KuduPredicate(PredicateType.EQUALITY, column,
                                 pb.getEquality().getValue().toByteArray(), null);

      }
      case RANGE: {
        Common.ColumnPredicatePB.Range range = pb.getRange();
        return new KuduPredicate(PredicateType.RANGE, column,
                                 range.hasLower() ? range.getLower().toByteArray() : null,
                                 range.hasUpper() ? range.getUpper().toByteArray() : null);
      }
      default: throw new IllegalArgumentException("unknown predicate type");
    }
  }

  /**
   * Compares two bounds based on the type of this predicate's column.
   * @param a the first serialized value
   * @param b the second serialized value
   * @return the comparison of the serialized values based on the column type
   */
  private int compare(byte[] a, byte[] b) {
    switch (column.getType().getDataType()) {
      case BOOL:
        return Boolean.compare(Bytes.getBoolean(a), Bytes.getBoolean(b));
      case INT8:
        return Byte.compare(Bytes.getByte(a), Bytes.getByte(b));
      case INT16:
        return Short.compare(Bytes.getShort(a), Bytes.getShort(b));
      case INT32:
        return Integer.compare(Bytes.getInt(a), Bytes.getInt(b));
      case INT64:
      case TIMESTAMP:
        return Long.compare(Bytes.getLong(a), Bytes.getLong(b));
      case FLOAT:
        return Float.compare(Bytes.getFloat(a), Bytes.getFloat(b));
      case DOUBLE:
        return Double.compare(Bytes.getDouble(a), Bytes.getDouble(b));
      case STRING:
      case BINARY:
        return UnsignedBytes.lexicographicalComparator().compare(a, b);
      default:
        throw new IllegalStateException(String.format("unknown column type %s", column.getType()));
    }
  }

  /**
   * Returns true if increment(a) == b.
   * @param a the value which would be incremented
   * @param b the target value
   * @return true if increment(a) == b
   */
  private boolean areConsecutive(byte[] a, byte[] b) {
    switch (column.getType().getDataType()) {
      case BOOL: return false;
      case INT8: {
        byte m = Bytes.getByte(a);
        byte n = Bytes.getByte(b);
        return m < n && m + 1 == n;
      }
      case INT16: {
        short m = Bytes.getShort(a);
        short n = Bytes.getShort(b);
        return m < n && m + 1 == n;
      }
      case INT32: {
        int m = Bytes.getInt(a);
        int n = Bytes.getInt(b);
        return m < n && m + 1 == n;
      }
      case INT64:
      case TIMESTAMP: {
        long m = Bytes.getLong(a);
        long n = Bytes.getLong(b);
        return m < n && m + 1 == n;
      }
      case FLOAT: {
        float m = Bytes.getFloat(a);
        float n = Bytes.getFloat(b);
        return m < n && Math.nextAfter(m, Float.POSITIVE_INFINITY) == n;
      }
      case DOUBLE: {
        double m = Bytes.getDouble(a);
        double n = Bytes.getDouble(b);
        return m < n && Math.nextAfter(m, Double.POSITIVE_INFINITY) == n;
      }
      case STRING:
      case BINARY: {
        if (a.length + 1 != b.length || b[a.length] != 0) return false;
        for (int i = 0; i < a.length; i++) {
          if (a[i] != b[i]) return false;
        }
        return true;
      }
      default:
        throw new IllegalStateException(String.format("unknown column type %s", column.getType()));
    }
  }

  /**
   * Returns the maximum value for the integer type.
   * @param type an integer type
   * @return the maximum value
   */
  @VisibleForTesting
  static long maxIntValue(Type type) {
    switch (type) {
      case INT8: return Byte.MAX_VALUE;
      case INT16: return Short.MAX_VALUE;
      case INT32: return Integer.MAX_VALUE;
      case TIMESTAMP:
      case INT64: return Long.MAX_VALUE;
      default: throw new IllegalArgumentException("type must be an integer type");
    }
  }

  /**
   * Returns the minimum value for the integer type.
   * @param type an integer type
   * @return the minimum value
   */
  @VisibleForTesting
  static long minIntValue(Type type) {
    switch (type) {
      case INT8: return Byte.MIN_VALUE;
      case INT16: return Short.MIN_VALUE;
      case INT32: return Integer.MIN_VALUE;
      case TIMESTAMP:
      case INT64: return Long.MIN_VALUE;
      default: throw new IllegalArgumentException("type must be an integer type");
    }
  }


  /**
   * Checks that the column is one of the expected types.
   * @param column the column being checked
   * @param passedTypes the expected types (logical OR)
   */
  private static void checkColumn(ColumnSchema column, Type... passedTypes) {
    for (Type type : passedTypes) {
      if (column.getType().equals(type)) return;
    }
    throw new IllegalArgumentException(String.format("%s's type isn't %s, it's %s",
                                                     column.getName(), Arrays.toString(passedTypes),
                                                     column.getType().getName()));
  }

  /**
   * Returns the string value of serialized value according to the type of column.
   * @param value the value
   * @return the text representation of the value
   */
  private String valueToString(byte[] value) {
    switch (column.getType().getDataType()) {
      case BOOL: return Boolean.toString(Bytes.getBoolean(value));
      case INT8: return Byte.toString(Bytes.getByte(value));
      case INT16: return Short.toString(Bytes.getShort(value));
      case INT32: return Integer.toString(Bytes.getInt(value));
      case INT64: return Long.toString(Bytes.getLong(value));
      case TIMESTAMP: return RowResult.timestampToString(Bytes.getLong(value));
      case FLOAT: return Float.toString(Bytes.getFloat(value));
      case DOUBLE: return Double.toString(Bytes.getDouble(value));
      case STRING: {
        String v = Bytes.getString(value);
        StringBuilder sb = new StringBuilder(2 + v.length());
        sb.append('"');
        sb.append(v);
        sb.append('"');
        return sb.toString();
      }
      case BINARY: return Bytes.hex(value);
      default:
        throw new IllegalStateException(String.format("unknown column type %s", column.getType()));
    }
  }

  @Override
  public String toString() {
    switch (type) {
      case EQUALITY: return String.format("`%s` = %s", column.getName(), valueToString(lower));
      case RANGE: {
        if (lower == null) {
          return String.format("`%s` < %s", column.getName(), valueToString(upper));
        } else if (upper == null) {
          return String.format("`%s` >= %s", column.getName(), valueToString(lower));
        } else {
          return String.format("`%s` >= %s AND `%s` < %s",
                               column.getName(), valueToString(lower),
                               column.getName(), valueToString(upper));
        }
      }
      case IS_NOT_NULL: return String.format("`%s` IS NOT NULL", column.getName());
      case NONE: return String.format("`%s` NONE", column.getName());
      default: throw new IllegalArgumentException(String.format("unknown predicate type %s", type));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    KuduPredicate that = (KuduPredicate) o;
    return type == that.type &&
        column.equals(that.column) &&
        Arrays.equals(lower, that.lower) &&
        Arrays.equals(upper, that.upper);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(type, column, Arrays.hashCode(lower), Arrays.hashCode(upper));
  }
}