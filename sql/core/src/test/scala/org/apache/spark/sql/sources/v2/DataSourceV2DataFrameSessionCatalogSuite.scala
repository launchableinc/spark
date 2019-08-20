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

package org.apache.spark.sql.sources.v2

import java.util
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import org.scalatest.BeforeAndAfter

import org.apache.spark.sql.{AnalysisException, DataFrame, QueryTest, SaveMode}
import org.apache.spark.sql.catalog.v2.Identifier
import org.apache.spark.sql.catalog.v2.expressions.Transform
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException
import org.apache.spark.sql.execution.datasources.v2.V2SessionCatalog
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.{PARTITION_OVERWRITE_MODE, PartitionOverwriteMode}
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

class DataSourceV2DataFrameSessionCatalogSuite extends SessionCatalogTests {
  import testImplicits._

  override protected def doInsert(tableName: String, insert: DataFrame, mode: SaveMode): Unit = {
    val dfw = insert.write.format(v2Format)
    if (mode != null) {
      dfw.mode(mode)
    }
    dfw.insertInto(tableName)
  }

  test("saveAsTable: v2 table - table doesn't exist and default mode (ErrorIfExists)") {
    val t1 = "tbl"
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    df.write.format(v2Format).saveAsTable(t1)
    verifyTable(t1, df)
  }

  test("saveAsTable: v2 table - table doesn't exist and append mode") {
    val t1 = "tbl"
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    df.write.format(v2Format).mode("append").saveAsTable(t1)
    verifyTable(t1, df)
  }

  test("saveAsTable: Append mode should not fail if the table not exists " +
    "but a same-name temp view exist") {
    withTable("same_name") {
      withTempView("same_name") {
        spark.range(10).createTempView("same_name")
        spark.range(20).write.format(v2Format).mode(SaveMode.Append).saveAsTable("same_name")
        assert(
          spark.sessionState.catalog.tableExists(TableIdentifier("same_name", Some("default"))))
      }
    }
  }

  test("saveAsTable: Append mode should not fail if the table already exists " +
    "and a same-name temp view exist") {
    withTable("same_name") {
      withTempView("same_name") {
        val format = spark.sessionState.conf.defaultDataSourceName
        sql(s"CREATE TABLE same_name(id LONG) USING $format")
        spark.range(10).createTempView("same_name")
        spark.range(20).write.format(v2Format).mode(SaveMode.Append).saveAsTable("same_name")
        checkAnswer(spark.table("same_name"), spark.range(10).toDF())
        checkAnswer(spark.table("default.same_name"), spark.range(20).toDF())
      }
    }
  }

  test("saveAsTable: v2 table - table exists") {
    val t1 = "tbl"
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    spark.sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format")
    intercept[TableAlreadyExistsException] {
      df.select("id", "data").write.format(v2Format).saveAsTable(t1)
    }
    df.write.format(v2Format).mode("append").saveAsTable(t1)
    verifyTable(t1, df)

    // Check that appends are by name
    df.select('data, 'id).write.format(v2Format).mode("append").saveAsTable(t1)
    verifyTable(t1, df.union(df))
  }

  test("saveAsTable: v2 table - table overwrite and table doesn't exist") {
    val t1 = "tbl"
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    df.write.format(v2Format).mode("overwrite").saveAsTable(t1)
    verifyTable(t1, df)
  }

  test("saveAsTable: v2 table - table overwrite and table exists") {
    val t1 = "tbl"
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    spark.sql(s"CREATE TABLE $t1 USING $v2Format AS SELECT 'c', 'd'")
    df.write.format(v2Format).mode("overwrite").saveAsTable(t1)
    verifyTable(t1, df)
  }

  test("saveAsTable: Overwrite mode should not drop the temp view if the table not exists " +
    "but a same-name temp view exist") {
    withTable("same_name") {
      withTempView("same_name") {
        spark.range(10).createTempView("same_name")
        spark.range(20).write.format(v2Format).mode(SaveMode.Overwrite).saveAsTable("same_name")
        assert(spark.sessionState.catalog.getTempView("same_name").isDefined)
        assert(
          spark.sessionState.catalog.tableExists(TableIdentifier("same_name", Some("default"))))
      }
    }
  }

