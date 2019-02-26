package org.apache.spark.graph.api

import org.apache.spark.sql.DataFrame

trait CypherResult {
  def df: DataFrame

  def nodeDataFrames(varName: String): Seq[NodeFrame]

  def relationshipDataFrames(varName: String): Seq[RelationshipFrame]
}
