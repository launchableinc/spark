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

package org.apache.spark.sql.execution.command

import org.scalactic.source.Position
import org.scalatest.Tag

import org.apache.spark.sql.{AnalysisException, QueryTest, Row}
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.types.{StringType, StructType}

trait ShowPartitionsSuiteBase extends QueryTest with SQLTestUtils {
  protected def version: String
  protected def catalog: String
  protected def defaultNamespace: Seq[String]
  protected def defaultUsing: String
  protected def createDateTable(table: String): Unit
  protected def wrongPartitionColumnsError(columns: String*): String

  protected def runShowPartitionsSql(sqlText: String, expected: Seq[Row]): Unit = {
    val df = spark.sql(sqlText)
    assert(df.schema === new StructType().add("partition", StringType, false))
    checkAnswer(df, expected)
  }

  override def test(testName: String, testTags: Tag*)(testFun: => Any)
      (implicit pos: Position): Unit = {
    super.test(s"SHOW PARTITIONS $version: " + testName, testTags: _*)(testFun)
  }

  test("show partitions of non-partitioned table") {
    withNamespace(s"$catalog.ns") {
      sql(s"CREATE NAMESPACE $catalog.ns")
      val table = s"$catalog.ns.not_partitioned_table"
      withTable(table) {
        sql(s"CREATE TABLE $table (col1 int) $defaultUsing")
        val errMsg = intercept[AnalysisException] {
          sql(s"SHOW PARTITIONS $table")
        }.getMessage
        assert(errMsg.contains("not allowed on a table that is not partitioned"))
      }
    }
  }

  test("non-partitioning columns") {
    withNamespace(s"$catalog.ns") {
      sql(s"CREATE NAMESPACE $catalog.ns")
      val table = s"$catalog.ns.dateTable"
      withTable(table) {
        createDateTable(table)
        val errMsg = intercept[AnalysisException] {
          sql(s"SHOW PARTITIONS $table PARTITION(abcd=2015, xyz=1)")
        }.getMessage
        assert(errMsg.contains(wrongPartitionColumnsError("abcd", "xyz")))
      }
    }
  }

  test("show everything") {
    withNamespace(s"$catalog.ns") {
      sql(s"CREATE NAMESPACE $catalog.ns")
      val table = s"$catalog.ns.dateTable"
      withTable(table) {
        createDateTable(table)
        runShowPartitionsSql(
          s"show partitions $table",
          Row("year=2015/month=1") ::
            Row("year=2015/month=2") ::
            Row("year=2016/month=2") ::
            Row("year=2016/month=3") :: Nil)
      }
    }
  }
}
