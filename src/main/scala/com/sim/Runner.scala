package com.sim

import java.util.concurrent.TimeUnit

import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.Reason
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, Terminated}
import akka.cluster.typed.{ClusterSingleton, SelfUp, SingletonActor, Unsubscribe}
import com.sim.http.{Bootstrap, HttpApi}
import com.typesafe.config.ConfigFactory
import akka.actor.typed.scaladsl.adapter._

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

  def guardian(hostName: String, port: Int): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx =>
        implicit val sys = ctx.system

        val cluster = akka.cluster.typed.Cluster(ctx.system)
        cluster.subscriptions ! akka.cluster.typed.Subscribe(ctx.self, classOf[SelfUp])

        //akka://us/user
        Behaviors.receive[SelfUp] {
          case (ctx, _ @SelfUp(_ /*state*/ )) =>
            //val upMemebers = state.members.filter(_.status == akka.cluster.MemberStatus.Up).map(_.address)

            cluster.subscriptions ! Unsubscribe(ctx.self)

            val master = ClusterSingleton(ctx.system).init(
              SingletonActor(Master(cluster.selfMember.uniqueAddress.address), "master")
            )

            val selfAddr =
              cluster.selfMember.address.host
                .flatMap(h => cluster.selfMember.address.port.map(p => s"$h-$p"))
                .getOrElse(throw new Exception("Couldn't get self address"))

            val worker = ctx.spawn(Worker(selfAddr), "worker")
            ctx.watch(worker)

            val cShutdown = CoordinatedShutdown(sys)
            new Bootstrap(HttpApi.api(master), hostName, port + 100)(sys.toClassic, cShutdown)

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
    val cfg    = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=$port").withFallback(ConfigFactory.load())
    val g      = guardian("127.0.0.1", port)
    val system = akka.actor.typed.ActorSystem[Nothing](g, SystemName, cfg)
    system.log.debug(cfg.toString)

    val _ = StdIn.readLine()
    system.log.warn("Shutting down ...")
    val _ = Await.result(
      CoordinatedShutdown(system.toClassic).run(InternalShutdown),
      cfg.getDuration("akka.coordinated-shutdown.default-phase-timeout", TimeUnit.SECONDS).seconds
    )
  }
}
