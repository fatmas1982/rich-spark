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

package org.apache.spark.streaming

import java.io.{IOException, ObjectInputStream, ObjectOutputStream}
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import org.apache.spark.internal.Logging
import org.apache.spark.streaming.dstream.{DStream, InputDStream, ReceiverInputDStream}
import org.apache.spark.streaming.event._
import org.apache.spark.streaming.scheduler.{Job, StreamingListenerEventSourceStarted}
import org.apache.spark.util.Utils

final private[streaming] class DStreamGraph extends Serializable with Logging {

  private val inputStreams = new ArrayBuffer[InputDStream[_]]()

  val eventSourceToBoundStreams =
    new ConcurrentHashMap[EventSource, mutable.LinkedHashSet[DStream[_]]]

  var rememberDuration: Duration = null
  var checkpointInProgress = false

  var zeroTime: Time = null
  var startTime: Time = null
  var batchDuration: Duration = null
  var defaultTimer: Option[TimerEventSource] = None
  private val defaultTimerStreams = new ArrayBuffer[DStream[_]]()
  private val defaultTimerListeners = new ArrayBuffer[EventListener]()

  def eventSources = {
    eventSourceToBoundStreams.keySet().toSet
  }

  def start(time: Time, ssc: StreamingContext) {
    this.synchronized {
      require(zeroTime == null, "DStream graph computation already started")
      zeroTime = time
      startTime = time

      // initialize the default timer
      val timer = ssc.timer(time + batchDuration, Time(Long.MaxValue),
        batchDuration, "DefaultTimer")
      defaultTimer = Some(timer)
      // Output streams that are not bound to any other
      // event source should be bound to the default timer
      defaultTimerStreams
        .filter(_.boundEventSources.isEmpty)
        .foreach(_.bind(timer))
      defaultTimerStreams.clear()
      defaultTimerListeners.foreach(timer.addListener)
      defaultTimerListeners.clear()

      val outputStreams = getOutputStreams()
      outputStreams.foreach(_.initialize(zeroTime))
      outputStreams.foreach(_.remember(rememberDuration))
      outputStreams.foreach(_.validateAtStart)
      inputStreams.par.foreach(_.start())
      eventSources.par.foreach { eventSource =>
        eventSource.start()
        ssc.scheduler.listenerBus.post(StreamingListenerEventSourceStarted(eventSource))
      }
    }
  }

  def restart(time: Time, ssc: StreamingContext) {
    this.synchronized {
      startTime = time
      eventSources.par.foreach { eventSource =>
        eventSource.restart()
        ssc.scheduler.listenerBus.post(StreamingListenerEventSourceStarted(eventSource))
      }
    }
  }

  def stop(ssc: StreamingContext) {
    this.synchronized {
      eventSources.par.foreach { eventSource =>
        eventSource.stop()
        ssc.scheduler.listenerBus.post(StreamingListenerEventSourceStarted(eventSource))
      }
      inputStreams.par.foreach(_.stop())
    }
  }

  def setContext(ssc: StreamingContext) {
    this.synchronized {
      eventSources.foreach(_.setContext(ssc))
      getOutputStreams().foreach(_.setContext(ssc))
    }
  }

  def setBatchDuration(duration: Duration) {
    this.synchronized {
      require(batchDuration == null,
        s"Batch duration already set as $batchDuration. Cannot set it again.")
      batchDuration = duration
    }
  }

  def remember(duration: Duration) {
    this.synchronized {
      require(rememberDuration == null,
        s"Remember duration already set as $rememberDuration. Cannot set it again.")
      rememberDuration = duration
    }
  }

  def addInputStream(inputStream: InputDStream[_]) {
    this.synchronized {
      inputStream.setGraph(this)
      inputStreams += inputStream
    }
  }

  def addEventListener(listener: EventListener) {
    eventSources.foreach(_.addListener(listener))
    if (defaultTimer.isEmpty) {
      this.synchronized {
        defaultTimerListeners += listener
      }
    }
  }

  def removeEventListener[T <: EventListener : ClassTag]() {
    eventSources.foreach(_.removeListeners[T]())
    if (defaultTimer.isEmpty) {
      this.synchronized {
        val c = implicitly[ClassTag[T]].runtimeClass
        val listenersToDrop = defaultTimerListeners.filter(l => c.isAssignableFrom(l.getClass))
        defaultTimerListeners --= listenersToDrop
      }
    }
  }

  def bind(stream: DStream[_], eventSource: EventSource) {
    val boundStreams = Option(eventSourceToBoundStreams.get(eventSource))
      .getOrElse {
        val emptySet = mutable.LinkedHashSet.empty[DStream[_]]
        eventSourceToBoundStreams.put(eventSource, emptySet)
        emptySet
      }
    boundStreams += stream
    if (stream.graph == null) {
      stream.setGraph(this)
    }
  }

  def bind(stream: DStream[_]) {
    defaultTimer match {
      case Some(timer) => bind(stream, timer)
      case _ => this.synchronized {
        defaultTimerStreams += stream
        if (stream.graph == null) {
          stream.setGraph(this)
        }
      }
    }
  }

  def getInputStreams(): Array[InputDStream[_]] = this.synchronized { inputStreams.toArray }

  def getOutputStreams(): Array[DStream[_]] = {
    (eventSourceToBoundStreams.values.flatten.toArray ++ defaultTimerStreams).distinct
  }

  def getReceiverInputStreams(): Array[ReceiverInputDStream[_]] = this.synchronized {
    inputStreams.filter(_.isInstanceOf[ReceiverInputDStream[_]])
      .map(_.asInstanceOf[ReceiverInputDStream[_]])
      .toArray
  }

  def getBoundStreams(eventSource: EventSource): Seq[DStream[_]] = {
    eventSourceToBoundStreams.getOrDefault(eventSource, mutable.LinkedHashSet.empty).toSeq
  }

  def getInputStreamName(streamId: Int): Option[String] = synchronized {
    inputStreams.find(_.id == streamId).map(_.name)
  }

  def deleteEvents(events: Seq[Event]) {
    this.synchronized {
      events.foreach(e =>
        getBoundStreams(e.eventSource)
          .foreach(_.deleteEvent(e)))
    }
  }

  def generateJobs(event: Event): Seq[Job] = {
    logDebug(s"Generating jobs for event $event")
    val jobs = this.synchronized {
      getBoundStreams(event.eventSource)
        .flatMap { stream =>
          val jobOption = stream.generateJob(event)
          jobOption.foreach(_.setCallSite(stream.creationSite))
          jobOption
        }
    }
    logDebug(s"Generated ${jobs.length} jobs for event $event")
    jobs
  }

  def clearMetadata(event: Event) {
    logDebug("Clearing metadata for event " + event)
    this.synchronized {
      getBoundStreams(event.eventSource).foreach(_.clearMetadata(event))
    }
    logDebug("Cleared old metadata for event " + event)
  }

  def updateCheckpointData(event: Event) {
    logInfo("Updating checkpoint data for event " + event)
    this.synchronized {
      getBoundStreams(event.eventSource).foreach(_.updateCheckpointData(event))
    }
    logInfo("Updated checkpoint data for event " + event)
  }

  def clearCheckpointData(event: Event) {
    logInfo("Clearing checkpoint data for event " + event)
    this.synchronized {
      getBoundStreams(event.eventSource).foreach(_.clearCheckpointData(event))
    }
    logInfo("Cleared checkpoint data for event " + event)
  }

  def restoreCheckpointData() {
    logInfo("Restoring checkpoint data")
    this.synchronized {
      getOutputStreams().foreach(_.restoreCheckpointData())
    }
    logInfo("Restored checkpoint data")
  }

  def validate() {
    this.synchronized {
      require(batchDuration != null, "Batch duration has not been set")
      // assert(batchDuration >= Milliseconds(100), "Batch duration of " + batchDuration +
      // " is very low")
      require((getOutputStreams() ++ defaultTimerStreams).nonEmpty,
        "No output operations registered, so nothing to execute")
    }
  }

  /**
   * Get the maximum remember duration across all the input streams. This is a conservative but
   * safe remember duration which can be used to perform cleanup operations.
   */
  def getMaxInputStreamRememberDuration(): Duration = {
    // If an InputDStream is not used, its `rememberDuration` will be null and we can ignore them
    inputStreams.map(_.rememberDuration).filter(_ != null).maxBy(_.milliseconds)
  }

  /**
   * Get the maximum remember duration across all the input streams considering events up to the
   * given event and ignoring events past it. This is a conservative but safe remember duration
   * which can be used to perform cleanup operations.
   */
  def getMaxInputStreamRememberDuration(event: Event): Duration = {
    // If an InputDStream is not used, its `rememberDuration` will be null and we can ignore them
    inputStreams.map(_.rememberDuration(event)).filter(_ != null).maxBy(_.milliseconds)
  }

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream): Unit = Utils.tryOrIOException {
    logDebug("DStreamGraph.writeObject used")
    this.synchronized {
      checkpointInProgress = true
      logDebug("Enabled checkpoint mode")
      oos.defaultWriteObject()
      checkpointInProgress = false
      logDebug("Disabled checkpoint mode")
    }
  }

  @throws(classOf[IOException])
  private def readObject(ois: ObjectInputStream): Unit = Utils.tryOrIOException {
    logDebug("DStreamGraph.readObject used")
    this.synchronized {
      checkpointInProgress = true
      ois.defaultReadObject()
      checkpointInProgress = false
    }
  }
}

