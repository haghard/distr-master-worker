package com
package sim.http

import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.server.directives.PathDirectives
import akka.actor.typed.scaladsl.AskPattern._
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.util.ByteString
import com.sim.Master.GetWorkers
import spray.json.DefaultJsonProtocol.jsonFormat1

import scala.concurrent.{Future, TimeoutException}
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success}
import spray.json._
import spray.json.DefaultJsonProtocol._
//import org.slf4j.Logger

object HttpApi extends PathDirectives with Directives {
  implicit val to = akka.util.Timeout(2.seconds) //

  sealed trait Reply
  final case class Status(master: String, workers: List[String]) extends Reply
  implicit val errorFormat0 = jsonFormat2(Status)

  final case class ServerError(error: String)
  implicit val errorFormat1 = jsonFormat1(ServerError)

  def api(
    master: ActorRef[GetWorkers]
  )(implicit sys: akka.actor.typed.ActorSystem[Nothing]) =
    extractLog { implicit log =>
      path("status") {
        get {
          //implicit val log = sys.log
          val f = master
            .ask { replyTo: ActorRef[Reply] => GetWorkers(replyTo) }
            .mapTo[Status]

          onRespComplete[Status](f) {
            case reply: Status =>
              complete(StatusCodes.OK -> Strict(`application/json`, ByteString(reply.toJson.compactPrint)))
          }
        }
      }
    }

  private def onRespComplete[T](
    responseFuture: Future[Any]
  )(f: Function[T, Route])(implicit c: ClassTag[T], log: LoggingAdapter): Route =
    onComplete(responseFuture) {
      case Success(t: T) => f(t)
      case Success(other) =>
        throw new IllegalArgumentException(
          s"Expected response of type ${c.runtimeClass.getName} instead of ${other.getClass.getName}."
        )
      case Failure(e: TimeoutException) =>
        log.error(e, s"A request for a ${c.runtimeClass.getName} did not produce a timely response")
        complete(StatusCodes.ServiceUnavailable)
      case Failure(e) =>
        log.error(e, s"A request for a ${c.runtimeClass.getName} could not be completed as expected")
        complete(
          HttpResponse(
            StatusCodes.InternalServerError,
            entity = Strict(`application/json`, ByteString(ServerError(e.getMessage).toJson.compactPrint))
          )
        )
    }

}
