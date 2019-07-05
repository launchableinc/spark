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

package org.apache.spark.sql.hive.execution

import scala.collection.JavaConverters._
import scala.util.Random

import test.org.apache.spark.sql.MyDoubleAvg
import test.org.apache.spark.sql.MyDoubleSum

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.expressions.{UserDefinedImperativeAggregator}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.hive.test.TestHiveSingleton
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.types._

class MyDoubleAvgUDIA extends UserDefinedImperativeAggregator[(Double, Long)] {
  import org.apache.spark.unsafe.Platform

  def inputSchema: StructType = new StructType()
    .add("x", DoubleType, true)

  def resultType: DataType = DoubleType

  def deterministic: Boolean = true

  def initial: (Double, Long) = (0D, 0L)

  def update(agg: (Double, Long), input: Row): (Double, Long) = {
    if (input.isNullAt(0)) agg else (agg._1 + input.getDouble(0), agg._2 + 1L)
  }

  def merge(agg1: (Double, Long), agg2: (Double, Long)): (Double, Long) =
    (agg1._1 + agg2._1, agg1._2 + agg2._2)

  def evaluate(agg: (Double, Long)): Any = {
    val (s, n) = agg
    if (n == 0L) null else 100.0 + (s / n.toDouble)
  }

  def serialize(agg: (Double, Long)): Array[Byte] = {
    val (s, n) = agg
    val byteArray = new Array[Byte](2 * 8)
    Platform.putDouble(byteArray, Platform.BYTE_ARRAY_OFFSET, s)
    Platform.putLong(byteArray, Platform.BYTE_ARRAY_OFFSET + 8, n)
    byteArray
  }

  def deserialize(data: Array[Byte]): (Double, Long) = {
    val s = Platform.getDouble(data, Platform.BYTE_ARRAY_OFFSET)
    val n = Platform.getLong(data, Platform.BYTE_ARRAY_OFFSET + 8)
    (s, n)
  }
}

class MyDoubleSumUDIA extends MyDoubleAvgUDIA {
  override def evaluate(agg: (Double, Long)): Any = {
    val (s, n) = agg
    if (n == 0L) null else s
  }
}

class LongProductSumUDIA extends UserDefinedImperativeAggregator[Long] {
  import org.apache.spark.unsafe.Platform

  def inputSchema: StructType = new StructType()
    .add("a", LongType)
    .add("b", LongType)

  def resultType: DataType = LongType

  def deterministic: Boolean = true

  def initial: Long = 0L

  def update(agg: Long, input: Row): Long = {
    if (!(input.isNullAt(0) || input.isNullAt(1))) {
      agg + (input.getLong(0) * input.getLong(1))
    } else {
      agg
    }
  }

  def merge(agg1: Long, agg2: Long): Long = agg1 + agg2

  def evaluate(agg: Long): Any = agg

  def serialize(agg: Long): Array[Byte] = {
    val byteArray = new Array[Byte](8)
    Platform.putLong(byteArray, Platform.BYTE_ARRAY_OFFSET, agg)
    byteArray
  }

  def deserialize(data: Array[Byte]): Long = {
    Platform.getLong(data, Platform.BYTE_ARRAY_OFFSET)
  }
}

abstract class UDIAQuerySuite extends QueryTest with SQLTestUtils with TestHiveSingleton {
  import testImplicits._

