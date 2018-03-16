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

package com.ibm.cds.spark.samples

import scala.BigDecimal
import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.immutable.Seq.canBuildFrom
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Map
import scala.reflect.ClassTag
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.spark.HashPartitioner
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.streaming.Duration
import org.apache.spark.streaming.Seconds
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.DStream.toPairDStreamFunctions
import org.http4s.client.blaze.PooledHttp1Client
import com.google.common.base.CharMatcher
import com.ibm.cds.spark.samples.config.MessageHubConfig
import com.ibm.cds.spark.samples.dstream.KafkaStreaming.KafkaStreamingContextAdapter
import twitter4j.Status
import org.apache.spark.streaming.scheduler.StreamingListenerBatchStarted
import org.apache.spark.streaming.scheduler.StreamingListenerBatchCompleted
import com.ibm.cds.spark.samples.config.DemoConfig
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStopped
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverError
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStarted
import org.apache.spark.broadcast.Broadcast
// import org.apache.spark.Logging
import org.apache.log4j.Logger

import java.util.Arrays

/**
 * @author dtaieb
 * Twitter+Watson sample app with MessageHub/Kafka
 */
object MessageHubStreamingTwitter {
  val log = Logger.getLogger(getClass.getName)
  
  var ssc: StreamingContext = null
  val reuseCheckpoint = false;
  
  val queue = new scala.collection.mutable.Queue[(String, String)] 
  
  final val KAFKA_TOPIC_TOP_HASHTAGS = "topHashTags"
  final val KAFKA_TOPIC_TONE_SCORES = "topHashTags.toneScores"
  final val KAFKA_TOPIC_TOTAL_TWEETS_PROCESSED = "total_tweets"
  
  //Logger.getLogger("org.apache.kafka").setLevel(Level.ALL)
  //Logger.getLogger("kafka").setLevel(Level.ALL)
  // Logger.getLogger("org.apache.spark").setLevel(Level.WARN)

  def main(args: Array[String]): Unit = {
    println("Printing arguments: ");
    args.foreach {  println }
    
    if(args.length>0 && System.getProperty("DEMO_CONFIG_PATH") == null ){
      //On Spark Service, input files are passed as parameters, if available, we assume first parameter is config file
      System.setProperty("DEMO_CONFIG_PATH", args(0))
    }
    val conf = new SparkConf().setAppName("Spark Streaming Twitter + Watson with MessageHub/Kafka Demo")
    val sc = new SparkContext(conf)
    startTwitterStreaming(sc);
    
    if(ssc!=null){
      //When running as stand alone app, we call awaitTermination to make sure the JVM doesn't exit prematurely due to the fact 
      //that all non-daemon threads have terminated. Note: Don't call awaitTermination directly from startTwitterStreaming as it can be run 
      //From Notebook
      ssc.awaitTermination()
    }
  }
  
  //Hold configuration key/value pairs
  lazy val kafkaProps = new MessageHubConfig
  
  //Wrapper api for Notebook access
  def getConfig():DemoConfig={
    kafkaProps
  }
  
  def startTwitterStreaming( sc: SparkContext, stopAfter: Duration = Seconds(0) ){
    if ( ssc != null ){
      println("Twitter Stream already running");
      return;
    }
    
    kafkaProps.setValueSerializer[StringSerializer];
    
    if ( !kafkaProps.validateConfiguration("twitter4j.oauth") ){
      return;
    }
    
    //Set the hadoop configuration if needed
    val checkpointDir = kafkaProps.getConfig( MessageHubConfig.CHECKPOINT_DIR_KEY );
    if ( checkpointDir.startsWith("swift") ){
      println("Setting hadoop configuration for swift container")
      kafkaProps.set_hadoop_config(sc)
    }
    
    //Make sure the topics are already created
    kafkaProps.createTopicsIfNecessary( KAFKA_TOPIC_TONE_SCORES, KAFKA_TOPIC_TOP_HASHTAGS, KAFKA_TOPIC_TOTAL_TWEETS_PROCESSED )
    
    val kafkaProducer = new org.apache.kafka.clients.producer.KafkaProducer[String, String]( kafkaProps.toImmutableMap ); 
    
    if ( !reuseCheckpoint ){
      createStreamingContextAndRunAnalytics(sc);
    }else{
      ssc = StreamingContext.getOrCreate( 
          kafkaProps.getConfig( MessageHubConfig.CHECKPOINT_DIR_KEY ), 
          () => {
            createStreamingContextAndRunAnalytics(sc);
          },
          sc.hadoopConfiguration,
          true
      );
    }
    
    ssc.addStreamingListener( new StreamingListener() )
    
    new Thread( new Runnable() {
      def run(){
        while(ssc!=null){          
          while(!queue.isEmpty ){
            try{
                var task:(String,String) = null;
                queue.synchronized{
                  task = queue.dequeue();
                }
                if ( task != null ){
                  val producerRecord = new ProducerRecord[String,String](task._1, "tweet", task._2 )
                  val metadata = kafkaProducer.send( producerRecord ).get;
                  // logInfo("Sent record " + metadata.offset() + " Topic " + task._1)
                  log.info("Sent record " + metadata.offset() + " Topic " + task._1)
                }
            }catch{
                // case e:Throwable => logError(e.getMessage, e)
                case e:Throwable => log.error(e.getMessage, e)
            }
          }
          queue.synchronized{
            queue.wait();
          }
        }
      }
    },"Message Hub producer").start
   
    ssc.start
    
    println("Twitter stream started");
    println("Tweets are collected real-time and analyzed")
    println("To stop the streaming and start interacting with the data use: StreamingTwitter.stopTwitterStreaming")
    
    if ( !stopAfter.isZero ){
      //Automatically stop it after 10s
      new Thread( new Runnable {
        def run(){
          Thread.sleep( stopAfter.milliseconds )
          stopTwitterStreaming
        }
      }).start
    }
  }
  
