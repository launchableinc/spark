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
import java.util

import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

// A simple version of `TableProvider` which doesn't support specified table schema/partitioning
// and treats table properties case-insensitively. This is private and only used in builtin sources.
private[sql] trait SimpleTableProvider extends TableProvider {

  protected def getTable(options: CaseInsensitiveStringMap): Table

  override def getTable(properties: util.Map[String, String]): Table = {
    getTable(new CaseInsensitiveStringMap(properties))
  }

  override def getTable(schema: StructType, properties: util.Map[String, String]): Table = {
    throw new UnsupportedOperationException(
      getClass().getSimpleName() + " does not support specified table schema")
  }

  override def getTable(
      schema: StructType,
      partitioning: Array[Transform],
      properties: util.Map[String, String]): Table = {
    throw new UnsupportedOperationException(
      getClass().getSimpleName() + " does not support specified table schema/partitioning")
  }
}