  override def beforeAll(): Unit = {
    super.beforeAll()
    val data1 = Seq[(Integer, Integer)](
      (1, 10),
      (null, -60),
      (1, 20),
      (1, 30),
      (2, 0),
      (null, -10),
      (2, -1),
      (2, null),
      (2, null),
      (null, 100),
      (3, null),
      (null, null),
      (3, null)).toDF("key", "value")
    data1.write.saveAsTable("agg1")

    val data2 = Seq[(Integer, Integer, Integer)](
      (1, 10, -10),
      (null, -60, 60),
      (1, 30, -30),
      (1, 30, 30),
      (2, 1, 1),
      (null, -10, 10),
      (2, -1, null),
      (2, 1, 1),
      (2, null, 1),
      (null, 100, -10),
      (3, null, 3),
      (null, null, null),
      (3, null, null)).toDF("key", "value1", "value2")
    data2.write.saveAsTable("agg2")

    val data3 = Seq[(Seq[Integer], Integer, Integer)](
      (Seq[Integer](1, 1), 10, -10),
      (Seq[Integer](null), -60, 60),
      (Seq[Integer](1, 1), 30, -30),
      (Seq[Integer](1), 30, 30),
      (Seq[Integer](2), 1, 1),
      (null, -10, 10),
      (Seq[Integer](2, 3), -1, null),
      (Seq[Integer](2, 3), 1, 1),
      (Seq[Integer](2, 3, 4), null, 1),
      (Seq[Integer](null), 100, -10),
      (Seq[Integer](3), null, 3),
      (null, null, null),
      (Seq[Integer](3), null, null)).toDF("key", "value1", "value2")
    data3.write.saveAsTable("agg3")

    val emptyDF = spark.createDataFrame(
      sparkContext.emptyRDD[Row],
      StructType(StructField("key", StringType) :: StructField("value", IntegerType) :: Nil))
    emptyDF.createOrReplaceTempView("emptyTable")

    // Register UDIAs
    spark.udf.register("mydoublesum", new MyDoubleSumUDIA)
    spark.udf.register("mydoubleavg", new MyDoubleAvgUDIA)
    spark.udf.register("longProductSum", new LongProductSumUDIA)
  }

  override def afterAll(): Unit = {
    try {
      spark.sql("DROP TABLE IF EXISTS agg1")
      spark.sql("DROP TABLE IF EXISTS agg2")
      spark.sql("DROP TABLE IF EXISTS agg3")
      spark.catalog.dropTempView("emptyTable")
    } finally {
      super.afterAll()
    }
  }

