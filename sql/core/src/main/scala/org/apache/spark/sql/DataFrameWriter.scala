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

package org.apache.spark.sql

import java.util.{Locale, Properties}

import scala.collection.JavaConverters._

import org.apache.spark.annotation.Stable
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{EliminateSubqueryAliases, NoSuchTableException, UnresolvedRelation}
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.catalyst.plans.logical.{AppendData, CreateTableAsSelect, CreateTableAsSelectStatement, InsertIntoStatement, LogicalPlan, OverwriteByExpression, OverwritePartitionsDynamic, ReplaceTableAsSelectStatement}
import org.apache.spark.sql.connector.catalog.{CatalogPlugin, CatalogV2Implicits, CatalogV2Util, Identifier, SupportsCatalogOptions, Table, TableCatalog, TableProvider, V1Table}
import org.apache.spark.sql.connector.catalog.TableCapability._
import org.apache.spark.sql.connector.expressions.{FieldReference, IdentityTransform, Transform}
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.command.DDLUtils
import org.apache.spark.sql.execution.datasources.{CreateTable, DataSource, DataSourceUtils, LogicalRelation}
import org.apache.spark.sql.execution.datasources.v2._
import org.apache.spark.sql.internal.SQLConf.PartitionOverwriteMode
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

/**
 * Interface used to write a [[Dataset]] to external storage systems (e.g. file systems,
 * key-value stores, etc). Use `Dataset.write` to access this.
 *
 * @since 1.4.0
 */
@Stable
final class DataFrameWriter[T] private[sql](ds: Dataset[T]) {

  private val df = ds.toDF()

  /**
   * Specifies the behavior when data or table already exists. Options include:
   * <ul>
   * <li>`SaveMode.Overwrite`: overwrite the existing data.</li>
   * <li>`SaveMode.Append`: append the data.</li>
   * <li>`SaveMode.Ignore`: ignore the operation (i.e. no-op).</li>
   * <li>`SaveMode.ErrorIfExists`: throw an exception at runtime.</li>
   * </ul>
   * <p>
   * When writing to data source v1, the default option is `ErrorIfExists`. When writing to data
   * source v2, the default option is `Append`.
   *
   * @since 1.4.0
   */
  def mode(saveMode: SaveMode): DataFrameWriter[T] = {
    this.mode = saveMode
    this
  }

  /**
   * Specifies the behavior when data or table already exists. Options include:
   * <ul>
   * <li>`overwrite`: overwrite the existing data.</li>
   * <li>`append`: append the data.</li>
   * <li>`ignore`: ignore the operation (i.e. no-op).</li>
   * <li>`error` or `errorifexists`: default option, throw an exception at runtime.</li>
   * </ul>
   *
   * @since 1.4.0
   */
  def mode(saveMode: String): DataFrameWriter[T] = {
    saveMode.toLowerCase(Locale.ROOT) match {
      case "overwrite" => mode(SaveMode.Overwrite)
      case "append" => mode(SaveMode.Append)
      case "ignore" => mode(SaveMode.Ignore)
      case "error" | "errorifexists" | "default" => mode(SaveMode.ErrorIfExists)
      case _ => throw new IllegalArgumentException(s"Unknown save mode: $saveMode. Accepted " +
        "save modes are 'overwrite', 'append', 'ignore', 'error', 'errorifexists', 'default'.")
    }
  }

  /**
   * Specifies the underlying output data source. Built-in options include "parquet", "json", etc.
   *
   * @since 1.4.0
   */
  def format(source: String): DataFrameWriter[T] = {
    this.source = source
    this
  }

  /**
   * Adds an output option for the underlying data source.
   *
   * You can set the following option(s):
   * <ul>
   * <li>`timeZone` (default session local timezone): sets the string that indicates a time zone ID
   * to be used to format timestamps in the JSON/CSV datasources or partition values. The following
   * formats of `timeZone` are supported:
   *   <ul>
   *     <li> Region-based zone ID: It should have the form 'area/city', such as
   *         'America/Los_Angeles'.</li>
   *     <li> Zone offset: It should be in the format '(+|-)HH:mm', for example '-08:00'
   *          or '+01:00'. Also 'UTC' and 'Z' are supported as aliases of '+00:00'.</li>
   *   </ul>
   * Other short names like 'CST' are not recommended to use because they can be ambiguous.
   * If it isn't set, the current value of the SQL config `spark.sql.session.timeZone` is
   * used by default.
   * </li>
   * </ul>
   *
   * @since 1.4.0
   */
  def option(key: String, value: String): DataFrameWriter[T] = {
    this.extraOptions += (key -> value)
    this
  }

  /**
   * Adds an output option for the underlying data source.
   *
   * @since 2.0.0
   */
  def option(key: String, value: Boolean): DataFrameWriter[T] = option(key, value.toString)

