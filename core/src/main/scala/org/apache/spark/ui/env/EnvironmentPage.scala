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

package org.apache.spark.ui.env

import javax.servlet.http.HttpServletRequest

import scala.collection.mutable
import scala.xml.Node

import org.apache.spark.SparkConf
import org.apache.spark.resource.{ExecutorResourceRequest, TaskResourceRequest}
import org.apache.spark.status.AppStatusStore
import org.apache.spark.ui._
import org.apache.spark.util.Utils

private[ui] class EnvironmentPage(
    parent: EnvironmentTab,
    conf: SparkConf,
    store: AppStatusStore) extends WebUIPage("") {

  def render(request: HttpServletRequest): Seq[Node] = {
    val appEnv = store.environmentInfo()
    val jvmInformation = Map(
      "Java Version" -> appEnv.runtime.javaVersion,
      "Java Home" -> appEnv.runtime.javaHome,
      "Scala Version" -> appEnv.runtime.scalaVersion)

    def constructExecutorRequestString(ereqs: Map[String, ExecutorResourceRequest]): String = {
      ereqs.map {
        case (_, ereq) =>
          val execStr = new mutable.StringBuilder()
          execStr ++= s"\t${ereq.resourceName}: [amount: ${ereq.amount}"
          if (ereq.discoveryScript.nonEmpty) execStr ++= s", discovery: ${ereq.discoveryScript}"
          if (ereq.vendor.nonEmpty) execStr ++= s", vendor: ${ereq.vendor}"
          execStr ++= "]"
          execStr.toString()
      }.mkString("\n")
    }

    def constructTaskRequestString(treqs: Map[String, TaskResourceRequest]): String = {
      treqs.map {
        case (_, ereq) => s"\t${ereq.resourceName}: [amount: ${ereq.amount}]"
      }.mkString("\n")
    }

    val resourceProfileInfo = store.resourceProfileInfo().map { rinfo =>
      val einfo = constructExecutorRequestString(rinfo.executorResources)
      val tinfo = constructTaskRequestString(rinfo.taskResources)
      val res = s"Executor Reqs:\n$einfo\nTask Reqs:\n$tinfo"
      (rinfo.id.toString, res)
    }.toMap

    val resourceProfileInformationTable = UIUtils.listingTable(resourceProfileHeader,
      jvmRowDataPre, resourceProfileInfo.toSeq.sorted, fixedWidth = true,
      headerClasses = headerClassesNoSortValues)

    val runtimeInformationTable = UIUtils.listingTable(
      propertyHeader, jvmRow, jvmInformation.toSeq.sorted, fixedWidth = true,
      headerClasses = headerClasses)
    val sparkPropertiesTable = UIUtils.listingTable(propertyHeader, propertyRow,
      Utils.redact(conf, appEnv.sparkProperties.sorted), fixedWidth = true,
      headerClasses = headerClasses)
    val hadoopPropertiesTable = UIUtils.listingTable(propertyHeader, propertyRow,
      Utils.redact(conf, appEnv.hadoopProperties.sorted), fixedWidth = true,
      headerClasses = headerClasses)
    val systemPropertiesTable = UIUtils.listingTable(propertyHeader, propertyRow,
      Utils.redact(conf, appEnv.systemProperties.sorted), fixedWidth = true,
      headerClasses = headerClasses)
    val classpathEntriesTable = UIUtils.listingTable(
      classPathHeader, classPathRow, appEnv.classpathEntries.sorted, fixedWidth = true,
      headerClasses = headerClasses)
    val content =
      <span>
        <span class="collapse-aggregated-execResourceProfileInformation collapse-table"
              onClick="collapseTable('collapse-aggregated-execResourceProfileInformation',
            'aggregated-execResourceProfileInformation')">
          <h4>
            <span class="collapse-table-arrow arrow-open"></span>
            <a>Resource Profiles</a>
          </h4>
        </span>
        <div class="aggregated-execResourceProfileInformation collapsible-table">
          {resourceProfileInformationTable}
        </div>
        <span class="collapse-aggregated-runtimeInformation collapse-table"
            onClick="collapseTable('collapse-aggregated-runtimeInformation',
            'aggregated-runtimeInformation')">
          <h4>
            <span class="collapse-table-arrow arrow-open"></span>
            <a>Runtime Information</a>
          </h4>
        </span>
        <div class="aggregated-runtimeInformation collapsible-table">
          {runtimeInformationTable}
        </div>
        <span class="collapse-aggregated-sparkProperties collapse-table"
            onClick="collapseTable('collapse-aggregated-sparkProperties',
            'aggregated-sparkProperties')">
          <h4>
            <span class="collapse-table-arrow arrow-open"></span>
            <a>Spark Properties</a>
          </h4>
        </span>
        <div class="aggregated-sparkProperties collapsible-table">
          {sparkPropertiesTable}
        </div>
        <span class="collapse-aggregated-hadoopProperties collapse-table"
              onClick="collapseTable('collapse-aggregated-hadoopProperties',
            'aggregated-hadoopProperties')">
          <h4>
            <span class="collapse-table-arrow arrow-closed"></span>
            <a>Hadoop Properties</a>
          </h4>
        </span>
        <div class="aggregated-hadoopProperties collapsible-table collapsed">
          {hadoopPropertiesTable}
        </div>
        <span class="collapse-aggregated-systemProperties collapse-table"
            onClick="collapseTable('collapse-aggregated-systemProperties',
            'aggregated-systemProperties')">
          <h4>
            <span class="collapse-table-arrow arrow-closed"></span>
            <a>System Properties</a>
          </h4>
        </span>
        <div class="aggregated-systemProperties collapsible-table collapsed">
          {systemPropertiesTable}
        </div>
        <span class="collapse-aggregated-classpathEntries collapse-table"
            onClick="collapseTable('collapse-aggregated-classpathEntries',
            'aggregated-classpathEntries')">
          <h4>
            <span class="collapse-table-arrow arrow-closed"></span>
            <a>Classpath Entries</a>
          </h4>
        </span>
        <div class="aggregated-classpathEntries collapsible-table collapsed">
          {classpathEntriesTable}
        </div>
      </span>

    UIUtils.headerSparkPage(request, "Environment", content, parent)
  }

  private def resourceProfileHeader = Seq("Resource Profile Id", "Resource Profile Contents")
  private def propertyHeader = Seq("Name", "Value")
  private def classPathHeader = Seq("Resource", "Source")
  private def headerClasses = Seq("sorttable_alpha", "sorttable_alpha")
  private def headerClassesNoSortValues = Seq("sorttable_numeric", "sorttable_nosort")

  private def jvmRowDataPre(kv: (String, String)) =
    <tr><td>{kv._1}</td><td><pre>{kv._2}</pre></td></tr>
  private def jvmRow(kv: (String, String)) = <tr><td>{kv._1}</td><td>{kv._2}</td></tr>
  private def propertyRow(kv: (String, String)) = <tr><td>{kv._1}</td><td>{kv._2}</td></tr>
  private def classPathRow(data: (String, String)) = <tr><td>{data._1}</td><td>{data._2}</td></tr>
}

private[ui] class EnvironmentTab(
    parent: SparkUI,
    store: AppStatusStore) extends SparkUITab(parent, "environment") {
  attachPage(new EnvironmentPage(this, parent.conf, store))
}
