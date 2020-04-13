package com.sim.http

import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.directives.PathDirectives
import akka.actor.typed.scaladsl.AskPattern._
import com.sim.Master.GetWorkers

import scala.concurrent.duration._

object HttpApi extends PathDirectives with Directives {
  implicit val to = akka.util.Timeout(2.seconds)

  sealed trait Reply
  final case class Status(desc: String) extends Reply

  def api(
    master: ActorRef[GetWorkers]
  )(implicit sys: akka.actor.typed.ActorSystem[_]) =
    path("status") {
      get {
        val f = master
          .ask { replyTo: ActorRef[Reply] => GetWorkers(replyTo) }
          .mapTo[Status]

        onComplete(f) {
          case scala.util.Success(reply) =>
            complete(reply.desc)
          case scala.util.Failure(err) =>
            complete(StatusCodes.InternalServerError -> err.getMessage)
        }
      }
    }
}