  /**
   * Adds an output option for the underlying data source.
   *
   * @since 2.0.0
   */
  def option(key: String, value: Long): DataFrameWriter[T] = option(key, value.toString)

  /**
   * Adds an output option for the underlying data source.
   *
   * @since 2.0.0
   */
  def option(key: String, value: Double): DataFrameWriter[T] = option(key, value.toString)

  /**
   * (Scala-specific) Adds output options for the underlying data source.
   *
   * You can set the following option(s):
   * <ul>
   * <li>`timeZone` (default session local timezone): sets the string that indicates a time zone ID
   * to be used to format timestamps in the JSON/CSV datasources or partition values. The following
   * formats of `timeZone` are supported:
   *   <ul>
   *     <li> Region-based zone ID: It should have the form 'area/city', such as
   *         'America/Los_Angeles'.</li>
   *     <li> Zone offset: It should be in the format '(+|-)HH:mm', for example '-08:00'
   *          or '+01:00'. Also 'UTC' and 'Z' are supported as aliases of '+00:00'.</li>
   *   </ul>
   * Other short names like 'CST' are not recommended to use because they can be ambiguous.
   * If it isn't set, the current value of the SQL config `spark.sql.session.timeZone` is
   * used by default.
   * </li>
   * </ul>
   *
   * @since 1.4.0
   */
  def options(options: scala.collection.Map[String, String]): DataFrameWriter[T] = {
    this.extraOptions ++= options
    this
  }

  /**
   * Adds output options for the underlying data source.
   *
   * You can set the following option(s):
   * <ul>
   * <li>`timeZone` (default session local timezone): sets the string that indicates a time zone ID
   * to be used to format timestamps in the JSON/CSV datasources or partition values. The following
   * formats of `timeZone` are supported:
   *   <ul>
   *     <li> Region-based zone ID: It should have the form 'area/city', such as
   *         'America/Los_Angeles'.</li>
   *     <li> Zone offset: It should be in the format '(+|-)HH:mm', for example '-08:00'
   *          or '+01:00'. Also 'UTC' and 'Z' are supported as aliases of '+00:00'.</li>
   *   </ul>
   * Other short names like 'CST' are not recommended to use because they can be ambiguous.
   * If it isn't set, the current value of the SQL config `spark.sql.session.timeZone` is
   * used by default.
   * </li>
   * </ul>
   *
   * @since 1.4.0
   */
  def options(options: java.util.Map[String, String]): DataFrameWriter[T] = {
    this.options(options.asScala)
    this
  }

  /**
   * Partitions the output by the given columns on the file system. If specified, the output is
   * laid out on the file system similar to Hive's partitioning scheme. As an example, when we
   * partition a dataset by year and then month, the directory layout would look like:
   * <ul>
   * <li>year=2016/month=01/</li>
   * <li>year=2016/month=02/</li>
   * </ul>
   *
   * Partitioning is one of the most widely used techniques to optimize physical data layout.
   * It provides a coarse-grained index for skipping unnecessary data reads when queries have
   * predicates on the partitioned columns. In order for partitioning to work well, the number
   * of distinct values in each column should typically be less than tens of thousands.
   *
   * This is applicable for all file-based data sources (e.g. Parquet, JSON) starting with Spark
   * 2.1.0.
   *
   * @since 1.4.0
   */
  @scala.annotation.varargs
  def partitionBy(colNames: String*): DataFrameWriter[T] = {
    this.partitioningColumns = Option(colNames)
    this
  }

  /**
   * Buckets the output by the given columns. If specified, the output is laid out on the file
   * system similar to Hive's bucketing scheme, but with a different bucket hash function
   * and is not compatible with Hive's bucketing.
   *
   * This is applicable for all file-based data sources (e.g. Parquet, JSON) starting with Spark
   * 2.1.0.
   *
   * @since 2.0
   */
  @scala.annotation.varargs
  def bucketBy(numBuckets: Int, colName: String, colNames: String*): DataFrameWriter[T] = {
    this.numBuckets = Option(numBuckets)
    this.bucketColumnNames = Option(colName +: colNames)
    this
  }

  /**
   * Sorts the output in each bucket by the given columns.
   *
   * This is applicable for all file-based data sources (e.g. Parquet, JSON) starting with Spark
   * 2.1.0.
   *
   * @since 2.0
   */
  @scala.annotation.varargs
  def sortBy(colName: String, colNames: String*): DataFrameWriter[T] = {
    this.sortColumnNames = Option(colName +: colNames)
    this
  }

  /**
   * Saves the content of the `DataFrame` at the specified path.
   *
   * @since 1.4.0
   */
  def save(path: String): Unit = {
    removeExtraOptionsByKey("path")
    this.extraOptions += ("path" -> path)
    save()
  }