  def createStreamingContextAndRunAnalytics(sc:SparkContext):StreamingContext={
    //Broadcast the config to each worker node
    val broadcastVar = sc.broadcast( kafkaProps.toImmutableMap )
    ssc = new StreamingContext( sc, Seconds(5) )
    ssc.checkpoint(kafkaProps.getConfig( MessageHubConfig.CHECKPOINT_DIR_KEY ));
    val stream = ssc.createKafkaStream[String, StatusAdapter,StringDeserializer, StatusDeserializer](
        kafkaProps,
        List(kafkaProps.getConfig(MessageHubConfig.KAFKA_TOPIC_TWEETS ))
    );
    runAnalytics(sc, broadcastVar, stream)
    ssc;
  }
  
  def runAnalytics(sc:SparkContext, broadcastVar: Broadcast[scala.collection.immutable.Map[String,String]], stream:DStream[(String,StatusAdapter)]){
    val keys = broadcastVar.value.get("tweets.key").get.split(",");
    val tweets = stream.map( t => t._2)
      .filter { status => 
        status.userLang.startsWith("en") && CharMatcher.ASCII.matchesAllOf(status.text) && ( keys.isEmpty || keys.exists{status.text.contains(_)})
      }
    
    val rowTweets = tweets.map(status=> {
      lazy val client = PooledHttp1Client()
      val sentiment = ToneAnalyzer.computeSentiment( client, status, broadcastVar )        
      var scoreMap : Map[String, Double] = Map()
      if ( sentiment != null ){
        // for( toneCategory <- Option(sentiment.tone_categories).getOrElse( Seq() )){
          for ( tone <- Option( sentiment.tones ).getOrElse( Seq() ) ){
            scoreMap.put( tone.tone_id, (BigDecimal(tone.score).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble) * 100.0 )
          }
        //}
      }
      
      EnrichedTweet( 
          status.userName, 
          status.userId,
          status.createdAt, 
          status.userLang, 
          status.text, 
          status.long,
          status.lat,
          scoreMap
      )
    })
   
   val delimTagTone = "-%!"
   val delimToneScore = ":%@"
   val statsStream = rowTweets.map { eTweet => ("total_tweets", 1L) }
      .reduceByKey( _+_ )
      .updateStateByKey( (a:Seq[Long], b:Option[Long] ) => {
        var runningCount=b.getOrElse(0L)
        a.foreach { v => runningCount=runningCount+v }
        Some(runningCount)
      })
   statsStream.foreachRDD( rdd =>{
     queue.synchronized{
       queue+=((KAFKA_TOPIC_TOTAL_TWEETS_PROCESSED, TweetsMetricJsonSerializer.serialize(rdd.collect())))
		   try{
			   queue.notify
		   }catch{
		     // case e:Throwable=>logError(e.getMessage, e)
		     case e:Throwable=>log.error(e.getMessage, e)
		   }
     }
   })
   
   val metricsStream = rowTweets.flatMap { eTweet => {
     val retList = ListBuffer[String]()
     for ( tag <- eTweet.text.split("\\s+") ){
       if ( tag.startsWith( "#") && tag.length > 1 ){
           for ( tone <- Option( eTweet.sentimentScores.keys ).getOrElse( Seq() ) ){
               retList += (tag + delimTagTone + tone + delimToneScore + eTweet.sentimentScores.getOrElse( tone, 0.0))
           }
       }
     }
     retList.toList
   }}
   .map { fullTag => {
       val split = fullTag.split(delimToneScore);
       (split(0), split(1).toFloat) 
   }}
   .combineByKey( 
       (x:Float) => (x,1), 
       (x:(Float,Int), y:Float) => (x._1 + y, x._2+1), 
       (x:(Float,Int),y:(Float,Int)) => (x._1 + y._1, x._2 + y._2),
       new HashPartitioner(sc.defaultParallelism)
   )
   .map[(String,(Long/*count*/, List[(String, Double)]))]{ t => {
     val key = t._1;
     val ab = t._2;
     val split = key.split(delimTagTone)
     (split(0), (ab._2, List((split(1), BigDecimal(ab._1/ab._2).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble ))))
   }}
   .reduceByKey( (t,u) => (t._1+u._1, (t._2 ::: u._2).sortWith( (l,r) => l._1.compareTo( r._1 ) < 0 )))
   .mapValues( (item:(Long, List[(String,Double)])) => {
     val unzip = item._2.unzip
     (item._1/(item._2.size), unzip._1, unzip._2)
   })
   .updateStateByKey( (a:scala.collection.Seq[(Long, List[String], List[Double])], b: Option[(Long, List[String], List[Double])]) => {
     val safeB = b.getOrElse( (0L, List(), List() ) )
     var listTones = safeB._2
     var listScores = safeB._3
     var count = safeB._1
     for( item <- a ){
       count += item._1
       listScores = listScores.zipAll( item._3, 0.0, 0.0).map{ case(a,b)=>(a+b)/2 }.toList
       listTones = item._2
     }
     
     Some( (count, listTones, listScores) )
   })
   
   metricsStream.print
   
   metricsStream.foreachRDD( rdd =>{
     val topHashTags = rdd.sortBy( f => f._2._1, false ).take(5)
     if ( !topHashTags.isEmpty){
       queue.synchronized{
         queue += ((KAFKA_TOPIC_TOP_HASHTAGS, TweetsMetricJsonSerializer.serialize(topHashTags.map( f => (f._1, f._2._1 )))))
         queue += ((KAFKA_TOPIC_TONE_SCORES, ToneScoreJsonSerializer.serialize(topHashTags)))
         try{
           queue.notify
         }catch{
           // case e:Throwable=>logError(e.getMessage, e)
           case e:Throwable=>log.error(e.getMessage, e)
         }
       }
     }
   })
  }
  
