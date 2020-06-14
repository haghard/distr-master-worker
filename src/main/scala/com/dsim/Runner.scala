package com
package dsim

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, PostStop, Terminated}
import akka.cluster.typed.{ClusterSingleton, SelfUp, SingletonActor, Unsubscribe}
import com.dsim.http.{Api, ServerBootstrap}
import com.typesafe.config.ConfigFactory
import akka.actor.typed.scaladsl.adapter._
import com.dsim.rdelilery.WorkMaster

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.io.StdIn

/**
  * runMain com.dsim.Runner 2551
  * runMain com.dsim.Runner 2552
  * runMain com.dsim.Runner 2553
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

        Behaviors.receive[SelfUp] {
          case (ctx, _ @SelfUp(_ /*state*/ )) =>
            //val upMemebers = state.members.filter(_.status == akka.cluster.MemberStatus.Up).map(_.address)
            cluster.subscriptions ! Unsubscribe(ctx.self)

            //ctx.spawn(ClusterListenerActor(), "cluster-ev")

            val workerAddr = cluster.selfMember.address.host
              .flatMap(h => cluster.selfMember.address.port.map(p => s"$h:$p"))
              .getOrElse(throw new Exception("Couldn't get self address"))

            /*
            val master = ClusterSingleton(ctx.system).init(
              SingletonActor(Master(cluster.selfMember.uniqueAddress.address), "master")
            )
            val worker = ctx.spawn(Worker(workerAddr, master), "worker")
             */

            val master = ClusterSingleton(ctx.system).init(
              SingletonActor(rdelilery.WorkMaster(cluster.selfMember.uniqueAddress.address), "master")
            )

            //Default {"flow-control-window":50,"only-flow-control":false,"resend-interval-max":"30s","resend-interval-min":"2s"}
            val worker = ctx.spawn(rdelilery.Worker(workerAddr), "worker")

            ctx.watch(worker)

            // {"consumer-controller":{"flow-control-window":50,"only-flow-control":false,"resend-interval-max":"30s","resend-interval-min":"2s"},"producer-controller":{"durable-queue":{"request-timeout":"3s","resend-first-interval":"1s","retry-attempts":10}},"work-pulling":{"producer-controller":{"buffer-size":1000,"durable-queue":{"request-timeout":"3s","resend-first-interval":"1s","retry-attempts":10},"internal-ask-timeout":"60s"}}})
            println(ctx.system.settings.config.getConfig("akka.reliable-delivery"))
            // akka.reliable-delivery.work-pulling.producer-controller.buffer-size

            // {"flow-control-window":50,"only-flow-control":false,"resend-interval-max":"30s","resend-interval-min":"2s"}))
            println(ctx.system.settings.config.getConfig("akka.reliable-delivery.consumer-controller"))

            /*val askTo = 2.seconds
            ServerBootstrap(Api(master.narrow[Master.HttpReq], askTo), hostName, port + 100, askTo.+(1.second))(
              sys.toClassic
            )*/

            ctx.system.scheduler.scheduleWithFixedDelay(3.seconds, 300.millis) { () =>
              master.tell(WorkMaster.Job(System.currentTimeMillis.toString))
            }(ctx.executionContext)

            Behaviors.receiveSignal[SelfUp] {
              case (_, Terminated(`worker`)) =>
                ctx.log.error(s"Unrecoverable WORKER error. $worker has been terminated. Shutting down...")
                ctx.system.terminate()
                Behaviors.same //WARNING: Behaviors.stopped here leads to incorrect coordinated-shutdown and unreachable node
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

  def startup(port: Int): Unit = {
    val cfg    = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=$port").withFallback(ConfigFactory.load())
    val g      = guardian(cfg.getString("akka.remote.artery.canonical.hostname"), port)
    val system = akka.actor.typed.ActorSystem[Nothing](g, SystemName, cfg)

    val memorySize = ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
      .getTotalPhysicalMemorySize
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
    system.terminate() //triggers CoordinatedShutdown
    val _ = Await.result(
      system.whenTerminated,
      cfg.getDuration("akka.coordinated-shutdown.default-phase-timeout", TimeUnit.SECONDS).seconds
    )
  }
}
