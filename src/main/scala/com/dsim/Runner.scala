package com
package dsim

import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, PostStop, Terminated}
import akka.cluster.typed.{ClusterSingleton, SelfUp, SingletonActor, Unsubscribe}
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.persistence.cassandra.testkit.CassandraLauncher
import com.dsim.http.Bootstrap
import com.dsim.http.Bootstrap.CriticalError
import com.dsim.rdelivery.Master
import com.typesafe.config.ConfigFactory

import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.StdIn

/** runMain com.dsim.Runner 2554 (cassandra)
  *
  * runMain com.dsim.Runner 2551
  *
  * runMain com.dsim.Runner 2552
  */
object Runner extends App {
  val SystemName = "dsim"

  if (args.isEmpty) throw new Exception("Port is missing") else startup(args(0).toInt)

  def guardian(hostName: String, port: Int): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx =>
        implicit val sys = ctx.system

        val cluster = akka.cluster.typed.Cluster(ctx.system)
        cluster.subscriptions ! akka.cluster.typed.Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive[SelfUp] { case (ctx, _ @SelfUp(_ /*state*/ )) =>
          // val upMemebers = state.members.filter(_.status == akka.cluster.MemberStatus.Up).map(_.address)

          cluster.subscriptions ! Unsubscribe(ctx.self)

          ctx.spawn(ClusterListenerActor(cluster), "listener")

          val mb = Behaviors
            .supervise(rdelivery.Master())
            .onFailure[Exception](akka.actor.typed.SupervisorStrategy.resume.withLoggingEnabled(true))
          // SupervisorStrategy.restartWithBackoff(minBackoff = 1.second, maxBackoff = 30.seconds, randomFactor = 0.1)

          val master =
            ClusterSingleton(ctx.system).init(SingletonActor(mb, "master").withStopMessage(rdelivery.Master.ShutDown))

          // Default {"flow-control-window":50,"only-flow-control":false,"resend-interval-max":"30s","resend-interval-min":"2s"}
          val worker = ctx.spawn(rdelivery.Worker(cluster.selfMember.uniqueAddress.address), "worker")
          ctx.watch(worker)

          // {"consumer-controller":{"flow-control-window":50,"only-flow-control":false,"resend-interval-max":"30s","resend-interval-min":"2s"},"producer-controller":{"durable-queue":{"request-timeout":"3s","resend-first-interval":"1s","retry-attempts":10}},"work-pulling":{"producer-controller":{"buffer-size":1000,"durable-queue":{"request-timeout":"3s","resend-first-interval":"1s","retry-attempts":10},"internal-ask-timeout":"60s"}}})
          ctx.system.settings.config.getConfig("akka.reliable-delivery")
          // akka.reliable-delivery.work-pulling.producer-controller.buffer-size

          // {"flow-control-window":50,"only-flow-control":false,"resend-interval-max":"30s","resend-interval-min":"2s"}))
          ctx.system.settings.config.getConfig("akka.reliable-delivery.consumer-controller")

          Bootstrap(ClusterHttpManagementRoutes(akka.cluster.Cluster(sys)), hostName, port + 100)

          ctx.system.scheduler.scheduleWithFixedDelay(3.seconds, 900.millis) { () =>
            master.tell(Master.JobDescription(System.currentTimeMillis.toString))
          }(ctx.executionContext)

          Behaviors.receiveSignal[SelfUp] {
            case (_, Terminated(`worker`)) =>
              ctx.log.error(s"Unrecoverable error. $worker has been terminated. Shutting down...")
              CoordinatedShutdown(sys).run(CriticalError)
              Behaviors.same
            case (_, PostStop) =>
              ctx.log.warn("Guardian has been stopped")
              Behaviors.same
            case (_, other) =>
              ctx.log.warn(s"Guardian got unexpected $other signal. Ignore it")
              Behaviors.ignore
          }
        }
      }
      .narrow

  def startCassandraDatabase(): Unit =
    CassandraLauncher.start(
      new File("./cassandra-db"),
      CassandraLauncher.DefaultTestConfigResource,
      clean = false,
      port = 9042
    )

  def startup(port: Int): Unit =
    if (port == 2554) {
      startCassandraDatabase()
      sys.addShutdownHook(CassandraLauncher.stop())
      println("Started Cassandra, press Ctrl + C to kill")
      new CountDownLatch(1).await()
    } else {
      val sysCfg =
        ConfigFactory.parseString(s"akka.remote.artery.canonical.port=$port").withFallback(ConfigFactory.load())
      val system = akka.actor.typed.ActorSystem[Nothing](
        guardian(sysCfg.getString("akka.remote.artery.canonical.hostname"), port),
        SystemName,
        sysCfg
      )

      val memorySize = ManagementFactory.getOperatingSystemMXBean
        .asInstanceOf[com.sun.management.OperatingSystemMXBean]
        .getTotalMemorySize
      val runtimeInfo = new StringBuilder()
        .append('\n')
        .append("★ ★ ★ ★ ★ ★ ★ ★ ★")
        .append('\n')
        .append(s"Cores:${Runtime.getRuntime.availableProcessors}")
        .append('\n')
        .append('\n')
        .append("Total Memory:" + Runtime.getRuntime.totalMemory / 1000000 + "Mb")
        .append('\n')
        .append("Max Memory:" + Runtime.getRuntime.maxMemory / 1000000 + "Mb")
        .append('\n')
        .append("Free Memory:" + Runtime.getRuntime.freeMemory / 1000000 + "Mb")
        .append('\n')
        .append("RAM:" + memorySize / 1000000 + "Mb")
        .append('\n')
        .append("★ ★ ★ ★ ★ ★ ★ ★ ★")
        .toString()
      system.log.info(runtimeInfo)

      val _ = StdIn.readLine()
      system.log.warn("Shutting down ...")
      system.terminate() // triggers CoordinatedShutdown
      val _ = Await.result(
        system.whenTerminated,
        sysCfg.getDuration("akka.coordinated-shutdown.default-phase-timeout", TimeUnit.SECONDS).seconds
      )
    }
}
