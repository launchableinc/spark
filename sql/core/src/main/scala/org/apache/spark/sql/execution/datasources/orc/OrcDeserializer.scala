/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.orc

import org.apache.hadoop.io._
import org.apache.orc.mapred.{OrcList, OrcMap, OrcStruct, OrcTimestamp}

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{MutableAny, MutableBoolean, MutableByte, MutableDouble, MutableFloat, MutableInt, MutableLong, MutableShort, MutableValue, SpecificInternalRow, UnsafeArrayData}
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.catalyst.util.RebaseDateTime.rebaseJulianToGregorianDays
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/**
 * A deserializer to deserialize ORC structs to Spark rows.
 */
class OrcDeserializer(
    dataSchema: StructType,
    requiredSchema: StructType,
    requestedColIds: Array[Int]) {

  private val resultRow = new SpecificInternalRow(requiredSchema.map(_.dataType))

  // `fieldWriters(index)` is
  // - null if the respective source column is missing, since the output value
  //   is always null in this case
  // - a function that updates target column `index` otherwise.
  private val fieldWriters: Array[WritableComparable[_] => Unit] = {
    requiredSchema.zipWithIndex
      .map { case (f, index) =>
        if (requestedColIds(index) == -1) {
          null
        } else {
          val writer = newWriter(f.dataType)
          val updater = new RowUpdater(resultRow)
          (value: WritableComparable[_]) => writer(updater, index, value)
        }
      }.toArray
  }

  def deserialize(orcStruct: OrcStruct): InternalRow = {
    var targetColumnIndex = 0
    while (targetColumnIndex < fieldWriters.length) {
      if (fieldWriters(targetColumnIndex) != null) {
        val value = orcStruct.getFieldValue(requestedColIds(targetColumnIndex))
        if (value == null) {
          resultRow.setNullAt(targetColumnIndex)
        } else {
          fieldWriters(targetColumnIndex)(value)
        }
      }
      targetColumnIndex += 1
    }
    resultRow
  }

  /**
   * Creates a writer to write ORC values to Catalyst data structure at the given ordinal.
   */
  private def newWriter(
      dataType: DataType): (CatalystDataUpdater, Int, WritableComparable[_]) => Unit =
    dataType match {
      case NullType => (updater, ordinal, _) =>
        updater.setNullAt(ordinal)

      case BooleanType => (updater, ordinal, value) =>
        updater.setBoolean(ordinal, value.asInstanceOf[BooleanWritable].get)

      case ByteType => (updater, ordinal, value) =>
        updater.setByte(ordinal, value.asInstanceOf[ByteWritable].get)

      case ShortType => (updater, ordinal, value) =>
        updater.setShort(ordinal, value.asInstanceOf[ShortWritable].get)

      case IntegerType => (updater, ordinal, value) =>
        updater.setInt(ordinal, value.asInstanceOf[IntWritable].get)

      case LongType => (updater, ordinal, value) =>
        updater.setLong(ordinal, value.asInstanceOf[LongWritable].get)

      case FloatType => (updater, ordinal, value) =>
        updater.setFloat(ordinal, value.asInstanceOf[FloatWritable].get)

      case DoubleType => (updater, ordinal, value) =>
        updater.setDouble(ordinal, value.asInstanceOf[DoubleWritable].get)

      case StringType => (updater, ordinal, value) =>
        updater.set(ordinal, UTF8String.fromBytes(value.asInstanceOf[Text].copyBytes))

      case BinaryType => (updater, ordinal, value) =>
        val binary = value.asInstanceOf[BytesWritable]
        val bytes = new Array[Byte](binary.getLength)
        System.arraycopy(binary.getBytes, 0, bytes, 0, binary.getLength)
        updater.set(ordinal, bytes)

      case DateType => (updater, ordinal, value) =>
        updater.setInt(ordinal, OrcShimUtils.getGregorianDays(value))

      case TimestampType => (updater, ordinal, value) =>
        updater.setLong(ordinal, DateTimeUtils.fromJavaTimestamp(value.asInstanceOf[OrcTimestamp]))

      case DecimalType.Fixed(precision, scale) => (updater, ordinal, value) =>
        val v = OrcShimUtils.getDecimal(value)
        v.changePrecision(precision, scale)
        updater.set(ordinal, v)

      case st: StructType =>
        val createRow: () => InternalRow = getRowCreator(st)
        val fieldConverters = st.map(_.dataType).map { dt =>
          newWriter(dt)
        }.toArray
        (updater, ordinal, value) => {
          val result = createRow.apply()
          val fieldUpdater = new RowUpdater(result)
          val orcStruct = value.asInstanceOf[OrcStruct]

          var i = 0
          while (i < st.length) {
            val value = orcStruct.getFieldValue(i)
            if (value == null) {
              result.setNullAt(i)
            } else {
              fieldConverters(i)(fieldUpdater, i, value)
            }
            i += 1
          }

          updater.set(ordinal, result)
        }

      case ArrayType(elementType, _) =>
        val elementConverter = newWriter(elementType)
        val createArray = getArrayDataCreator(elementType)
        (updater, ordinal, value) => {
          val orcArray = value.asInstanceOf[OrcList[WritableComparable[_]]]
          val length = orcArray.size()
          val result = createArray(length)
          val elementUpdater = new ArrayDataUpdater(result)

          var i = 0
          while (i < length) {
            val value = orcArray.get(i)
            if (value == null) {
              result.setNullAt(i)
            } else {
              elementConverter(elementUpdater, i, value)
            }
            i += 1
          }

          updater.set(ordinal, result)
        }

      case MapType(keyType, valueType, _) =>
        val keyConverter = newWriter(keyType)
        val createKeyArray = getArrayDataCreator(keyType)
        val valueConverter = newWriter(valueType)
        val createValueArray = getArrayDataCreator(valueType)
        (updater, ordinal, value) => {
          val orcMap = value.asInstanceOf[OrcMap[WritableComparable[_], WritableComparable[_]]]
          val length = orcMap.size()
          val keyArray = createKeyArray(length)
          val keyUpdater = new ArrayDataUpdater(keyArray)
          val valueArray = createValueArray(length)
          val valueUpdater = new ArrayDataUpdater(valueArray)

          var i = 0
          val it = orcMap.entrySet().iterator()
          while (it.hasNext) {
            val entry = it.next()
            keyConverter(keyUpdater, i, entry.getKey)
            val value = entry.getValue
            if (value == null) {
              valueArray.setNullAt(i)
            } else {
              valueConverter(valueUpdater, i, value)
            }
            i += 1
          }

          // The ORC map will never have null or duplicated map keys, it's safe to create a
          // ArrayBasedMapData directly here.
          updater.set(ordinal, new ArrayBasedMapData(keyArray, valueArray))
        }

      case udt: UserDefinedType[_] =>
        val converter = newWriter(udt.sqlType)
        (updater, ordinal, value) => {
          converter(updater, ordinal, value)
        }

      case _ =>
        throw new UnsupportedOperationException(s"$dataType is not supported yet.")
    }

  private def getArrayDataCreator(elementType: DataType): Int => ArrayData = elementType match {
    case BooleanType => length => UnsafeArrayData.createFreshArray(length, 1)
    case ByteType => length => UnsafeArrayData.createFreshArray(length, 1)
    case ShortType => length => UnsafeArrayData.createFreshArray(length, 2)
    case IntegerType => length => UnsafeArrayData.createFreshArray(length, 4)
    case LongType => length => UnsafeArrayData.createFreshArray(length, 8)
    case FloatType => length => UnsafeArrayData.createFreshArray(length, 4)
    case DoubleType => length => UnsafeArrayData.createFreshArray(length, 8)
    case _ => length => new GenericArrayData(new Array[Any](length))
  }

  private def getRowCreator(st: StructType): () => InternalRow = {
    val constructorsArray = new Array[() => MutableValue](st.fields.length)
    var i = 0
    while (i < st.fields.length) {
      st.fields(i).dataType match {
        case BooleanType => constructorsArray(i) = () => new MutableBoolean
        case ByteType => constructorsArray(i) = () => new MutableByte
        case ShortType => constructorsArray(i) = () => new MutableShort
        // We use INT for DATE internally
        case IntegerType | DateType => constructorsArray(i) = () => new MutableInt
        // We use Long for Timestamp internally
        case LongType | TimestampType => constructorsArray(i) = () => new MutableLong
        case FloatType => constructorsArray(i) = () => new MutableFloat
        case DoubleType => constructorsArray(i) = () => new MutableDouble
        case _ => constructorsArray(i) = () => new MutableAny
      }
      i += 1
    }

    () => {
      val array = new Array[MutableValue](constructorsArray.length)
      var i = 0
      while (i < constructorsArray.length) {
        array(i) = constructorsArray(i).apply()
        i += 1
      }
      new SpecificInternalRow(array)
    }
  }

  /**
   * A base interface for updating values inside catalyst data structure like `InternalRow` and
   * `ArrayData`.
   */
  sealed trait CatalystDataUpdater {
    def set(ordinal: Int, value: Any): Unit

    def setNullAt(ordinal: Int): Unit = set(ordinal, null)
    def setBoolean(ordinal: Int, value: Boolean): Unit = set(ordinal, value)
    def setByte(ordinal: Int, value: Byte): Unit = set(ordinal, value)
    def setShort(ordinal: Int, value: Short): Unit = set(ordinal, value)
    def setInt(ordinal: Int, value: Int): Unit = set(ordinal, value)
    def setLong(ordinal: Int, value: Long): Unit = set(ordinal, value)
    def setDouble(ordinal: Int, value: Double): Unit = set(ordinal, value)
    def setFloat(ordinal: Int, value: Float): Unit = set(ordinal, value)
  }

  final class RowUpdater(row: InternalRow) extends CatalystDataUpdater {
    override def setNullAt(ordinal: Int): Unit = row.setNullAt(ordinal)
    override def set(ordinal: Int, value: Any): Unit = row.update(ordinal, value)

    override def setBoolean(ordinal: Int, value: Boolean): Unit = row.setBoolean(ordinal, value)
    override def setByte(ordinal: Int, value: Byte): Unit = row.setByte(ordinal, value)
    override def setShort(ordinal: Int, value: Short): Unit = row.setShort(ordinal, value)
    override def setInt(ordinal: Int, value: Int): Unit = row.setInt(ordinal, value)
    override def setLong(ordinal: Int, value: Long): Unit = row.setLong(ordinal, value)
    override def setDouble(ordinal: Int, value: Double): Unit = row.setDouble(ordinal, value)
    override def setFloat(ordinal: Int, value: Float): Unit = row.setFloat(ordinal, value)
  }

  final class ArrayDataUpdater(array: ArrayData) extends CatalystDataUpdater {
    override def setNullAt(ordinal: Int): Unit = array.setNullAt(ordinal)
    override def set(ordinal: Int, value: Any): Unit = array.update(ordinal, value)

    override def setBoolean(ordinal: Int, value: Boolean): Unit = array.setBoolean(ordinal, value)
    override def setByte(ordinal: Int, value: Byte): Unit = array.setByte(ordinal, value)
    override def setShort(ordinal: Int, value: Short): Unit = array.setShort(ordinal, value)
    override def setInt(ordinal: Int, value: Int): Unit = array.setInt(ordinal, value)
    override def setLong(ordinal: Int, value: Long): Unit = array.setLong(ordinal, value)
    override def setDouble(ordinal: Int, value: Double): Unit = array.setDouble(ordinal, value)
    override def setFloat(ordinal: Int, value: Float): Unit = array.setFloat(ordinal, value)
  }
}