  /**
   * Saves the content of the `DataFrame` as the specified table.
   *
   * @since 1.4.0
   */
  def save(): Unit = {
    if (source.toLowerCase(Locale.ROOT) == DDLUtils.HIVE_PROVIDER) {
      throw new AnalysisException("Hive data source can only be used with tables, you can not " +
        "write files of Hive data source directly.")
    }

    assertNotBucketed("save")

    val maybeV2Provider = lookupV2Provider()
    if (maybeV2Provider.isDefined) {
      val provider = maybeV2Provider.get
      val sessionOptions = DataSourceV2Utils.extractSessionConfigs(
        provider, df.sparkSession.sessionState.conf)
      val options = sessionOptions ++ extraOptions
      val dsOptions = new CaseInsensitiveStringMap(options.asJava)

      def getTable: Table = {
        // For file source, it's expensive to infer schema/partition at each write. Here we pass
        // the schema of input query and the user-specified partitioning to `getTable`. If the
        // query schema is not compatible with the existing data, the write can still success but
        // following reads would fail.
        if (provider.isInstanceOf[FileDataSourceV2]) {
          provider.getTable(
            df.schema.asNullable,
            partitioningAsV2.toArray,
            dsOptions.asCaseSensitiveMap())
        } else {
          DataSourceV2Utils.getTableFromProvider(provider, dsOptions, userSpecifiedSchema = None)
        }
      }

      import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Implicits._
      val catalogManager = df.sparkSession.sessionState.catalogManager
      mode match {
        case SaveMode.Append | SaveMode.Overwrite =>
          val (table, catalog, ident) = provider match {
            case supportsExtract: SupportsCatalogOptions =>
              val ident = supportsExtract.extractIdentifier(dsOptions)
              val catalog = CatalogV2Util.getTableProviderCatalog(
                supportsExtract, catalogManager, dsOptions)

              (catalog.loadTable(ident), Some(catalog), Some(ident))
            case _: TableProvider =>
              val t = getTable
              if (t.supports(BATCH_WRITE)) {
                (t, None, None)
              } else {
                // Streaming also uses the data source V2 API. So it may be that the data source
                // implements v2, but has no v2 implementation for batch writes. In that case, we
                // fall back to saving as though it's a V1 source.
                return saveToV1Source()
              }
          }

          val relation = DataSourceV2Relation.create(table, catalog, ident, dsOptions)
          checkPartitioningMatchesV2Table(table)
          if (mode == SaveMode.Append) {
            runCommand(df.sparkSession, "save") {
              AppendData.byName(relation, df.logicalPlan, extraOptions.toMap)
            }
          } else {
            // Truncate the table. TableCapabilityCheck will throw a nice exception if this
            // isn't supported
            runCommand(df.sparkSession, "save") {
              OverwriteByExpression.byName(
                relation, df.logicalPlan, Literal(true), extraOptions.toMap)
            }
          }

        case createMode =>
          provider match {
            case supportsExtract: SupportsCatalogOptions =>
              val ident = supportsExtract.extractIdentifier(dsOptions)
              val catalog = CatalogV2Util.getTableProviderCatalog(
                supportsExtract, catalogManager, dsOptions)

              val location = Option(dsOptions.get("path")).map(TableCatalog.PROP_LOCATION -> _)

              runCommand(df.sparkSession, "save") {
                CreateTableAsSelect(
                  catalog,
                  ident,
                  partitioningAsV2,
                  df.queryExecution.analyzed,
                  Map(TableCatalog.PROP_PROVIDER -> source) ++ location,
                  extraOptions.toMap,
                  ignoreIfExists = createMode == SaveMode.Ignore)
              }
            case _: TableProvider =>
              if (getTable.supports(BATCH_WRITE)) {
                throw new AnalysisException(s"TableProvider implementation $source cannot be " +
                    s"written with $createMode mode, please use Append or Overwrite " +
                    "modes instead.")
              } else {
                // Streaming also uses the data source V2 API. So it may be that the data source
                // implements v2, but has no v2 implementation for batch writes. In that case, we
                // fallback to saving as though it's a V1 source.
                saveToV1Source()
              }
          }
      }

    } else {
      saveToV1Source()
    }
  }

  private def saveToV1Source(): Unit = {
    partitioningColumns.foreach { columns =>
      extraOptions += (DataSourceUtils.PARTITIONING_COLUMNS_KEY ->
        DataSourceUtils.encodePartitioningColumns(columns))
    }

    // Code path for data source v1.
    runCommand(df.sparkSession, "save") {
      DataSource(
        sparkSession = df.sparkSession,
        className = source,
        partitionColumns = partitioningColumns.getOrElse(Nil),
        options = extraOptions.toMap).planForWriting(mode, df.logicalPlan)
    }
  }

