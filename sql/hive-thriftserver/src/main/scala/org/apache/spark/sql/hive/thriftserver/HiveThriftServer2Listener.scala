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

package org.apache.spark.sql.hive.thriftserver

import java.util.concurrent.ConcurrentHashMap

import org.apache.hive.service.server.HiveServer2
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.scheduler.{SparkListener, SparkListenerApplicationEnd, SparkListenerEvent, SparkListenerJobStart}
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.hive.thriftserver.HiveThriftServer2.ExecutionState
import org.apache.spark.sql.hive.thriftserver.ui.{ExecutionInfo, SessionInfo}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.status.{ElementTrackingStore, KVUtils, LiveEntity}

/**
 * An inner sparkListener called in sc.stop to clean up the HiveThriftServer2
 */
private[thriftserver] class HiveThriftServer2Listener(
  kvstore: ElementTrackingStore,
  server: Option[HiveServer2],
  sqlContext: Option[SQLContext],
  sc: Option[SparkContext],
  sparkConf: Option[SparkConf] = None,
  live: Boolean = true) extends SparkListener {

  private val sessionList = new ConcurrentHashMap[String, LiveSessionData]()
  private val executionList = new ConcurrentHashMap[String, LiveExecutionData]()

  private val (retainedStatements: Int, retainedSessions: Int) = {
    if (live) {
      val conf = sqlContext.get.conf
      (conf.getConf(SQLConf.THRIFTSERVER_UI_STATEMENT_LIMIT),
        conf.getConf(SQLConf.THRIFTSERVER_UI_SESSION_LIMIT))
    } else {
      val conf = sparkConf.get
      (conf.get(SQLConf.THRIFTSERVER_UI_STATEMENT_LIMIT),
        conf.get(SQLConf.THRIFTSERVER_UI_SESSION_LIMIT))
    }
  }

  kvstore.addTrigger(classOf[SessionInfo], retainedSessions) { count =>
    cleanupSession(count)
  }

  kvstore.addTrigger(classOf[ExecutionInfo], retainedStatements) { count =>
    cleanupExecutions(count)
  }

  kvstore.onFlush {
    if (!live) {
      val now = System.nanoTime()
      flush(update(_, now))
      executionList.keys().asScala.foreach(
        key => executionList.remove(key)
      )
      sessionList.keys().asScala.foreach(
        key => sessionList.remove(key)
      )
    }
  }

  def postLiveListenerBus(event: SparkListenerEvent): Unit = {
    if (live) {
      sc.foreach(_.listenerBus.post(event))
    }
  }

  override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd): Unit = {
    if (live) {
      server.foreach(_.stop())
    }
  }

  override def onJobStart(jobStart: SparkListenerJobStart): Unit = {
    if (jobStart.properties != null) {
      val groupId = jobStart.properties.getProperty(SparkContext.SPARK_JOB_GROUP_ID)
      if (groupId != null) {
        executionList.values().asScala.filter(_.groupId == groupId).foreach(
          exec => {
            exec.jobId += jobStart.jobId.toString
            exec.groupId = groupId
            updateLiveStore(exec)
          }
        )
      }
    }
  }

  override def onOtherEvent(event: SparkListenerEvent): Unit = {
    event match {
      case e: SparkListenerSessionCreated => processEventSessionCreated(e)
      case e: SparkListenerSessionClosed => processEventSessionClosed(e)
      case e: SparkListenerStatementStart => processEventStatementStart(e)
      case e: SparkListenerStatementParsed => processEventStatementParsed(e)
      case e: SparkListenerStatementCanceled => processEventStatementCanceled(e)
      case e: SparkListenerStatementError => processEventStatementError(e)
      case e: SparkListenerStatementFinish => processEventStatementFinish(e)
      case e: SparkListenerOperationClosed => processEventOperationClosed(e)
      case _ => // Ignore
    }
  }

  def processEventSessionCreated(e: SparkListenerSessionCreated): Unit = {
    val session = getOrCreateSession(e.sessionId, e.startTime, e.ip, e.userName)
    sessionList.put(e.sessionId, session)
    updateLiveStore(session)
  }

  def processEventSessionClosed(e: SparkListenerSessionClosed): Unit = {
    val session = sessionList.get(e.sessionId)
    session.finishTimestamp = e.finishTime
    updateLiveStore(session)
    if (live) {
      sessionList.remove(e.sessionId)
    }
  }

  def processEventStatementStart(e: SparkListenerStatementStart): Unit = {
    val info = getOrCreateExecution(
      e.id,
      e.statement,
      e.sessionId,
      e.startTime,
      e.userName)

    info.state = ExecutionState.STARTED
    executionList.put(e.id, info)
    sessionList.get(e.sessionId).totalExecution += 1
    executionList.get(e.id).groupId = e.groupId
    updateLiveStore(executionList.get(e.id))
    updateLiveStore(sessionList.get(e.sessionId))
  }

  def processEventStatementParsed(e: SparkListenerStatementParsed): Unit = {
    executionList.get(e.id).executePlan = e.executionPlan
    executionList.get(e.id).state = ExecutionState.COMPILED
    updateLiveStore(executionList.get(e.id))
  }

  def processEventStatementCanceled(e: SparkListenerStatementCanceled): Unit = {
    executionList.get(e.id).finishTimestamp = e.finishTime
    executionList.get(e.id).state = ExecutionState.CANCELED
    updateLiveStore(executionList.get(e.id))
  }

  def processEventStatementError(e: SparkListenerStatementError): Unit = {
    executionList.get(e.id).finishTimestamp = e.finishTime
    executionList.get(e.id).detail = e.errorMsg
    executionList.get(e.id).state = ExecutionState.FAILED
    updateLiveStore(executionList.get(e.id))
  }

  def processEventStatementFinish(e: SparkListenerStatementFinish): Unit = {
    executionList.get(e.id).finishTimestamp = e.finishTime
    executionList.get(e.id).state = ExecutionState.FINISHED
    updateLiveStore(executionList.get(e.id))
  }

  def processEventOperationClosed(e: SparkListenerOperationClosed): Unit = {
    executionList.get(e.id).closeTimestamp = e.closeTime
    executionList.get(e.id).state = ExecutionState.CLOSED
    updateLiveStore(executionList.get(e.id))
    if (live) {
      executionList.remove(e.id)
    }
  }


  def onSessionCreated(ip: String, sessionId: String, userName: String = "UNKNOWN"): Unit = {
    postLiveListenerBus(SparkListenerSessionCreated(ip, sessionId,
      userName, System.currentTimeMillis()))
  }

  def onSessionClosed(sessionId: String): Unit = {
    postLiveListenerBus(SparkListenerSessionClosed(sessionId, System.currentTimeMillis()))
  }

  def onStatementStart(
    id: String,
    sessionId: String,
    statement: String,
    groupId: String,
    userName: String = "UNKNOWN"): Unit = {
    postLiveListenerBus(SparkListenerStatementStart(id, sessionId, statement, groupId,
      System.currentTimeMillis(), userName))
  }

  def onStatementParsed(id: String, executionPlan: String): Unit = {
    postLiveListenerBus(SparkListenerStatementParsed(id, executionPlan))
  }

  def onStatementCanceled(id: String): Unit = {
    postLiveListenerBus(SparkListenerStatementCanceled(id, System.currentTimeMillis()))
  }

  def onStatementError(id: String, errorMsg: String, errorTrace: String): Unit = {
    postLiveListenerBus(SparkListenerStatementError(id, errorMsg, errorTrace,
      System.currentTimeMillis()))
  }

  def onStatementFinish(id: String): Unit = {
    postLiveListenerBus(SparkListenerStatementFinish(id, System.currentTimeMillis()))

  }

  def onOperationClosed(id: String): Unit = {
    postLiveListenerBus(SparkListenerOperationClosed(id, System.currentTimeMillis()))
  }


  /** Go through all `LiveEntity`s and use `entityFlushFunc(entity)` to flush them. */
  private def flush(entityFlushFunc: LiveEntity => Unit): Unit = {
    sessionList.values.asScala.foreach(entityFlushFunc)
    executionList.values.asScala.foreach(entityFlushFunc)
  }

  private def update(entity: LiveEntity, now: Long): Unit = {
    entity.write(kvstore, now)
  }

  def updateLiveStore(session: LiveEntity): Unit = {
    if (live) {
      session.write(kvstore, System.nanoTime())
    }
  }

  private def getOrCreateSession(
     sessionId: String,
     startTime: Long,
     ip: String,
     username: String): LiveSessionData = {
    sessionList.computeIfAbsent(sessionId,
      (_: String) => new LiveSessionData(sessionId, startTime, ip, username))
  }

  private def getOrCreateExecution(
    execId: String, statement: String,
    sessionId: String, startTimestamp: Long,
    userName: String): LiveExecutionData = {
    executionList.computeIfAbsent(execId,
      (_: String) => new LiveExecutionData(execId, statement, sessionId, startTimestamp, userName))
  }

  private def cleanupExecutions(count: Long): Unit = {
    val countToDelete = calculateNumberToRemove(count, retainedStatements)
    if (countToDelete <= 0L) {
      return
    }
    val view = kvstore.view(classOf[ExecutionInfo]).index("execId").first(0L)
    val toDelete = KVUtils.viewToSeq(view, countToDelete.toInt) { j =>
      j.finishTimestamp != 0
    }
    toDelete.foreach { j => kvstore.delete(j.getClass(), j.execId) }
  }

  private def cleanupSession(count: Long): Unit = {
    val countToDelete = calculateNumberToRemove(count, retainedSessions)
    if (countToDelete <= 0L) {
      return
    }
    val view = kvstore.view(classOf[SessionInfo]).index("sessionId").first(0L)
    val toDelete = KVUtils.viewToSeq(view, countToDelete.toInt) { j =>
      j.finishTimestamp != 0L
    }
    toDelete.foreach { j => kvstore.delete(j.getClass(), j.sessionId) }
  }

  /**
   * Remove at least (retainedSize / 10) items to reduce friction. Because tracking may be done
   * asynchronously, this method may return 0 in case enough items have been deleted already.
   */
  private def calculateNumberToRemove(dataSize: Long, retainedSize: Long): Long = {
    if (dataSize > retainedSize) {
      math.max(retainedSize / 10L, dataSize - retainedSize)
    } else {
      0L
    }
  }
}

