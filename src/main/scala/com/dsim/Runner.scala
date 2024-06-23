package com
package dsim

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop, Terminated}
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.cluster.typed.{ClusterSingleton, SelfUp, SingletonActor, Unsubscribe}
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.persistence.cassandra.testkit.CassandraLauncher
import com.dsim.http.Bootstrap
import com.typesafe.config.ConfigFactory

import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.{CountDownLatch, TimeUnit}
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

          ctx.spawn(ClusterListenerActor(cluster), "listener")

          ClusterSingleton(ctx.system).init(
            SingletonActor(
              Behaviors
                .supervise(rdelivery.Leader(cluster.selfMember.uniqueAddress))
                .onFailure[Exception](akka.actor.typed.SupervisorStrategy.resume.withLoggingEnabled(true)),
              "leader"
            ).withStopMessage(rdelivery.Leader.Command.ShutDown)
          )

          // {"consumer-controller":{"flow-control-window":50,"only-flow-control":false,"resend-interval-max":"30s","resend-interval-min":"2s"},"producer-controller":{"durable-queue":{"request-timeout":"3s","resend-first-interval":"1s","retry-attempts":10}},"work-pulling":{"producer-controller":{"buffer-size":1000,"durable-queue":{"request-timeout":"3s","resend-first-interval":"1s","retry-attempts":10},"internal-ask-timeout":"60s"}}})
          ctx.system.settings.config.getConfig("akka.reliable-delivery")
          // akka.reliable-delivery.work-pulling.producer-controller.buffer-size

          // {"flow-control-window":50,"only-flow-control":false,"resend-interval-max":"30s","resend-interval-min":"2s"}))
          ctx.system.settings.config.getConfig("akka.reliable-delivery.consumer-controller")

          Bootstrap(ClusterHttpManagementRoutes(akka.cluster.Cluster(sys)), hostName, 8080)
          Behaviors.same
        }

        /*Behaviors.receiveSignal[SelfUp] {
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
         } else Behaviors.same*/
      }
      .narrow

  def run(): Unit = {
    val sysCfg = ConfigFactory.load()
    val system = akka.actor.typed.ActorSystem[Nothing](
      guardian(sysCfg.getString("akka.remote.artery.canonical.hostname")),
      SystemName,
      sysCfg
    )

    // start 2/3 workers
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

    /*
        Dynamic scaling of number of workers
        Starting the sharded daemon process with initWithContext returns an ActorRef[ShardedDaemonProcessCommand] that accepts a ChangeNumberOfProcesses command
        to rescale the process to a new number of workers.
     */
    /*
      val sdp: ActorRef[ShardedDaemonProcessCommand] =
        ShardedDaemonProcess(system)
          .initWithContext(
            name = "aa",
            initialNumberOfInstances = 3,
            behaviorFactory = ???,
            settings = ???,
            stopMessage = ???, // Some()
            shardAllocationStrategy = Some(new akka.cluster.sharding.ConsistentHashingShardAllocationStrategy(3))
          )

      sdp.tell(ChangeNumberOfProcesses(4, ???))
     */

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

    akka.management.scaladsl.AkkaManagement(system).start()
    akka.management.cluster.bootstrap.ClusterBootstrap(system).start()
    akka.discovery.Discovery(system).loadServiceDiscovery("config") // kubernetes-api

    val _ = scala.io.StdIn.readLine()
    system.log.warn("Shutting down ...")
    system.terminate()
    val _ = Await.result(
      system.whenTerminated,
      sysCfg.getDuration("akka.coordinated-shutdown.default-phase-timeout", TimeUnit.SECONDS).seconds
    )
  }
}
