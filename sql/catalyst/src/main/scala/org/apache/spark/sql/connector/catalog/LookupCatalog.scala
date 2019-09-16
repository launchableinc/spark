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

package org.apache.spark.sql.connector.catalog

import org.apache.spark.annotation.Experimental
import org.apache.spark.internal.Logging
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.TableIdentifier

/**
 * A trait to encapsulate catalog lookup function and helpful extractors.
 */
private[sql] trait LookupCatalog extends Logging {

  protected val catalogManager: CatalogManager

  /**
   * Returns the default catalog. When set, this catalog is used for all identifiers that do not
   * set a specific catalog. When this is None, the session catalog is responsible for the
   * identifier.
   *
   * If this is None and a table's provider (source) is a v2 provider, the v2 session catalog will
   * be used.
   */
  def defaultCatalog: Option[CatalogPlugin] = catalogManager.defaultCatalog

  /**
   * This catalog is a v2 catalog that delegates to the v1 session catalog. it is used when the
   * session catalog is responsible for an identifier, but the source requires the v2 catalog API.
   * This happens when the source implementation extends the v2 TableProvider API and is not listed
   * in the fallback configuration, spark.sql.sources.write.useV1SourceList
   */
  def sessionCatalog: CatalogPlugin = catalogManager.v2SessionCatalog

  /**
   * Extract catalog plugin and remaining identifier names.
   *
   * This does not substitute the default catalog if no catalog is set in the identifier.
   */
  private object CatalogAndIdentifier {
    def unapply(parts: Seq[String]): Some[(Option[CatalogPlugin], Seq[String])] = parts match {
      case Seq(_) =>
        Some((None, parts))
      case Seq(catalogName, tail @ _*) =>
        try {
          Some((Some(catalogManager.catalog(catalogName)), tail))
        } catch {
          case _: CatalogNotFoundException =>
            Some((None, parts))
        }
    }
  }

  type CatalogObjectIdentifier = (Option[CatalogPlugin], Identifier)

  /**
   * Extract catalog and identifier from a multi-part identifier with the default catalog if needed.
   */
  object CatalogObjectIdentifier {
    def unapply(parts: Seq[String]): Some[CatalogObjectIdentifier] = parts match {
      case CatalogAndIdentifier(maybeCatalog, nameParts) =>
        Some((
            maybeCatalog.orElse(defaultCatalog),
            Identifier.of(nameParts.init.toArray, nameParts.last)
        ))
    }
  }

  type CatalogNamespace = (Option[CatalogPlugin], Seq[String])

  /**
   * Extract catalog and namespace from a multi-part identifier with the default catalog if needed.
   * Catalog name takes precedence over namespaces.
   */
  object CatalogNamespace {
    def unapply(parts: Seq[String]): Some[CatalogNamespace] = parts match {
      case Seq(catalogName, tail @ _*) =>
        try {
          Some((Some(catalogManager.catalog(catalogName)), tail))
        } catch {
          case _: CatalogNotFoundException =>
            Some((defaultCatalog, parts))
        }
    }
  }

  /**
   * Extract legacy table identifier from a multi-part identifier.
   *
   * For legacy support only. Please use [[CatalogObjectIdentifier]] instead on DSv2 code paths.
   */
  object AsTableIdentifier {
    def unapply(parts: Seq[String]): Option[TableIdentifier] = parts match {
      case CatalogAndIdentifier(None, names) if defaultCatalog.isEmpty =>
        names match {
          case Seq(name) =>
            Some(TableIdentifier(name))
          case Seq(database, name) =>
            Some(TableIdentifier(name, Some(database)))
          case _ =>
            None
        }
      case _ =>
        None
    }
  }

  /**
   * For temp views, extract a table identifier from a multi-part identifier if it has no catalog.
   */
  object AsTemporaryViewIdentifier {
    def unapply(parts: Seq[String]): Option[TableIdentifier] = parts match {
      case CatalogAndIdentifier(None, Seq(table)) =>
        Some(TableIdentifier(table))
      case CatalogAndIdentifier(None, Seq(database, table)) =>
        Some(TableIdentifier(table, Some(database)))
      case _ =>
        None
    }
  }

  type CatalogAndRestNameParts = (Either[CatalogPlugin, CatalogPlugin], Seq[String])

  /**
   * Extract catalog and the rest name parts from a multi-part identifier. It returns a
   * `Left[CatalogPlugin]` if the catalog is resolved from the identifier or it's the default
   * catalog. Returns a `Right[CatalogPlugin]` if it's the session catalog.
   */
  object CatalogAndRestNameParts {
    def unapply(nameParts: Seq[String]): Some[CatalogAndRestNameParts] = {
      assert(nameParts.nonEmpty)
      try {
        Some((Left(catalogManager.catalog(nameParts.head)), nameParts.tail))
      } catch {
        case _: CatalogNotFoundException =>
          defaultCatalog.map { catalog =>
            Some((Left(catalog), nameParts))
          }.getOrElse {
            Some((Right(sessionCatalog), nameParts))
          }
      }
    }
  }

  /**
   * Extract catalog and table with its name from a multi-part identifier. If the table can't be
   * found, return None.
   */
  object CatalogAndTable {
    import CatalogV2Implicits._
    import CatalogV2Util._

    def unapply(nameParts: Seq[String]): Option[(TableCatalog, Seq[String], Table)] = {
      assert(nameParts.nonEmpty)
      val (catalog, tableName) = nameParts match {
        case CatalogAndRestNameParts(Left(c), restNameParts) =>
          c.asTableCatalog -> restNameParts
        case CatalogAndRestNameParts(Right(c), restNameParts) =>
          c.asTableCatalog -> restNameParts
      }
      loadTable(catalog, tableName.asIdentifier).map { table =>
        (catalog, tableName, table)
      }
    }
  }
}
