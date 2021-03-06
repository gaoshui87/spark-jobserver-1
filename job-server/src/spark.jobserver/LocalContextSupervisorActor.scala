package spark.jobserver

import akka.actor.{Terminated, Props, ActorRef, PoisonPill}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import ooyala.common.akka.InstrumentedActor
import spark.jobserver.JobManagerActor._
import spark.jobserver.io.JobDAO
import spark.jobserver.util.SparkJobUtils
import scala.collection.mutable
import scala.concurrent.Await
import scala.util.{Failure, Success, Try}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

/** Messages common to all ContextSupervisors */
object ContextSupervisor {
  // Messages/actions
  case object AddContextsFromConfig // Start up initial contexts
  case object ListContexts

  //列出一个context上面有多少条sql
  case object ListContextsContainSqlNum
  case object ListContextsInfo
  case class AddContext(name: String, contextConfig: Config)
  //要删除的旧的context
  case class DropOldContext(contextProcessId : String)
  case class StartAdHocContext(classPath: String, contextConfig: Config)
  case class GetContext(name: String) // returns JobManager, JobResultActor
  case class GetResultActor(name: String)  // returns JobResultActor
  case class StopContext(name: String)

  // Errors/Responses  返回当前创建的上下文占用的log路径
  case class ContextInitialized(temLogPathName:String)
  case class ContextInitError(t: Throwable)

  case class DropContextSuccs(temLogPathName:String)
  case class DropContextError(t: Throwable)

  case object ContextAlreadyExists
  case object NoSuchContext
  case object ContextStopped
}

/**
 * This class starts and stops JobManagers / Contexts in-process.
 * It is responsible for watching out for the death of contexts/JobManagers.
 *
 * == Auto context start configuration ==
 * Contexts can be configured to be created automatically at job server initialization.
 * Configuration example:
 * {{{
 *   spark {
 *     contexts {
 *       olap-demo {
 *         num-cpu-cores = 4            # Number of cores to allocate.  Required.
 *         memory-per-node = 1024m      # Executor memory per node, -Xmx style eg 512m, 1G, etc.
 *       }
 *     }
 *   }
 * }}}
 *
 * == Other configuration ==
 * {{{
 *   spark {
 *     jobserver {
 *       context-creation-timeout = 15 s
 *       yarn-context-creation-timeout = 40 s
 *     }
 *
 *     # Default settings for all context creation
 *     context-settings {
 *       spark.mesos.coarse = true
 *     }
 *   }
 * }}}
 */
class LocalContextSupervisorActor(dao: ActorRef) extends InstrumentedActor {
  import ContextSupervisor._
  import scala.collection.JavaConverters._
  import scala.concurrent.duration._

  val config = context.system.settings.config
  val defaultContextConfig = config.getConfig("spark.context-settings")
  val contextTimeout = SparkJobUtils.getContextTimeout(config)
  import context.dispatcher   // to get ExecutionContext for futures

  private val contexts = mutable.HashMap.empty[String, (ActorRef, ActorRef,String)]