  /**
   * Inserts the content of the `DataFrame` to the specified table. It requires that
   * the schema of the `DataFrame` is the same as the schema of the table.
   *
   * @note Unlike `saveAsTable`, `insertInto` ignores the column names and just uses position-based
   * resolution. For example:
   *
   * @note SaveMode.ErrorIfExists and SaveMode.Ignore behave as SaveMode.Append in `insertInto` as
   *       `insertInto` is not a table creating operation.
   *
   * {{{
   *    scala> Seq((1, 2)).toDF("i", "j").write.mode("overwrite").saveAsTable("t1")
   *    scala> Seq((3, 4)).toDF("j", "i").write.insertInto("t1")
   *    scala> Seq((5, 6)).toDF("a", "b").write.insertInto("t1")
   *    scala> sql("select * from t1").show
   *    +---+---+
   *    |  i|  j|
   *    +---+---+
   *    |  5|  6|
   *    |  3|  4|
   *    |  1|  2|
   *    +---+---+
   * }}}
   *
   * Because it inserts data to an existing table, format or options will be ignored.
   *
   * @since 1.4.0
   */
  def insertInto(tableName: String): Unit = {
    import df.sparkSession.sessionState.analyzer.{AsTableIdentifier, NonSessionCatalogAndIdentifier, SessionCatalogAndIdentifier}
    import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._
    import org.apache.spark.sql.connector.catalog.CatalogV2Util._

    assertNotBucketed("insertInto")

    if (partitioningColumns.isDefined) {
      throw new AnalysisException(
        "insertInto() can't be used together with partitionBy(). " +
          "Partition columns have already been defined for the table. " +
          "It is not necessary to use partitionBy()."
      )
    }

    val session = df.sparkSession
    val canUseV2 = lookupV2Provider().isDefined

    session.sessionState.sqlParser.parseMultipartIdentifier(tableName) match {
      case NonSessionCatalogAndIdentifier(catalog, ident) =>
        insertInto(catalog, ident)

      case SessionCatalogAndIdentifier(catalog, ident)
          if canUseV2 && ident.namespace().length <= 1 =>
        insertInto(catalog, ident)

      case AsTableIdentifier(tableIdentifier) =>
        insertInto(tableIdentifier)
      case other =>
        throw new AnalysisException(
          s"Couldn't find a catalog to handle the identifier ${other.quoted}.")
    }
  }

  private def insertInto(catalog: CatalogPlugin, ident: Identifier): Unit = {
    import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._

    val table = catalog.asTableCatalog.loadTable(ident) match {
      case _: V1Table =>
        return insertInto(TableIdentifier(ident.name(), ident.namespace().headOption))
      case t =>
        DataSourceV2Relation.create(t, Some(catalog), Some(ident))
    }

    val command = mode match {
      case SaveMode.Append | SaveMode.ErrorIfExists | SaveMode.Ignore =>
        AppendData.byPosition(table, df.logicalPlan, extraOptions.toMap)

      case SaveMode.Overwrite =>
        val conf = df.sparkSession.sessionState.conf
        val dynamicPartitionOverwrite = table.table.partitioning.size > 0 &&
          conf.partitionOverwriteMode == PartitionOverwriteMode.DYNAMIC

        if (dynamicPartitionOverwrite) {
          OverwritePartitionsDynamic.byPosition(table, df.logicalPlan, extraOptions.toMap)
        } else {
          OverwriteByExpression.byPosition(table, df.logicalPlan, Literal(true), extraOptions.toMap)
        }
    }

    runCommand(df.sparkSession, "insertInto") {
      command
    }
  }

  private def insertInto(tableIdent: TableIdentifier): Unit = {
    runCommand(df.sparkSession, "insertInto") {
      InsertIntoStatement(
        table = UnresolvedRelation(tableIdent),
        partitionSpec = Map.empty[String, Option[String]],
        query = df.logicalPlan,
        overwrite = mode == SaveMode.Overwrite,
        ifPartitionNotExists = false)
    }
  }

  private def getBucketSpec: Option[BucketSpec] = {
    if (sortColumnNames.isDefined && numBuckets.isEmpty) {
      throw new AnalysisException("sortBy must be used together with bucketBy")
    }

    numBuckets.map { n =>
      BucketSpec(n, bucketColumnNames.get, sortColumnNames.getOrElse(Nil))
    }
  }

  private def assertNotBucketed(operation: String): Unit = {
    if (getBucketSpec.isDefined) {
      if (sortColumnNames.isEmpty) {
        throw new AnalysisException(s"'$operation' does not support bucketBy right now")
      } else {
        throw new AnalysisException(s"'$operation' does not support bucketBy and sortBy right now")
      }
    }
  }

  private def assertNotPartitioned(operation: String): Unit = {
    if (partitioningColumns.isDefined) {
      throw new AnalysisException(s"'$operation' does not support partitioning")
    }
  }