private[thriftserver] class LiveExecutionData(
    val execId: String,
    val statement: String,
    val sessionId: String,
    val startTimestamp: Long,
    val userName: String) extends LiveEntity {

    var finishTimestamp: Long = 0L
    var closeTimestamp: Long = 0L
    var executePlan: String = ""
    var detail: String = ""
    var state: ExecutionState.Value = ExecutionState.STARTED
    val jobId: ArrayBuffer[String] = ArrayBuffer[String]()
    var groupId: String = ""

  override protected def doUpdate(): Any = {
    new ExecutionInfo(
      execId,
      statement,
      sessionId,
      startTimestamp,
      userName,
      finishTimestamp,
      closeTimestamp,
      executePlan,
      detail,
      state,
      jobId,
      groupId)
  }
}


private[thriftserver] class LiveSessionData(
    val sessionId: String,
    val startTimeStamp: Long,
    val ip: String,
    val username: String) extends LiveEntity {

  var finishTimestamp: Long = 0L
  var totalExecution: Int = 0

  override protected def doUpdate(): Any = {
    new SessionInfo(
      sessionId,
      startTimeStamp,
      ip,
      username,
      finishTimestamp,
      totalExecution)
  }

}

private[thriftserver] case class SparkListenerSessionCreated(
    ip: String,
    sessionId: String,
    userName: String,
    startTime: Long) extends SparkListenerEvent

private[thriftserver] case class SparkListenerSessionClosed(
    sessionId: String, finishTime: Long) extends SparkListenerEvent

private[thriftserver] case class SparkListenerStatementStart(
    id: String,
    sessionId: String,
    statement: String,
    groupId: String,
    startTime: Long,
    userName: String = "UNKNOWN") extends SparkListenerEvent

private[thriftserver] case class SparkListenerStatementParsed(
    id: String,
    executionPlan: String) extends SparkListenerEvent

private[thriftserver] case class SparkListenerStatementCanceled(
    id: String, finishTime: Long) extends SparkListenerEvent

private[thriftserver] case class SparkListenerStatementError(
    id: String,
    errorMsg: String,
    errorTrace: String,
    finishTime: Long) extends SparkListenerEvent

private[thriftserver] case class SparkListenerStatementFinish(id: String, finishTime: Long)
  extends SparkListenerEvent

private[thriftserver] case class SparkListenerOperationClosed(id: String, closeTime: Long)
  extends SparkListenerEvent