  def stopTwitterStreaming(){
    if ( ssc == null){
      println("No Twitter stream to stop");
      return;
    }
    
    println("Stopping Twitter stream. Please wait this may take a while")
    ssc.stop(stopSparkContext = false, stopGracefully = true)
    ssc = null
    println("Twitter stream stopped");
  }
}

object TweetsMetricJsonSerializer {
  val log = Logger.getLogger(getClass.getName)
  def serialize(value: Seq[(String,Long)] ): String = {   
    val sb = new StringBuilder("[")
    var comma = ""
    value.foreach( item => {
      sb.append( comma + "[\"" + item._1.replaceAll("\"", "") + "\"," + item._2 + "]")
      comma=","
    })
    sb.append("]")
    // logInfo("Serialized json: " + sb)
    log.info("Serialized json: " + sb)
    sb.toString()
  }
}

object ToneScoreJsonSerializer {
  val log = Logger.getLogger(getClass.getName)
  def serializeList[U:ClassTag]( label: String, value: List[U] ):String = {
    val sb = new StringBuilder("[\"" + label.replaceAll("\"", "") + "\"")
    value.foreach { item => {
      if ( item.isInstanceOf[String] ) {
        val s = ",\"" + item.toString().replaceAll("\"", "") + "\"";
        sb.append( s.replaceAll("\"\"", "\"") )
      }else if ( item.isInstanceOf[Double] ){
        sb.append("," + item )
      }
    }}
    sb.append("]")
    sb.toString
  }
  def serialize(value:Seq[(String, (Long, List[String], List[Double]))]):String={
    val sb = new StringBuilder("[")
    var comma = ""
    var appendToneData = true;
    value.foreach( item => {
      if ( appendToneData ){
        sb.append( comma + serializeList( "x", item._2._2 ) )
        appendToneData = false
        comma = ","
      }
      sb.append( comma + serializeList( item._1, item._2._3 ) )
      comma=","
    })
    sb.append("]")
    // logInfo("Serialized size: " + value.size + ". Tone json: " + sb)
    log.info("Serialized size: " + value.size + ". Tone json: " + sb)
    sb.toString()
  }
}