  /**
   * Saves the content of the `DataFrame` as the specified table.
   *
   * In the case the table already exists, behavior of this function depends on the
   * save mode, specified by the `mode` function (default to throwing an exception).
   * When `mode` is `Overwrite`, the schema of the `DataFrame` does not need to be
   * the same as that of the existing table.
   *
   * When `mode` is `Append`, if there is an existing table, we will use the format and options of
   * the existing table. The column order in the schema of the `DataFrame` doesn't need to be same
   * as that of the existing table. Unlike `insertInto`, `saveAsTable` will use the column names to
   * find the correct column positions. For example:
   *
   * {{{
   *    scala> Seq((1, 2)).toDF("i", "j").write.mode("overwrite").saveAsTable("t1")
   *    scala> Seq((3, 4)).toDF("j", "i").write.mode("append").saveAsTable("t1")
   *    scala> sql("select * from t1").show
   *    +---+---+
   *    |  i|  j|
   *    +---+---+
   *    |  1|  2|
   *    |  4|  3|
   *    +---+---+
   * }}}
   *
   * In this method, save mode is used to determine the behavior if the data source table exists in
   * Spark catalog. We will always overwrite the underlying data of data source (e.g. a table in
   * JDBC data source) if the table doesn't exist in Spark catalog, and will always append to the
   * underlying data of data source if the table already exists.
   *
   * When the DataFrame is created from a non-partitioned `HadoopFsRelation` with a single input
   * path, and the data source provider can be mapped to an existing Hive builtin SerDe (i.e. ORC
   * and Parquet), the table is persisted in a Hive compatible format, which means other systems
   * like Hive will be able to read this table. Otherwise, the table is persisted in a Spark SQL
   * specific format.
   *
   * @since 1.4.0
   */
  def saveAsTable(tableName: String): Unit = {
    import df.sparkSession.sessionState.analyzer.{AsTableIdentifier, NonSessionCatalogAndIdentifier, SessionCatalogAndIdentifier}
    import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._

    val session = df.sparkSession
    val canUseV2 = lookupV2Provider().isDefined

    session.sessionState.sqlParser.parseMultipartIdentifier(tableName) match {
      case nameParts @ NonSessionCatalogAndIdentifier(catalog, ident) =>
        saveAsTable(catalog.asTableCatalog, ident, nameParts)

      case nameParts @ SessionCatalogAndIdentifier(catalog, ident)
          if canUseV2 && ident.namespace().length <= 1 =>
        saveAsTable(catalog.asTableCatalog, ident, nameParts)

      case AsTableIdentifier(tableIdentifier) =>
        saveAsTable(tableIdentifier)

      case other =>
        throw new AnalysisException(
          s"Couldn't find a catalog to handle the identifier ${other.quoted}.")
    }
  }


  private def saveAsTable(
      catalog: TableCatalog, ident: Identifier, nameParts: Seq[String]): Unit = {
    val tableOpt = try Option(catalog.loadTable(ident)) catch {
      case _: NoSuchTableException => None
    }

    val command = (mode, tableOpt) match {
      case (_, Some(_: V1Table)) =>
        return saveAsTable(TableIdentifier(ident.name(), ident.namespace().headOption))

      case (SaveMode.Append, Some(table)) =>
        checkPartitioningMatchesV2Table(table)
        val v2Relation = DataSourceV2Relation.create(table, Some(catalog), Some(ident))
        AppendData.byName(v2Relation, df.logicalPlan, extraOptions.toMap)

      case (SaveMode.Overwrite, _) =>
        ReplaceTableAsSelectStatement(
          nameParts,
          df.queryExecution.analyzed,
          partitioningAsV2,
          None,
          Map.empty,
          Some(source),
          Map.empty,
          extraOptions.get("path"),
          extraOptions.get(TableCatalog.PROP_COMMENT),
          extraOptions.toMap,
          orCreate = true)      // Create the table if it doesn't exist

      case (other, _) =>
        // We have a potential race condition here in AppendMode, if the table suddenly gets
        // created between our existence check and physical execution, but this can't be helped
        // in any case.
        CreateTableAsSelectStatement(
          nameParts,
          df.queryExecution.analyzed,
          partitioningAsV2,
          None,
          Map.empty,
          Some(source),
          Map.empty,
          extraOptions.get("path"),
          extraOptions.get(TableCatalog.PROP_COMMENT),
          extraOptions.toMap,
          ifNotExists = other == SaveMode.Ignore)
    }

    runCommand(df.sparkSession, "saveAsTable") {
      command
    }
  }

