package com.dsim.rdelivery

import akka.actor.typed.Behavior
import akka.actor.typed.delivery.WorkPullingProducerController
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.typed.Cluster
import akka.coordination.lease.scaladsl.{Lease, LeaseProvider}
import akka.persistence.typed.PersistenceId
import akka.util.Timeout

import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.DurationInt

//producer talks to ProducerController

/**  Reliable delivery: Work pulling mode (fan-out) Allows us to do thinks that are very simular to what kafka's consumer group
  *
  * Six things you can do in Akka 2.6 by Chris Batey  https://youtu.be/85FW9EUOixg?t=772
  *
  * Unconfirmed messages may be lost if the producer crashes. To avoid that you need to enable the durable queue on the producer side.
  * https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#durable-producer
  *
  * The stored unconfirmed messages will be redelivered when the corresponding producer is started again. Those messages may be routed to different
  * workers than before and some of them may have already been processed but the fact that they were confirmed had not been stored yet.
  *
  * All this stuff about messages being idempotent, retried and redelivered.
  *
  * What types of things does it do:
  *  a) It sequence your messages to guarantee idempotency. This means we can retry and then we can do deduplication on the other side.
  *  b) It has flow control build to it(not a simple req/resp).
  */
object Master {

  sealed trait Command
  final case class JobDescription(jobDesc: String) extends Command
  private final case class ReqNextWrapper(rn: WorkPullingProducerController.RequestNext[Worker.WorkerJob])
      extends Command
  private final case class AskReply(seqNum: Long, timeout: Boolean) extends Command

  private final case class Acquire(b: Boolean) extends Command
  object AcquireTick                           extends Command

  //case object ShutDown extends Command

  def leaseLostCallback: Option[Throwable] ⇒ Unit =
    ???

  def apply(): Behavior[Master.Command] =
    Behaviors.setup { implicit ctx ⇒
      implicit val exc = ctx.executionContext
      //val serialization = SerializationExtension(ctx.system.toClassic)
      //val serializer = serialization.serializerFor(classOf[akka.actor.typed.delivery.ConsumerController.SequencedMessage[_]])
      //ctx.log.error("*** {}:{} ****", serializer.identifier, serializer.getClass.getName)

      //Messages are sent from the Master to WorkPullingProducerController and via ConsumerController actors, which handle the delivery and confirmation of the processing in the destination worker (consumer) actor.

      val durableQueue =
        akka.persistence.typed.delivery
          .EventSourcedProducerQueue[Worker.WorkerJob](PersistenceId.ofUniqueId("messages"))

      //WorkPullingProducerController
      ctx
        .spawn(
          WorkPullingProducerController(
            producerId = "master",
            workerServiceKey = serviceKey,
            durableQueueBehavior = Some(durableQueue) //None
          ),
          "producer-controller"
        )
        .tell(
          WorkPullingProducerController.Start(
            ctx.messageAdapter[WorkPullingProducerController.RequestNext[Worker.WorkerJob]](ReqNextWrapper(_))
          )
        )

      val cfg = ctx.system.settings.config.getConfig("akka.reliable-delivery")
      val bufferSize =
        cfg.getInt("work-pulling.producer-controller.buffer-size") +
        cfg.getInt("consumer-controller.flow-control-window")

      //https://github.com/akka/akka-persistence-cassandra/issues/851

      /*
      val lease = LeaseProvider(ctx.system)
        .getLease("master", "akka.coordination.lease.cassandra", Cluster(ctx.system).selfMember.uniqueAddress.toString)

      ctx.pipeToSelf(lease.acquire())(_.fold(_ ⇒ Acquire(false), Acquire(_)))
      Behaviors.withTimers { t ⇒
        t.startTimerAtFixedRate(AcquireTick, 5.second)
        acquireLock(lease, bufferSize)
      }
       */
      Behaviors.withStash(bufferSize)(implicit buf ⇒ idle(0))

    /*
      val f = lease.acquire().map { acquired =>
        if(acquired) Behaviors.withStash(bufferSize)(implicit buf ⇒ idle(0))
        else ctx.system.scheduler.scheduleOnce()
        // schedule next acquisition attempt
      }.recover {
        case e: Throwable =>
        // schedule next acquisition attempt
        ???
      }*/
    }

  def acquireLock(lease: Lease, bufferSize: Int)(implicit ctx: ActorContext[Master.Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Acquire(r) ⇒
        if (r) {
          ctx.log.warn("★ ★ ★ ★  Lock Acquired. Master [buffer:{}]  ★ ★ ★ ★", bufferSize)
          Behaviors.withStash(bufferSize)(implicit buf ⇒ idle(0))
        } else Behaviors.same
      case AcquireTick ⇒
        ctx.log.warn("AcquireTick")
        ctx.pipeToSelf(lease.acquire())(_.fold(_ ⇒ Acquire(false), Acquire(_)))
        Behaviors.same
      case _ ⇒
        //if (buf.isFull) ctx.log.warn("Too many requests. Master.Producer is overloaded !!!") else buf.stash(r)
        Behaviors.same
    }

  def idle(seqNum: Long)(implicit
    ctx: ActorContext[Master.Command],
    buf: StashBuffer[Master.Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case job: Master.JobDescription ⇒ //comes from outside
        if (buf.isFull) ctx.log.warn("Too many requests. Master.Producer is overloaded !!!") else buf.stash(job)
        Behaviors.same

      case r: ReqNextWrapper ⇒
        buf.unstashAll(active(seqNum, r.rn))
    }

  def active(
    seqNum: Long,
    next: WorkPullingProducerController.RequestNext[Worker.WorkerJob]
  )(implicit ctx: ActorContext[Master.Command], buf: StashBuffer[Master.Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case Master.JobDescription(desc @ _) ⇒
        val b = new Array[Byte](1024 * 1)
        ThreadLocalRandom.current().nextBytes(b)

        val job = Worker.WorkerJob(seqNum, b)
        next.sendNextTo.tell(job)
        //ctx.log.warn(s"Produced: ${job.seqNum}")

        /*
        https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#ask-from-the-producer

        implicit val askTimeout: Timeout = Timeout(3.second)
        ctx.ask[MessageWithConfirmation[Worker.WorkerJob], akka.Done](
          next.askNextTo,
          askReplyTo => MessageWithConfirmation(job, askReplyTo)) {
          case Success(_) => AskReply(seqNum, timeout = false)
          case Failure(_)    => AskReply(seqNum, timeout = true)
        }
        waitForNext()
         */
        idle(seqNum + 1)
      case _: ReqNextWrapper ⇒
        ctx.log.error("Unexpected Demand. Stop the Master")
        Behaviors.stopped
    }

  /*def waitForNext(): Behavior[Command] =
    Behaviors.receiveMessage {
      case Master.JobDescription(desc @ _) ⇒
        stashBuffer.unstashAll(active(next))
      case AskReply(resultId, timeout) =>
        val response = if (timeout) ConvertTimedOut(resultId) else ConvertAccepted(resultId)
        originalReplyTo ! response
        Behaviors.same
    }*/
}
