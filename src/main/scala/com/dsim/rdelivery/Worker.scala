package com.dsim.rdelivery

import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.typed.Cluster
import com.dsim.domain.v1.WorkerTaskPB
import io.hypersistence.tsid.TSID

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.util.control.NoStackTrace

//The consumer talks with ConsumerController

//akka.actor.typed.delivery.ConsumerController
//akka.actor.typed.delivery.WorkPullingProducerController

//https://github.com/haghard/distr-master-worker/blob/master/src/main/scala/com/dsim/rdelivery/Leader.scala
//https://github.com/haghard/distr-master-worker/blob/master/src/main/scala/com/dsim/rdelivery/Worker.scala

//
object Worker {

  sealed trait Command
  object Command {
    case object Flush                                                         extends Command
    case class DeliveryEnvelope(d: ConsumerController.Delivery[WorkerTaskPB]) extends Command
    case object WorkerGracefulShutdown                                        extends Command
  }

  final case class WorkerError(msg: String) extends Exception(msg) with NoStackTrace

  def apply(id: Int): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
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

      ctx.log.warn("★ ★ ★  Start worker{} on {} ★ ★ ★", id, Cluster(ctx.system).selfMember.address)

      // ConsumerController
      ctx
        .spawn(ConsumerController(serviceKey, settings), "consumer-controller")
        .tell(
          ConsumerController.Start(
            ctx.messageAdapter[ConsumerController.Delivery[WorkerTaskPB]](Command.DeliveryEnvelope(_))
          )
        )

      val flushPeriod = 5.second
      Behaviors.withTimers { tm =>
        tm.startTimerAtFixedRate(Command.Flush, flushPeriod)
        active(new mutable.ListBuffer())
      }
    }

  // for-testing-only
  def active(
    buf: mutable.ListBuffer[ConsumerController.Delivery[WorkerTaskPB]]
  )(implicit ctx: ActorContext[Worker.Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Command.WorkerGracefulShutdown =>
        ctx.log.warn(s"★ ★ ★ FlushOnExit batch [${buf.map(m => TSID.from(m.message.tsid)).mkString(",")}] ★ ★ ★")
        buf.clear()
        Behaviors.stopped
      case Command.Flush =>
        if (buf.isEmpty) {
          ctx.log.warn("★ ★ ★ Flush empty")
          active(buf)
        } else {
          ctx.log.warn(s"★ ★ ★ Flush batch [${buf.map(m => TSID.from(m.message.tsid)).mkString(",")}]")
          buf.clear()
          active(buf)
        }

      case Command.DeliveryEnvelope(env) =>
        val task = env.message
        val tsid = TSID.from(env.message.tsid)
        ctx.log.warn(s"Consumed: ${task.tsid}/${tsid.getInstant}")

        Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(200, 500))

        /*val rnd = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
        if (rnd < .85) {
          buf += env
          ctx.log.warn(s"Acked:${task.tsid}")
          env.confirmTo.tell(ConsumerController.Confirmed)
        } else {
          throw WorkerError(s"Boom: ${task.tsid})")
        }*/

        buf += env
        ctx.log.warn(s"Confirmed: ${task.tsid}/${tsid.getInstant}")
        // fully processed message here

        // The next message is not delivered until the previous one is confirmed. Any messages from the producer that arrive
        // while waiting for the confirmation are stashed by the ConsumerController and delivered when the previous message is confirmed.
        // So we need to confirm to receive the next message
        env.confirmTo.tell(ConsumerController.Confirmed)

        active(buf)
    }

  // at-least-once delivery: If smth fails before the confirmation, we will get the same message again
  // We should do one-by-one processing, batching is not safe
  def run(implicit ctx: ActorContext[Worker.Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Command.WorkerGracefulShutdown =>
        ctx.log.warn("★ ★ ★ WorkerGracefulShutdown  ★ ★ ★")
        Behaviors.stopped

      case Command.DeliveryEnvelope(env) =>
        val task = env.message
        val tsid = TSID.from(env.message.tsid)
        ctx.log.warn(s"Consumed: ${task.tsid}/${tsid.getInstant}")

        // message processing
        Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(200, 500))

        // fully processed message here

        // The next message is not delivered until the previous one is confirmed. Any messages from the producer that arrive
        // while waiting for the confirmation are stashed by the ConsumerController and delivered when the previous message is confirmed.
        // So we need to confirm to receive the next message
        env.confirmTo.tell(ConsumerController.Confirmed)
        ctx.log.warn(s"Confirmed: ${task.tsid}/${tsid.getInstant}")

        run
    }

}