  private def saveAsTable(tableIdent: TableIdentifier): Unit = {
    val catalog = df.sparkSession.sessionState.catalog
    val tableExists = catalog.tableExists(tableIdent)
    val db = tableIdent.database.getOrElse(catalog.getCurrentDatabase)
    val tableIdentWithDB = tableIdent.copy(database = Some(db))
    val tableName = tableIdentWithDB.unquotedString

    (tableExists, mode) match {
      case (true, SaveMode.Ignore) =>
        // Do nothing

      case (true, SaveMode.ErrorIfExists) =>
        throw new AnalysisException(s"Table $tableIdent already exists.")

      case (true, SaveMode.Overwrite) =>
        // Get all input data source or hive relations of the query.
        val srcRelations = df.logicalPlan.collect {
          case LogicalRelation(src: BaseRelation, _, _, _) => src
          case relation: HiveTableRelation => relation.tableMeta.identifier
        }

        val tableRelation = df.sparkSession.table(tableIdentWithDB).queryExecution.analyzed
        EliminateSubqueryAliases(tableRelation) match {
          // check if the table is a data source table (the relation is a BaseRelation).
          case LogicalRelation(dest: BaseRelation, _, _, _) if srcRelations.contains(dest) =>
            throw new AnalysisException(
              s"Cannot overwrite table $tableName that is also being read from")
          // check hive table relation when overwrite mode
          case relation: HiveTableRelation
              if srcRelations.contains(relation.tableMeta.identifier) =>
            throw new AnalysisException(
              s"Cannot overwrite table $tableName that is also being read from")
          case _ => // OK
        }

        // Drop the existing table
        catalog.dropTable(tableIdentWithDB, ignoreIfNotExists = true, purge = false)
        createTable(tableIdentWithDB)
        // Refresh the cache of the table in the catalog.
        catalog.refreshTable(tableIdentWithDB)

      case _ => createTable(tableIdent)
    }
  }

  private def createTable(tableIdent: TableIdentifier): Unit = {
    val storage = DataSource.buildStorageFormatFromOptions(extraOptions.toMap)
    val tableType = if (storage.locationUri.isDefined) {
      CatalogTableType.EXTERNAL
    } else {
      CatalogTableType.MANAGED
    }

    val tableDesc = CatalogTable(
      identifier = tableIdent,
      tableType = tableType,
      storage = storage,
      schema = new StructType,
      provider = Some(source),
      partitionColumnNames = partitioningColumns.getOrElse(Nil),
      bucketSpec = getBucketSpec)

    runCommand(df.sparkSession, "saveAsTable")(
      CreateTable(tableDesc, mode, Some(df.logicalPlan)))
  }

  /** Converts the provided partitioning and bucketing information to DataSourceV2 Transforms. */
  private def partitioningAsV2: Seq[Transform] = {
    val partitioning = partitioningColumns.map { colNames =>
      colNames.map(name => IdentityTransform(FieldReference(name)))
    }.getOrElse(Seq.empty[Transform])
    val bucketing =
      getBucketSpec.map(spec => CatalogV2Implicits.BucketSpecHelper(spec).asTransform).toSeq
    partitioning ++ bucketing
  }

  /**
   * For V2 DataSources, performs if the provided partitioning matches that of the table.
   * Partitioning information is not required when appending data to V2 tables.
   */
  private def checkPartitioningMatchesV2Table(existingTable: Table): Unit = {
    val v2Partitions = partitioningAsV2
    if (v2Partitions.isEmpty) return
    require(v2Partitions.sameElements(existingTable.partitioning()),
      "The provided partitioning does not match of the table.\n" +
      s" - provided: ${v2Partitions.mkString(", ")}\n" +
      s" - table: ${existingTable.partitioning().mkString(", ")}")
  }

  /**
   * Saves the content of the `DataFrame` to an external database table via JDBC. In the case the
   * table already exists in the external database, behavior of this function depends on the
   * save mode, specified by the `mode` function (default to throwing an exception).
   *
   * Don't create too many partitions in parallel on a large cluster; otherwise Spark might crash
   * your external database systems.
   *
   * You can set the following JDBC-specific option(s) for storing JDBC:
   * <ul>
   * <li>`truncate` (default `false`): use `TRUNCATE TABLE` instead of `DROP TABLE`.</li>
   * </ul>
   *
   * In case of failures, users should turn off `truncate` option to use `DROP TABLE` again. Also,
   * due to the different behavior of `TRUNCATE TABLE` among DBMS, it's not always safe to use this.
   * MySQLDialect, DB2Dialect, MsSqlServerDialect, DerbyDialect, and OracleDialect supports this
   * while PostgresDialect and default JDBCDirect doesn't. For unknown and unsupported JDBCDirect,
   * the user option `truncate` is ignored.
   *
   * @param url JDBC database url of the form `jdbc:subprotocol:subname`
   * @param table Name of the table in the external database.
   * @param connectionProperties JDBC database connection arguments, a list of arbitrary string
   *                             tag/value. Normally at least a "user" and "password" property
   *                             should be included. "batchsize" can be used to control the
   *                             number of rows per insert. "isolationLevel" can be one of
   *                             "NONE", "READ_COMMITTED", "READ_UNCOMMITTED", "REPEATABLE_READ",
   *                             or "SERIALIZABLE", corresponding to standard transaction
   *                             isolation levels defined by JDBC's Connection object, with default
   *                             of "READ_UNCOMMITTED".
   * @since 1.4.0
   */
  def jdbc(url: String, table: String, connectionProperties: Properties): Unit = {
    assertNotPartitioned("jdbc")
    assertNotBucketed("jdbc")
    // connectionProperties should override settings in extraOptions.
    this.extraOptions ++= connectionProperties.asScala
    // explicit url and dbtable should override all
    this.extraOptions += ("url" -> url, "dbtable" -> table)
    format("jdbc").save()
  }

