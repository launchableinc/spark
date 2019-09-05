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

package org.apache.spark.sql.hive.client

import java.security.PrivilegedExceptionAction

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.security.UserGroupInformation
import org.scalatest.{BeforeAndAfterAll, PrivateMethodTester}

import org.apache.spark.util.Utils

class HiveClientSuite(version: String)
    extends HiveVersionSuite(version) with BeforeAndAfterAll {

  test("username of HiveClient - no UGI") {
    // Assuming we're not faking System username
    assert(System.getProperty("user.name") === getUserNameFromHiveClient)
  }

  test("username of HiveClient - UGI") {
    val ugi = UserGroupInformation.createUserForTesting(
      "fakeprincipal@EXAMPLE.COM", Array.empty)
    ugi.doAs(new PrivilegedExceptionAction[Unit]() {
      override def run(): Unit = {
        assert(ugi.getUserName === getUserNameFromHiveClient)
      }
    })
  }

  test("username of HiveClient - Proxy user as HADOOP_USER_NAME") {
    val ugi = UserGroupInformation.createUserForTesting(
      "fakeprincipal@EXAMPLE.COM", Array.empty)
    val proxyUgi = UserGroupInformation.createProxyUserForTesting(
      "proxyprincipal@EXAMPLE.COM", ugi, Array.empty)
    proxyUgi.doAs(new PrivilegedExceptionAction[Unit]() {
      override def run(): Unit = {
        assert(proxyUgi.getUserName === getUserNameFromHiveClient)
      }
    })
  }

  private def getUserNameFromHiveClient: String = {
    val hadoopConf = new Configuration()
    hadoopConf.set("hive.metastore.warehouse.dir", Utils.createTempDir().toURI().toString())
    val client = buildClient(hadoopConf)
    client.userName
  }
}