  test("saveAsTable with mode Overwrite should not fail if the table already exists " +
    "and a same-name temp view exist") {
    withTable("same_name") {
      withTempView("same_name") {
        sql(s"CREATE TABLE same_name(id LONG) USING $v2Format")
        spark.range(10).createTempView("same_name")
        spark.range(20).write.format(v2Format).mode(SaveMode.Overwrite).saveAsTable("same_name")
        checkAnswer(spark.table("same_name"), spark.range(10).toDF())
        checkAnswer(spark.table("default.same_name"), spark.range(20).toDF())
      }
    }
  }

  test("saveAsTable: v2 table - ignore mode and table doesn't exist") {
    val t1 = "tbl"
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    df.write.format(v2Format).mode("ignore").saveAsTable(t1)
    verifyTable(t1, df)
  }

  test("saveAsTable: v2 table - ignore mode and table exists") {
    val t1 = "tbl"
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    spark.sql(s"CREATE TABLE $t1 USING $v2Format AS SELECT 'c', 'd'")
    df.write.format(v2Format).mode("ignore").saveAsTable(t1)
    verifyTable(t1, Seq(("c", "d")).toDF("id", "data"))
  }
}

class InMemoryTableProvider extends TableProvider {
  override def getTable(options: CaseInsensitiveStringMap): Table = {
    throw new UnsupportedOperationException("D'oh!")
  }
}

/** A SessionCatalog that always loads an in memory Table, so we can test write code paths. */
class TestV2SessionCatalog extends V2SessionCatalog {

  protected val tables: util.Map[Identifier, InMemoryTable] =
    new ConcurrentHashMap[Identifier, InMemoryTable]()

  private def fullIdentifier(ident: Identifier): Identifier = {
    if (ident.namespace().isEmpty) {
      Identifier.of(Array("default"), ident.name())
    } else {
      ident
    }
  }

  override def loadTable(ident: Identifier): Table = {
    val fullIdent = fullIdentifier(ident)
    if (tables.containsKey(fullIdent)) {
      tables.get(fullIdent)
    } else {
      // Table was created through the built-in catalog
      val t = super.loadTable(fullIdent)
      val table = new InMemoryTable(t.name(), t.schema(), t.partitioning(), t.properties())
      tables.put(fullIdent, table)
      table
    }
  }

  override def createTable(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): Table = {
    val created = super.createTable(ident, schema, partitions, properties)
    val t = new InMemoryTable(created.name(), schema, partitions, properties)
    val fullIdent = fullIdentifier(ident)
    tables.put(fullIdent, t)
    t
  }

  def clearTables(): Unit = {
    assert(!tables.isEmpty, "Tables were empty, maybe didn't use the session catalog code path?")
    tables.keySet().asScala.foreach(super.dropTable)
    tables.clear()
  }
}