  // This is for capturing results for ad-hoc jobs. Otherwise when ad-hoc job dies, resultActor also dies,
  // and there is no way to retrieve results.
  val globalResultActor = context.actorOf(Props[JobResultActor], "global-result-actor")
  private object Locker
//这里接收到信息进行处理
  def wrappedReceive: Receive = {
    case AddContextsFromConfig =>
      addContextsFromConfig(config)

    case ListContexts =>
      sender ! contexts.keys.toSeq


    case ListContextsContainSqlNum  =>
      //返回context里面包含多少条运行的Sql
      var contextsSql = mutable.HashMap.empty[String,( String ,Int ,Int)]
      val keynum = contexts.keys.size
      if(keynum == 0 ){
        sender ! contextsSql.values.toSeq

      } else {
        var othermsg: Int = 0;
        for (name <- contexts.keys) {
          val originator = sender
          val future = (contexts(name)._1 ? SparkContextSqlNum) (contextTimeout.seconds)
          future.collect {
            case JobManagerActor.ContextContainSqlNum(contextName, sqlNum, maxRunningJobs) =>
              Locker.synchronized {
                contextsSql(name) = (name, sqlNum, maxRunningJobs)
                if (keynum == (contextsSql.toSeq.length + othermsg)) {
                  logger.info("send all length in it  {}  ", contextsSql.toSeq.length)
                  originator ! contextsSql.values.toSeq
                }
              }
            case _ =>
              logger.warn("when send  SparkContextInfo ,it get other messag")
              othermsg = othermsg + 1
              if (keynum == (contextsSql.toSeq.length + othermsg)) {
                logger.info("send all length in it  {}  ", contextsSql.toSeq.length)
                originator ! contextsSql.values.toSeq
              }
          }
        }
      }

    case ListContextsInfo  =>
      //返回context里面包含所有的context的信息
      var contextsInfo = mutable.HashMap.empty[String,( String ,Int ,Int,String)]
      val keynum = contexts.keys.size
      if(keynum == 0 ){
        sender ! contextsInfo.values.toSeq

      } else {
        var othermsg: Int = 0;
        for (name <- contexts.keys) {
          val originator = sender
          val future = (contexts(name)._1 ? SparkContextInfo) (contextTimeout.seconds)
          future.collect {
            case JobManagerActor.ContextInfoMsg(contextName, sqlNum, maxRunningJobs, applicationId) =>
              Locker.synchronized {
                contextsInfo(name) = (name, sqlNum, maxRunningJobs, applicationId)
                if (keynum == (contextsInfo.toSeq.length + othermsg)) {
                  logger.info("send all length in it  {}  ", contextsInfo.toSeq.length)
                  originator ! contextsInfo.values.toSeq
                }
              }
            case _ =>
              logger.warn("when send  SparkContextInfo ,it get other messag")
              othermsg = othermsg + 1
              if (keynum == (contextsInfo.toSeq.length + othermsg)) {
                logger.info("send all length in it  {}  ", contextsInfo.toSeq.length)
                originator ! contextsInfo.values.toSeq
              }
          }
        }
      }

    case AddContext(name, contextConfig) =>
      val originator = sender // Sender is a mutable reference, must capture in immutable val
      val mergedConfig = contextConfig.withFallback(defaultContextConfig)
      if (contexts contains name) {
        originator ! ContextAlreadyExists
      } else {
        startContext(name, mergedConfig, false, contextTimeout) { contextMgr =>
          originator ! ContextInitialized("")
        } { err =>
          originator ! ContextInitError(err)
        }
      }


    case DropOldContext(contextProcessId) =>
      val originator = sender()

      //这个本地进程，就直接返回了，没有什么进程在后台没有kill掉的
      originator ! DropContextSuccs(contextProcessId)



    case StartAdHocContext(classPath, contextConfig) =>
      val originator = sender // Sender is a mutable reference, must capture in immutable val
      logger.info("Creating SparkContext for adhoc jobs.")

      val mergedConfig = contextConfig.withFallback(defaultContextConfig)

      // Keep generating context name till there is no collision
      var contextName = ""
      do {
        contextName = java.util.UUID.randomUUID().toString().substring(0, 8) + "-" + classPath
      } while (contexts contains contextName)

      // Create JobManagerActor and JobResultActor 这里进行了起动了
      startContext(contextName, mergedConfig, true, contextTimeout) { contextMgr =>
        originator ! contexts(contextName)
      } { err =>
        originator ! ContextInitError(err)
      }

    case GetResultActor(name) =>
      sender ! contexts.get(name).map(_._2).getOrElse(globalResultActor)

    case GetContext(name) =>
      if (contexts contains name) {
        val future = (contexts(name)._1 ? SparkContextStatus) (contextTimeout.seconds)
        val originator = sender
        future.collect {
          case SparkContextAlive => originator ! contexts(name)
          case SparkContextDead =>
            logger.info("SparkContext {} is dead", name)
            self ! StopContext(name)
            originator ! NoSuchContext
        }
      } else {
        sender ! NoSuchContext
      }

    case StopContext(name) =>
      if (contexts contains name) {
        logger.info("Shutting down context {}", name)
        contexts(name)._1 ! PoisonPill
        sender ! ContextStopped
      } else {
        sender ! NoSuchContext
      }

    case Terminated(actorRef) =>
      val name = actorRef.path.name
      logger.info("Actor terminated: " + name)
      contexts.remove(name)
  }

  /**
    * 本进程创建上下文
    * @param name
    * @param contextConfig
    * @param isAdHoc
    * @param timeoutSecs
    * @param successFunc
    * @param failureFunc
    */
  private def startContext(name: String, contextConfig: Config, isAdHoc: Boolean, timeoutSecs: Int = 1)
                          (successFunc: ActorRef => Unit)
                          (failureFunc: Throwable => Unit) {
    require(!(contexts contains name), "There is already a context named " + name)
    logger.info("Creating a SparkContext named {}", name)

    val resultActorRef = if (isAdHoc) Some(globalResultActor) else None
    val mergedConfig = ConfigFactory.parseMap(
                         Map("is-adhoc" -> isAdHoc.toString,
                             "context.name" -> name,
                             "context.actorname" -> name).asJava
                       ).withFallback(contextConfig)
    //每个上下文进来，都要创建 JobManagerActor 对象
    val ref = context.actorOf(JobManagerActor.props(mergedConfig), name)
    (ref ? JobManagerActor.Initialize(
      dao, resultActorRef))(Timeout(timeoutSecs.second)).onComplete {
      case Failure(e: Exception) =>
        logger.error("Exception after sending Initialize to JobManagerActor", e)
        // Make sure we try to shut down the context in case it gets created anyways
        ref ! PoisonPill
        failureFunc(e)
      case Success(JobManagerActor.Initialized(_, resultActor)) =>
        logger.info("SparkContext {} initialized", name)
        contexts(name) = (ref, resultActor,"")
        context.watch(ref)
        //成功了
        successFunc(ref)
      case Success(JobManagerActor.InitError(t)) =>
        ref ! PoisonPill
        failureFunc(t)
      case x =>
        logger.warn("Unexpected message received by startContext: {}", x)
    }
  }

  // Adds the contexts from the config file
  private def addContextsFromConfig(config: Config) {
    for (contexts <- Try(config.getObject("spark.contexts"))) {
      contexts.keySet().asScala.foreach { contextName =>
        val contextConfig = config.getConfig("spark.contexts." + contextName)
          .withFallback(defaultContextConfig)
        startContext(contextName, contextConfig, false, contextTimeout) { ref => } {
          e => logger.error("Unable to start context " + contextName, e)
        }
        Thread sleep 500 // Give some spacing so multiple contexts can be created
      }
    }
  }

}
