package com.dsim.rdelivery

import akka.actor.Address
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

import java.util.concurrent.ThreadLocalRandom
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

//consumer talks with ConsumerController

/** The next message is not delivered until the previous one is confirmed. Any messages from the producer that arrive
  * while waiting for the confirmation are stashed by the ConsumerController and delivered when the previous message is confirmed.
  * So we need to confirm to receive the next message.
  */
object Worker {

  sealed trait Command
  final case class WorkerJob(seqNum: Long, jobDesc: Array[Byte])
  case object Flush                                                              extends Command
  private case class DeliveryEnvelope(d: ConsumerController.Delivery[WorkerJob]) extends Command

  def apply(address: Address, batchSize: Int = 1 << 5): Behavior[Command] =
    Behaviors.setup { implicit ctx ⇒
      //val config = ctx.system.settings.config
      val settings = akka.actor.typed.delivery.ConsumerController.Settings(ctx.system)

      /*val settings = akka.actor.typed.delivery.ConsumerController
        .Settings(ctx.system)
        //Many unconfirmed messages can be in flight between the ProducerController and ConsumerController, but their number is limited by a flow control window.
        //.withFlowControlWindow(1)
        .withOnlyFlowControl(false)
        .withResendIntervalMin(2.seconds)
        .withResendIntervalMax(10.seconds)*/

      /*
      val settings = akka.actor.typed.delivery.ConsumerController.Settings(
        config.getInt("flow-control-window"),
        config.getDuration("resend-interval-min").asScala,
        config.getDuration("resend-interval-max").asScala,
        config.getBoolean("only-flow-control")
      )*/

      ctx.log.warn("★ ★ ★ ★   Worker {} [batchSize:{}]  ★ ★ ★ ★", address, batchSize)

      //ConsumerController
      ctx
        .spawn(ConsumerController(serviceKey, settings), "consumer-controller")
        .tell(ConsumerController.Start(ctx.messageAdapter[ConsumerController.Delivery[WorkerJob]](DeliveryEnvelope(_))))

      Behaviors.withTimers { t ⇒
        //flush timeout  1.second
        t.startTimerAtFixedRate(Flush, 3.second)
        active(new mutable.ListBuffer[Long](), true)
      }
    }

  def active(
    buf: mutable.ListBuffer[Long],
    isFirst: Boolean = false
  )(implicit ctx: ActorContext[Worker.Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case Flush ⇒
        if (buf.nonEmpty) {
          ctx.log.warn(s"Flush processed batch [${buf.mkString(",")}]")
          buf.clear()
          active(buf)
        } else active(buf)
      case DeliveryEnvelope(env) ⇒
        val _   = ctx.log.warn(s"received { env:${env.seqNr}, msg:${env.message.seqNum} }")
        val job = env.message
        /*if (isFirst) {
          //buf.append(job.seqNum)
          //buf.addOne(job.seqNum)
          buf += job.seqNum
          env.confirmTo.tell(ConsumerController.Confirmed)
        } else if (ThreadLocalRandom.current().nextBoolean()) {
          buf += job.seqNum
          env.confirmTo.tell(ConsumerController.Confirmed)
        }
        active(buf)
        */

        buf += job.seqNum
        env.confirmTo.tell(ConsumerController.Confirmed)
        active(buf)

      //The next message is not delivered until the previous one is confirmed. Any messages from the producer that arrive
      //while waiting for the confirmation are stashed by the ConsumerController and delivered when the previous message is confirmed.
      //So we need to confirm to receive the next message

      /*if (up.size == bufferSize) {
        ctx.log.warn(s"consume batch [${up.mkString(",")}]")
        active(bufferSize, Vector.empty)
      } else active(bufferSize, up)*/
    }
}