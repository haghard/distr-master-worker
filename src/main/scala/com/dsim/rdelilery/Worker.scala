package com.dsim.rdelilery

import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

//consumer talks with ConsumerController
object Worker {
  sealed trait Command
  //final case class Job(resultId: java.util.UUID, jobDesc: String)
  final case class Job(seqNum: Long, jobDesc: String)

  private case class DeliveryEnvelope(d: ConsumerController.Delivery[Job]) extends Command

  def apply(addr: String, batchSize: Int = 1 << 2): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      val deliveryAdapter =
        ctx.messageAdapter[ConsumerController.Delivery[Job]](DeliveryEnvelope(_))

      val settings = akka.actor.typed.delivery.ConsumerController.Settings(ctx.system)
      /*val settings = akka.actor.typed.delivery.ConsumerController
        .Settings(ctx.system)
        //Many unconfirmed messages can be in flight between the ProducerController and ConsumerController, but their number is limited by a flow control window.
        //.withFlowControlWindow(1)
        .withOnlyFlowControl(false)
        .withResendIntervalMin(2.seconds)
        .withResendIntervalMax(10.seconds)*/

      /*val settings = akka.actor.typed.delivery.ConsumerController.Settings(
        config.getInt("flow-control-window"),
        config.getDuration("resend-interval-min").asScala,
        config.getDuration("resend-interval-max").asScala,
        config.getBoolean("only-flow-control")
      )*/

      ctx
        .spawn(ConsumerController(serviceKey, settings), "consumer-controller")
        .tell(ConsumerController.Start(deliveryAdapter))

      active(batchSize, Vector.empty)
    }

  def active(windowSize: Int, buf: Vector[Long])(implicit ctx: ActorContext[Worker.Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case DeliveryEnvelope(env) =>
        //val _ = ctx.log.warn(s"consume ${env.producerId}:${env.seqNr} msg: ${env.message.seqNum}")

        val up = buf :+ env.message.seqNum

        //The next message is not delivered until the previous one is confirmed. Any messages from the producer that arrive
        //while waiting for the confirmation are stashed by the ConsumerController and delivered when the previous message is confirmed.
        //So we need to confirm to receive the next message
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
