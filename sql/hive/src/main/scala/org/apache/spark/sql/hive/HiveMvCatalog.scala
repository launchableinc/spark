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
package org.apache.spark.sql.hive

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan

class HiveMvCatalog extends MvCatalog {

  private var sparkSession: SparkSession = SparkSession.getActiveSession.get

  override def init(session: Any): Unit = {
    this.sparkSession = session.asInstanceOf[SparkSession]
  }

  override def getMaterializedViewForTable(db: String, tblName: String): CatalogCreationData = {
    sparkSession.sessionState.catalog.externalCatalog.getMaterializedViewForTable(db, tblName)
  }

  override def getMaterializedViewPlan(catalogTable: CatalogTable): Option[LogicalPlan] = {
    val viewTextOpt = catalogTable.viewOriginalText
    viewTextOpt.map {
      viewText =>
        sparkSession.sql(viewText).queryExecution.optimizedPlan
    }
  }
}
