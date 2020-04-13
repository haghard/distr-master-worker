package com.sim

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.Reason
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, Terminated}
import akka.cluster.typed.{ClusterSingleton, SelfUp, SingletonActor, Unsubscribe}
import com.sim.http.{Bootstrap, HttpApi}
import com.typesafe.config.ConfigFactory
import akka.actor.typed.scaladsl.adapter._
import com.sim.Master.GetWorkers

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.io.StdIn

/**
  * runMain com.sim.Runner 2551
  * runMain com.sim.Runner 2552
  * runMain com.sim.Runner 2553
  */
object Runner extends App {
  val SystemName = "sim"
  case object InternalShutdown extends Reason

  if (args.isEmpty) throw new Exception("Port is missing") else startup(args(0).toInt)

  def root(hostName: String, port: Int): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx =>
        implicit val sys = ctx.system

        val cluster = akka.cluster.typed.Cluster(ctx.system)
        cluster.subscriptions ! akka.cluster.typed.Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive[SelfUp] {
          case (ctx, _ @SelfUp(_ /*state*/ )) =>
            //val upMemebers = state.members.filter(_.status == akka.cluster.MemberStatus.Up).map(_.address)

            cluster.subscriptions ! Unsubscribe(ctx.self)

            val master = ClusterSingleton(ctx.system).init(
              SingletonActor(Master(cluster.selfMember.uniqueAddress.address), "master")
            )

            val addr =
              cluster.selfMember.address.host
                .flatMap(h => cluster.selfMember.address.port.map(p => s"$h:$p"))
                .getOrElse(throw new Exception("Couldn't get self address"))
            val worker = ctx.spawn(Worker(addr), "worker")
            ctx.watch(worker)

            val cShutdown = CoordinatedShutdown(sys)
            new Bootstrap(HttpApi.api(master.narrow[GetWorkers]), hostName, port + 100)(sys.toClassic, cShutdown)

            Behaviors.receiveSignal[SelfUp] {
              case (_, Terminated(`master`)) =>
                ctx.log.error(
                  s"Unrecoverable error. $worker has been terminated. Trigger CoordinatedShutdown"
                )
                cShutdown.run(InternalShutdown)
                Behaviors.empty
            }
        }
      }
      .narrow

  def startup(port: Int): Unit = {
    val cfg       = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=$port").withFallback(ConfigFactory.load())
    val rootActor = root("127.0.0.1", port)
    val system    = akka.actor.typed.ActorSystem[Nothing](rootActor, SystemName, cfg)

    val memorySize = ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
      .getTotalPhysicalMemorySize
    val runtimeInfo = new StringBuilder()
      .append("=================================================================================================")
      .append('\n')
      .append(s"Cores:${Runtime.getRuntime.availableProcessors}")
      .append('\n')
      .append("Total Memory:" + Runtime.getRuntime.totalMemory / 1000000 + "Mb")
      .append('\n')
      .append("Max Memory:" + Runtime.getRuntime.maxMemory / 1000000 + "Mb")
      .append('\n')
      .append("Free Memory:" + Runtime.getRuntime.freeMemory / 1000000 + "Mb")
      .append('\n')
      .append("RAM:" + memorySize / 1000000 + "Mb")
      .append('\n')
      .append("=================================================================================================")
      .toString()

    system.log.info(runtimeInfo)

    val _ = StdIn.readLine()
    system.log.warn("Shutting down ...")
    val _ = Await.result(
      CoordinatedShutdown(system.toClassic).run(InternalShutdown),
      cfg.getDuration("akka.coordinated-shutdown.default-phase-timeout", TimeUnit.SECONDS).seconds
    )
  }
}