  /**
   * Saves the content of the `DataFrame` in JSON format (<a href="http://jsonlines.org/">
   * JSON Lines text format or newline-delimited JSON</a>) at the specified path.
   * This is equivalent to:
   * {{{
   *   format("json").save(path)
   * }}}
   *
   * You can set the following JSON-specific option(s) for writing JSON files:
   * <ul>
   * <li>`compression` (default `null`): compression codec to use when saving to file. This can be
   * one of the known case-insensitive shorten names (`none`, `bzip2`, `gzip`, `lz4`,
   * `snappy` and `deflate`). </li>
   * <li>`dateFormat` (default `yyyy-MM-dd`): sets the string that indicates a date format.
   * Custom date formats follow the formats at
   * <a href="https://spark.apache.org/docs/latest/sql-ref-datetime-pattern.html">
   *   Datetime Patterns</a>.
   * This applies to date type.</li>
   * <li>`timestampFormat` (default `yyyy-MM-dd'T'HH:mm:ss[.SSS][XXX]`): sets the string that
   * indicates a timestamp format. Custom date formats follow the formats at
   * <a href="https://spark.apache.org/docs/latest/sql-ref-datetime-pattern.html">
   *   Datetime Patterns</a>.
   * This applies to timestamp type.</li>
   * <li>`encoding` (by default it is not set): specifies encoding (charset) of saved json
   * files. If it is not set, the UTF-8 charset will be used. </li>
   * <li>`lineSep` (default `\n`): defines the line separator that should be used for writing.</li>
   * <li>`ignoreNullFields` (default `true`): Whether to ignore null fields
   * when generating JSON objects. </li>
   * </ul>
   *
   * @since 1.4.0
   */
  def json(path: String): Unit = {
    format("json").save(path)
  }

  /**
   * Saves the content of the `DataFrame` in Parquet format at the specified path.
   * This is equivalent to:
   * {{{
   *   format("parquet").save(path)
   * }}}
   *
   * You can set the following Parquet-specific option(s) for writing Parquet files:
   * <ul>
   * <li>`compression` (default is the value specified in `spark.sql.parquet.compression.codec`):
   * compression codec to use when saving to file. This can be one of the known case-insensitive
   * shorten names(`none`, `uncompressed`, `snappy`, `gzip`, `lzo`, `brotli`, `lz4`, and `zstd`).
   * This will override `spark.sql.parquet.compression.codec`.</li>
   * </ul>
   *
   * @since 1.4.0
   */
  def parquet(path: String): Unit = {
    format("parquet").save(path)
  }

  /**
   * Saves the content of the `DataFrame` in ORC format at the specified path.
   * This is equivalent to:
   * {{{
   *   format("orc").save(path)
   * }}}
   *
   * You can set the following ORC-specific option(s) for writing ORC files:
   * <ul>
   * <li>`compression` (default is the value specified in `spark.sql.orc.compression.codec`):
   * compression codec to use when saving to file. This can be one of the known case-insensitive
   * shorten names(`none`, `snappy`, `zlib`, and `lzo`). This will override
   * `orc.compress` and `spark.sql.orc.compression.codec`. If `orc.compress` is given,
   * it overrides `spark.sql.orc.compression.codec`.</li>
   * </ul>
   *
   * @since 1.5.0
   */
  def orc(path: String): Unit = {
    format("orc").save(path)
  }

  /**
   * Saves the content of the `DataFrame` in a text file at the specified path.
   * The DataFrame must have only one column that is of string type.
   * Each row becomes a new line in the output file. For example:
   * {{{
   *   // Scala:
   *   df.write.text("/path/to/output")
   *
   *   // Java:
   *   df.write().text("/path/to/output")
   * }}}
   * The text files will be encoded as UTF-8.
   *
   * You can set the following option(s) for writing text files:
   * <ul>
   * <li>`compression` (default `null`): compression codec to use when saving to file. This can be
   * one of the known case-insensitive shorten names (`none`, `bzip2`, `gzip`, `lz4`,
   * `snappy` and `deflate`). </li>
   * <li>`lineSep` (default `\n`): defines the line separator that should be used for writing.</li>
   * </ul>
   *
   * @since 1.6.0
   */
  def text(path: String): Unit = {
    format("text").save(path)
  }

