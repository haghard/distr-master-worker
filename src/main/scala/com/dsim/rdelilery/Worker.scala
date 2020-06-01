package com.dsim.rdelilery

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.delivery.ConsumerController

//consumer talks with ConsumerController
object Worker {
  sealed trait Command
  //final case class Job(resultId: java.util.UUID, jobDesc: String)
  final case class Job(seqNum: Long, jobDesc: String)

  private case class DeliveryEnvelope(d: ConsumerController.Delivery[Job]) extends Command

  def apply(addr: String, windowSize: Int = 1 << 2): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      val deliveryAdapter =
        ctx.messageAdapter[ConsumerController.Delivery[Job]](DeliveryEnvelope(_))

      //val config: Config = ???
      //import akka.util.JavaDurationConverters.JavaDurationOps
      import scala.concurrent.duration._
      val settings = akka.actor.typed.delivery.ConsumerController
        .Settings(ctx.system)
        .withFlowControlWindow(windowSize)
        .withOnlyFlowControl(false)
        .withResendIntervalMin(2.seconds)
        .withResendIntervalMax(10.seconds)

      /*val settings = akka.actor.typed.delivery.ConsumerController.Settings(
        config.getInt("flow-control-window"),
        config.getDuration("resend-interval-min").asScala,
        config.getDuration("resend-interval-max").asScala,
        config.getBoolean("only-flow-control")
      )*/

      val consumerController =
        ctx.spawn(ConsumerController(serviceKey, settings), "consumer-controller")

      consumerController.tell(ConsumerController.Start(deliveryAdapter))

      active(windowSize, Vector.empty)
    }

  def active(windowSize: Int, buf: Vector[Long])(implicit ctx: ActorContext[Worker.Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case DeliveryEnvelope(env) =>
        //val _ = ctx.log.warn(s"consume ${env.producerId}:${env.seqNr} msg: ${env.message.seqNum}")

        val up = buf :+ env.message.seqNum
        // and when completed confirm
        env.confirmTo.tell(ConsumerController.Confirmed)

        if (up.size == windowSize) {
          ctx.log.warn(s"consume batch [${up.mkString(",")}]")
          active(windowSize, Vector.empty)
        } else
          // does job ...
          // store result with resultId key for later retrieval
          active(windowSize, up)
    }
}
