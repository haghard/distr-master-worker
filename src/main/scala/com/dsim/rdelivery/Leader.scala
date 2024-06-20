package com.dsim.rdelivery

import akka.actor.typed.Behavior
import akka.actor.typed.delivery.WorkPullingProducerController
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import com.dsim.domain.v1.WorkerTaskPB
import io.hypersistence.tsid.TSID

import java.util.concurrent.ThreadLocalRandom

/** Reliable delivery: Work pulling mode (fan-out) Allows us to do things that are very similar to what kafka's consumer
  * group does.
  *
  * Six things you can do in Akka 2.6 by Chris Batey https://youtu.be/85FW9EUOixg?t=772
  *
  * Unconfirmed messages may be lost if the producer crashes. To avoid that you need to enable the durable queue on the
  * producer side. https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#durable-producer
  *
  * The stored unconfirmed messages will be redelivered when the corresponding producer is started again. Those messages
  * may be routed to different workers than before and some of them may have already been processed but the fact that
  * they were confirmed had not been stored yet.
  *
  * All this stuff about messages being idempotent, retried and redelivered.
  *
  * What types of things does it do:
  *
  * a) It sequences your messages to guarantee idempotency. This means we can retry and then we can do deduplication on
  * the other side.
  *
  * b) It has flow control build in (to support more than just a req/resp flow).
  */
object Leader {

  sealed trait Command
  object Command {
    final case class ReqNextWrapper(
      reqNext: WorkPullingProducerController.RequestNext[WorkerTaskPB]
    ) extends Command

    case object ShutDown extends Command
  }

  def apply(): Behavior[Leader.Command] =
    Behaviors.setup { implicit ctx =>
      // implicit val exc = ctx.executionContext
      // val serialization = SerializationExtension(ctx.system.toClassic)
      // val serializer = serialization.serializerFor(classOf[akka.actor.typed.delivery.ConsumerController.SequencedMessage[_]])
      // ctx.log.error("*** {}:{} ****", serializer.identifier, serializer.getClass.getName)

      /** Durable producer. Until sent messages have been confirmed, the producer side keeps them in memory to be able
        * to resend them. If the JVM of the producer side crashes those unconfirmed messages are lost. To make sure the
        * messages can be delivered also in that scenario a DurableProducerQueue can be used. Then the unconfirmed
        * messages are stored in a durable way so that they can be redelivered when the producer is started again.
        */
      val durableQueue =
        akka.persistence.typed.delivery
          .EventSourcedProducerQueue[WorkerTaskPB](PersistenceId.ofUniqueId("tasks"))

      ctx
        .spawn(
          WorkPullingProducerController[WorkerTaskPB](
            producerId = "task-src",
            workerServiceKey = serviceKey,
            durableQueueBehavior = Some(durableQueue)
          ),
          "producer-controller"
        )
        .tell(
          WorkPullingProducerController.Start(
            ctx.messageAdapter[WorkPullingProducerController.RequestNext[WorkerTaskPB]](
              Command.ReqNextWrapper(_)
            )
          )
        )

      val cfg = ctx.system.settings.config.getConfig("akka.reliable-delivery")

      val bufferSize =
        cfg.getInt("work-pulling.producer-controller.buffer-size") +
          cfg.getInt("consumer-controller.flow-control-window")

      /*Behaviors.withTimers { timer =>
        //timer.startTimerAtFixedRate(Master.JobDescription(System.currentTimeMillis.toString), 2.seconds)

        /*ctx.system.scheduler.scheduleWithFixedDelay(3.seconds, 1500.millis) { () =>
          master.tell(Master.JobDescription(System.currentTimeMillis.toString))
        }(ctx.executionContext)*/

        Behaviors.withStash(bufferSize)(idle()(ctx, _))
      }*/

      run(ctx)

    }

  // def pulling(req: ReqNextWrapper): Behavior[Command] = {}

  def run(
    ctx: ActorContext[Leader.Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case req: Command.ReqNextWrapper =>
        // pulling(req)
        // ctx.log.info("ReqNext")
        val bts = new Array[Byte](1024 * 5)
        ThreadLocalRandom.current().nextBytes(bts)

        val tsid = TSID.fast().toLong
        val task = WorkerTaskPB(tsid, com.google.protobuf.UnsafeByteOperations.unsafeWrap(bts))

        // ctx.log.info("Produce: {}", tsid)
        req.reqNext.sendNextTo.tell(task)
        Behaviors.same

      case Command.ShutDown =>
        Behaviors.stopped
    }

  /*def idle()(implicit
    ctx: ActorContext[Master.Command],
    buf: StashBuffer[Master.Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case job: Master.JobDescription =>
        //val job = Master.JobDescription(System.currentTimeMillis.toString)
        if (buf.isFull) {
          ctx.log.warn(s"Too many requests. Master.Producer is overloaded !!! Drop ${job.jobDesc}")
          // timer to
        } else {
          ctx.log.warn(s"Stash:${job.jobDesc} Capacity:${buf.size}")
          buf.stash(job)
        }
        Behaviors.same

      case req: ReqNextWrapper =>
        buf.unstashAll(active(req.rn))
        /*if (buf.isEmpty) {
          buf.stash(req)
          Behaviors.same
        } else {
          buf.unstashAll(active(seqNum, req.rn))
        }*/

      case ShutDown =>
        Behaviors.stopped
    }*/

  /*def active(
    next: WorkPullingProducerController.RequestNext[Worker.WorkerJob]
  )(implicit ctx: ActorContext[Master.Command], buf: StashBuffer[Master.Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case _: Master.JobDescription =>
        val b = new Array[Byte](1024 * 1)
        ThreadLocalRandom.current().nextBytes(b)

        val job = Worker.WorkerJob(System.currentTimeMillis(), b)
        next.sendNextTo.tell(job)
        // ctx.log.warn(s"Produced: ${job.seqNum}")

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


      case _: ReqNextWrapper =>
        val b = new Array[Byte](100 * 1)
        ThreadLocalRandom.current().nextBytes(b)
        val job = Worker.WorkerJob(System.currentTimeMillis(), b)
        next.sendNextTo.tell(job)
        Behaviors.same

      case ShutDown =>
        Behaviors.stopped
    }*/
}

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

/*
      val lease = LeaseProvider(ctx.system)
        .getLease("master", "akka.coordination.lease.cassandra", Cluster(ctx.system).selfMember.uniqueAddress.toString)

      ctx.pipeToSelf(lease.acquire())(_.fold(_ ⇒ Acquire(false), Acquire(_)))
      Behaviors.withTimers { t ⇒
        t.startTimerAtFixedRate(AcquireTick, 5.second)
        acquireLock(lease, bufferSize)
      }
 */

/*def waitForNext(): Behavior[Command] =
    Behaviors.receiveMessage {
      case Master.JobDescription(desc @ _) ⇒
        stashBuffer.unstashAll(active(next))
      case AskReply(resultId, timeout) =>
        val response = if (timeout) ConvertTimedOut(resultId) else ConvertAccepted(resultId)
        originalReplyTo ! response
        Behaviors.same
    }*/

/*def acquireLock(lease: Lease, bufferSize: Int)(implicit ctx: ActorContext[Master.Command]): Behavior[Command] =
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
        // if (buf.isFull) ctx.log.warn("Too many requests. Master.Producer is overloaded !!!") else buf.stash(r)
        Behaviors.same
    }*/