  /**
   * Saves the content of the `DataFrame` in CSV format at the specified path.
   * This is equivalent to:
   * {{{
   *   format("csv").save(path)
   * }}}
   *
   * You can set the following CSV-specific option(s) for writing CSV files:
   * <ul>
   * <li>`sep` (default `,`): sets a single character as a separator for each
   * field and value.</li>
   * <li>`quote` (default `"`): sets a single character used for escaping quoted values where
   * the separator can be part of the value. If an empty string is set, it uses `u0000`
   * (null character).</li>
   * <li>`escape` (default `\`): sets a single character used for escaping quotes inside
   * an already quoted value.</li>
   * <li>`charToEscapeQuoteEscaping` (default `escape` or `\0`): sets a single character used for
   * escaping the escape for the quote character. The default value is escape character when escape
   * and quote characters are different, `\0` otherwise.</li>
   * <li>`escapeQuotes` (default `true`): a flag indicating whether values containing
   * quotes should always be enclosed in quotes. Default is to escape all values containing
   * a quote character.</li>
   * <li>`quoteAll` (default `false`): a flag indicating whether all values should always be
   * enclosed in quotes. Default is to only escape values containing a quote character.</li>
   * <li>`header` (default `false`): writes the names of columns as the first line.</li>
   * <li>`nullValue` (default empty string): sets the string representation of a null value.</li>
   * <li>`emptyValue` (default `""`): sets the string representation of an empty value.</li>
   * <li>`encoding` (by default it is not set): specifies encoding (charset) of saved csv
   * files. If it is not set, the UTF-8 charset will be used.</li>
   * <li>`compression` (default `null`): compression codec to use when saving to file. This can be
   * one of the known case-insensitive shorten names (`none`, `bzip2`, `gzip`, `lz4`,
   * `snappy` and `deflate`). </li>
   * <li>`dateFormat` (default `yyyy-MM-dd`): sets the string that indicates a date format.
   * Custom date formats follow the formats at
   * <a href="https://spark.apache.org/docs/latest/sql-ref-datetime-pattern.html">
   *   Datetime Patterns</a>.
   * This applies to date type.</li>
   * <li>`timestampFormat` (default `yyyy-MM-dd'T'HH:mm:ss[.SSS][XXX]`): sets the string that
   * indicates a timestamp format. Custom date formats follow the formats at
   * <a href="https://spark.apache.org/docs/latest/sql-ref-datetime-pattern.html">
   *   Datetime Patterns</a>.
   * This applies to timestamp type.</li>
   * <li>`ignoreLeadingWhiteSpace` (default `true`): a flag indicating whether or not leading
   * whitespaces from values being written should be skipped.</li>
   * <li>`ignoreTrailingWhiteSpace` (default `true`): a flag indicating defines whether or not
   * trailing whitespaces from values being written should be skipped.</li>
   * <li>`lineSep` (default `\n`): defines the line separator that should be used for writing.
   * Maximum length is 1 character.</li>
   * </ul>
   *
   * @since 2.0.0
   */
  def csv(path: String): Unit = {
    format("csv").save(path)
  }

  /**
   * Wrap a DataFrameWriter action to track the QueryExecution and time cost, then report to the
   * user-registered callback functions.
   */
  private def runCommand(session: SparkSession, name: String)(command: LogicalPlan): Unit = {
    val qe = session.sessionState.executePlan(command)
    // call `QueryExecution.toRDD` to trigger the execution of commands.
    SQLExecution.withNewExecutionId(qe, Some(name))(qe.toRdd)
  }

  private def lookupV2Provider(): Option[TableProvider] = {
    DataSource.lookupDataSourceV2(source, df.sparkSession.sessionState.conf) match {
      // TODO(SPARK-28396): File source v2 write path is currently broken.
      case Some(_: FileDataSourceV2) => None
      case other => other
    }
  }

  /**
   * Remove extra options which has the same key case-insensitively.
   */
  private def removeExtraOptionsByKey(key: String): Unit = {
    val keys = extraOptions.keys.filter(_.equalsIgnoreCase(key)).toList
    keys.foreach(extraOptions.remove)
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  // Builder pattern config options
  ///////////////////////////////////////////////////////////////////////////////////////

  private var source: String = df.sparkSession.sessionState.conf.defaultDataSourceName

  private var mode: SaveMode = SaveMode.ErrorIfExists

  private val extraOptions = new scala.collection.mutable.HashMap[String, String]

  private var partitioningColumns: Option[Seq[String]] = None

  private var bucketColumnNames: Option[Seq[String]] = None

  private var numBuckets: Option[Int] = None

  private var sortColumnNames: Option[Seq[String]] = None
}