  test("udia") {
    checkAnswer(
      spark.sql(
        """
          |SELECT
          |  key,
          |  mydoublesum(value + 1.5 * key),
          |  mydoubleavg(value),
          |  avg(value - key),
          |  mydoublesum(value - 1.5 * key),
          |  avg(value)
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      Row(1, 64.5, 120.0, 19.0, 55.5, 20.0) ::
        Row(2, 5.0, 99.5, -2.5, -7.0, -0.5) ::
        Row(3, null, null, null, null, null) ::
        Row(null, null, 110.0, null, null, 10.0) :: Nil)
  }

  test("non-deterministic children expressions of UDIA") {
    val e = intercept[AnalysisException] {
      spark.sql(
        """
          |SELECT mydoublesum(value + 1.5 * key + rand())
          |FROM agg1
          |GROUP BY key
        """.stripMargin)
    }.getMessage
    assert(Seq("nondeterministic expression",
      "should not appear in the arguments of an aggregate function").forall(e.contains))
  }

  test("interpreted aggregate function") {
    checkAnswer(
      spark.sql(
        """
          |SELECT mydoublesum(value), key
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      Row(60.0, 1) :: Row(-1.0, 2) :: Row(null, 3) :: Row(30.0, null) :: Nil)

    checkAnswer(
      spark.sql(
        """
          |SELECT mydoublesum(value) FROM agg1
        """.stripMargin),
      Row(89.0) :: Nil)

    checkAnswer(
      spark.sql(
        """
          |SELECT mydoublesum(null)
        """.stripMargin),
      Row(null) :: Nil)
  }

  test("interpreted and expression-based aggregation functions") {
    checkAnswer(
      spark.sql(
        """
          |SELECT mydoublesum(value), key, avg(value)
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      Row(60.0, 1, 20.0) ::
        Row(-1.0, 2, -0.5) ::
        Row(null, 3, null) ::
        Row(30.0, null, 10.0) :: Nil)

    checkAnswer(
      spark.sql(
        """
          |SELECT
          |  mydoublesum(value + 1.5 * key),
          |  avg(value - key),
          |  key,
          |  mydoublesum(value - 1.5 * key),
          |  avg(value)
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      Row(64.5, 19.0, 1, 55.5, 20.0) ::
        Row(5.0, -2.5, 2, -7.0, -0.5) ::
        Row(null, null, 3, null, null) ::
        Row(null, null, null, null, 10.0) :: Nil)
  }

  test("single distinct column set") {
    checkAnswer(
      spark.sql(
        """
          |SELECT
          |  mydoubleavg(distinct value1),
          |  avg(value1),
          |  avg(value2),
          |  key,
          |  mydoubleavg(value1 - 1),
          |  mydoubleavg(distinct value1) * 0.1,
          |  avg(value1 + value2)
          |FROM agg2
          |GROUP BY key
        """.stripMargin),
      Row(120.0, 70.0/3.0, -10.0/3.0, 1, 67.0/3.0 + 100.0, 12.0, 20.0) ::
        Row(100.0, 1.0/3.0, 1.0, 2, -2.0/3.0 + 100.0, 10.0, 2.0) ::
        Row(null, null, 3.0, 3, null, null, null) ::
        Row(110.0, 10.0, 20.0, null, 109.0, 11.0, 30.0) :: Nil)

    checkAnswer(
      spark.sql(
        """
          |SELECT
          |  key,
          |  mydoubleavg(distinct value1),
          |  mydoublesum(value2),
          |  mydoublesum(distinct value1),
          |  mydoubleavg(distinct value1),
          |  mydoubleavg(value1)
          |FROM agg2
          |GROUP BY key
        """.stripMargin),
      Row(1, 120.0, -10.0, 40.0, 120.0, 70.0/3.0 + 100.0) ::
        Row(2, 100.0, 3.0, 0.0, 100.0, 1.0/3.0 + 100.0) ::
        Row(3, null, 3.0, null, null, null) ::
        Row(null, 110.0, 60.0, 30.0, 110.0, 110.0) :: Nil)
  }
  test("multiple distinct multiple columns sets") {
    checkAnswer(
      spark.sql(
        """
          |SELECT
          |  key,
          |  count(distinct value1),
          |  sum(distinct value1),
          |  count(distinct value2),
          |  sum(distinct value2),
          |  count(distinct value1, value2),
          |  longProductSum(distinct value1, value2),
          |  count(value1),
          |  sum(value1),
          |  count(value2),
          |  sum(value2),
          |  longProductSum(value1, value2),
          |  count(*),
          |  count(1)
          |FROM agg2
          |GROUP BY key
        """.stripMargin),
      Row(null, 3, 30, 3, 60, 3, -4700, 3, 30, 3, 60, -4700, 4, 4) ::
        Row(1, 2, 40, 3, -10, 3, -100, 3, 70, 3, -10, -100, 3, 3) ::
        Row(2, 2, 0, 1, 1, 1, 1, 3, 1, 3, 3, 2, 4, 4) ::
        Row(3, 0, null, 1, 3, 0, 0, 0, null, 1, 3, 0, 2, 2) :: Nil)
  }
}


class HashUDIAQuerySuite extends UDIAQuerySuite


class HashUDIAQueryWithControlledFallbackSuite extends UDIAQuerySuite {

  override protected def checkAnswer(actual: => DataFrame, expectedAnswer: Seq[Row]): Unit = {
    Seq("true", "false").foreach { enableTwoLevelMaps =>
      withSQLConf("spark.sql.codegen.aggregate.map.twolevel.enabled" ->
        enableTwoLevelMaps) {
        (1 to 3).foreach { fallbackStartsAt =>
          withSQLConf("spark.sql.TungstenAggregate.testFallbackStartsAt" ->
            s"${(fallbackStartsAt - 1).toString}, ${fallbackStartsAt.toString}") {
            // Create a new df to make sure its physical operator picks up
            // spark.sql.TungstenAggregate.testFallbackStartsAt.
            // todo: remove it?
            val newActual = Dataset.ofRows(spark, actual.logicalPlan)

            QueryTest.checkAnswer(newActual, expectedAnswer) match {
              case Some(errorMessage) =>
                val newErrorMessage =
                  s"""
                     |The following aggregation query failed when using HashAggregate with
                     |controlled fallback (it falls back to bytes to bytes map once it has processed
                     |${fallbackStartsAt - 1} input rows and to sort-based aggregation once it has
                     |processed $fallbackStartsAt input rows). The query is ${actual.queryExecution}
                     |
                    |$errorMessage
                  """.stripMargin

                fail(newErrorMessage)
              case None => // Success
            }
          }
        }
      }
    }
  }

  // Override it to make sure we call the actually overridden checkAnswer.
  override protected def checkAnswer(df: => DataFrame, expectedAnswer: Row): Unit = {
    checkAnswer(df, Seq(expectedAnswer))
  }

  // Override it to make sure we call the actually overridden checkAnswer.
  override protected def checkAnswer(df: => DataFrame, expectedAnswer: DataFrame): Unit = {
    checkAnswer(df, expectedAnswer.collect())
  }
}
