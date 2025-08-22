package com
package dsim

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.cluster.typed.*
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import com.dsim.http.Bootstrap
import com.dsim.rdelivery.Tables
import com.typesafe.config.ConfigFactory

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.*

object Runner extends Ops {

  val SystemName = "work-pulling"

  def main(args: Array[String]): Unit = {
    val opts: Map[String, String] = argsToOpts(args.toList)
    applySystemProperties(opts)
    run()
  }

  def guardian(hostName: String): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx =>
        implicit val sys = ctx.system

        val cluster = akka.cluster.typed.Cluster(ctx.system)
        cluster.subscriptions ! akka.cluster.typed.Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive[SelfUp] { case (ctx, _ @SelfUp(_)) =>
          cluster.subscriptions ! Unsubscribe(ctx.self)
          ClusterSingleton(ctx.system).init(
            SingletonActor(
              Behaviors
                .supervise(rdelivery.Master())
                .onFailure[Exception](akka.actor.typed.SupervisorStrategy.resume.withLoggingEnabled(true)),
              "master"
            ).withStopMessage(rdelivery.Master.Command.ShutDown)
          )

          Bootstrap(ClusterHttpManagementRoutes(akka.cluster.Cluster(sys)), hostName, 8080)
          Behaviors.same
        }
      }
      .narrow

  def run(): Unit = {
    val sysCfg          = ConfigFactory.load()
    implicit val system = akka.actor.typed.ActorSystem[Nothing](
      guardian(sysCfg.getString("akka.remote.artery.canonical.hostname")),
      SystemName,
      sysCfg
    )

    Tables.createAllTables().onComplete(r => println("INSERTED: " + r))(system.executionContext)

    ShardedDaemonProcess(system).init(
      name = "worker",
      numberOfInstances = sysCfg.getInt("num-of-workers"),
      behaviorFactory = { (id: Int) =>
        Behaviors
          .supervise(rdelivery.Worker(id))
          .onFailure[Exception](akka.actor.typed.SupervisorStrategy.restart.withLoggingEnabled(true))
      },
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Some(rdelivery.Worker.Command.WorkerGracefulShutdown),
      shardAllocationStrategy = Some(ShardAllocationStrategy.leastShardAllocationStrategy(1, 1))
    )

    val memorySize = ManagementFactory
      .getOperatingSystemMXBean()
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
      .getTotalMemorySize()

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

    akka.management.scaladsl.AkkaManagement(system).start()
    akka.management.cluster.bootstrap.ClusterBootstrap(system).start()
    akka.discovery.Discovery(system).loadServiceDiscovery("config") // kubernetes-api

    // TODO: for local debug only !!!!!!!!!!!!!!!!!!!
    val _ = scala.io.StdIn.readLine()
    system.log.warn("Shutting down ...")
    system.terminate()
    val _ = Await.result(
      system.whenTerminated,
      sysCfg.getDuration("akka.coordinated-shutdown.default-phase-timeout", TimeUnit.SECONDS).seconds
    )
    Await.result(Tables.shutdown, 2.seconds)
  }
}