private[v2] trait SessionCatalogTests
  extends QueryTest
  with SharedSparkSession
  with BeforeAndAfter {

  import testImplicits._

  protected val v2Format: String = classOf[InMemoryTableProvider].getName

  before {
    spark.conf.set(SQLConf.V2_SESSION_CATALOG.key, classOf[TestV2SessionCatalog].getName)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    spark.catalog("session").asInstanceOf[TestV2SessionCatalog].clearTables()
    spark.conf.set(SQLConf.V2_SESSION_CATALOG.key, classOf[V2SessionCatalog].getName)
  }

  protected def verifyTable(tableName: String, expected: DataFrame): Unit = {
    checkAnswer(spark.table(tableName), expected)
    checkAnswer(sql(s"SELECT * FROM $tableName"), expected)
    checkAnswer(sql(s"SELECT * FROM default.$tableName"), expected)
    checkAnswer(sql(s"TABLE $tableName"), expected)
  }

  protected def doInsert(tableName: String, insert: DataFrame, mode: SaveMode = null): Unit

  test("insertInto: append") {
    val t1 = "tbl"
    sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format")
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    doInsert(t1, df)
    verifyTable(t1, df)
  }

  test("insertInto: append by position") {
    val t1 = "tbl"
    sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format")
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    val dfr = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("data", "id")

    doInsert(t1, dfr)
    verifyTable(t1, df)
  }

  test("insertInto: append partitioned table") {
    val t1 = "tbl"
    withTable(t1) {
      sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format PARTITIONED BY (id)")
      val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
      doInsert(t1, df)
      verifyTable(t1, df)
    }
  }

  test("insertInto: overwrite non-partitioned table") {
    val t1 = "tbl"
    sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format")
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    val df2 = Seq((4L, "d"), (5L, "e"), (6L, "f")).toDF("id", "data")
    doInsert(t1, df)
    doInsert(t1, df2, SaveMode.Overwrite)
    verifyTable(t1, df2)
  }

  test("insertInto: overwrite partitioned table in static mode") {
    withSQLConf(PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.STATIC.toString) {
      val t1 = "tbl"
      sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format PARTITIONED BY (id)")
      val init = Seq((2L, "dummy"), (4L, "keep")).toDF("id", "data")
      doInsert(t1, init)

      val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
      doInsert(t1, df, SaveMode.Overwrite)
      verifyTable(t1, df)
    }
  }


  test("insertInto: overwrite partitioned table in static mode by position") {
    withSQLConf(PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.STATIC.toString) {
      val t1 = "tbl"
      withTable(t1) {
        sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format PARTITIONED BY (id)")
        val init = Seq((2L, "dummy"), (4L, "keep")).toDF("id", "data")
        doInsert(t1, init)

        val dfr = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("data", "id")
        doInsert(t1, dfr, SaveMode.Overwrite)

        val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
        verifyTable(t1, df)
      }
    }
  }

  test("insertInto: overwrite partitioned table in dynamic mode") {
    withSQLConf(PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.DYNAMIC.toString) {
      val t1 = "tbl"
      withTable(t1) {
        sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format PARTITIONED BY (id)")
        val init = Seq((2L, "dummy"), (4L, "keep")).toDF("id", "data")
        doInsert(t1, init)

        val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
        doInsert(t1, df, SaveMode.Overwrite)

        verifyTable(t1, df.union(sql("SELECT 4L, 'keep'")))
      }
    }
  }

  test("insertInto: overwrite partitioned table in dynamic mode by position") {
    withSQLConf(PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.DYNAMIC.toString) {
      val t1 = "tbl"
      withTable(t1) {
        sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format PARTITIONED BY (id)")
        val init = Seq((2L, "dummy"), (4L, "keep")).toDF("id", "data")
        doInsert(t1, init)

        val dfr = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("data", "id")
        doInsert(t1, dfr, SaveMode.Overwrite)

        val df = Seq((1L, "a"), (2L, "b"), (3L, "c"), (4L, "keep")).toDF("id", "data")
        verifyTable(t1, df)
      }
    }
  }

  test("insertInto: fails when missing a column") {
    val t1 = "tbl"
    sql(s"CREATE TABLE $t1 (id bigint, data string, missing string) USING $v2Format")
    val df = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
    val exc = intercept[AnalysisException] {
      doInsert(t1, df)
    }

    assert(spark.table(t1).count === 0)
    assert(exc.getMessage.contains(s"Cannot write to 'default.$t1', not enough data columns"))
  }

  test("insertInto: fails when an extra column is present") {
    val t1 = "tbl"
    withTable(t1) {
      sql(s"CREATE TABLE $t1 (id bigint, data string) USING $v2Format")
      val df = Seq((1L, "a", "mango")).toDF("id", "data", "fruit")
      val exc = intercept[AnalysisException] {
        doInsert(t1, df)
      }

      assert(spark.table(t1).count === 0)
      assert(exc.getMessage.contains(s"Cannot write to 'default.$t1', too many data columns"))
    }
  }
}
