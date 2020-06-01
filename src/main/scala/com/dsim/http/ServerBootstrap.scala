package com
package dsim.http

package com.evolutiongaming.livesim.launch

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.actor.CoordinatedShutdown.{PhaseActorSystemTerminate, PhaseBeforeServiceUnbind, PhaseServiceRequestsDone, PhaseServiceUnbind, Reason}

import scala.concurrent.Future

object ServerBootstrap {

  private case object HttpServerBindFailure extends Reason
}

case class ServerBootstrap(
  routes: Route,
  host: String,
  httpPort: Int,
  terminationDeadline: FiniteDuration //http layer ask timeout + some extra time
)(implicit classicSystem: akka.actor.ActorSystem) {

  implicit val ex = classicSystem.dispatcher

  private val cShutdown = CoordinatedShutdown(classicSystem)

  Http()
    .bindAndHandle(routes, host, httpPort)
    .onComplete {
      case Failure(ex) =>
        classicSystem.log.error(ex, s"Shutting down because can't bind on $host:$httpPort")
        cShutdown.run(ServerBootstrap.HttpServerBindFailure)
      case Success(binding) =>
        classicSystem.log.info(s"Started http server on $host:$httpPort")
        cShutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () =>
          Future {
            classicSystem.log.warning("CoordinatedShutdown started")
            Done
          }
        }

        cShutdown.addTask(PhaseServiceUnbind, "api.unbind") { () =>
          //No new connections are accepted. Existing connections are still allowed to perform request/response cycles
          binding.unbind().transform { r =>
            classicSystem.log.warning("CoordinatedShutdown: [api.unbind]")
            r
          }
        }

        //graceful termination request being handled on this connection
        cShutdown.addTask(PhaseServiceRequestsDone, "http-api.terminate") { () =>
          /**
            * It doesn't accept new connection but it drains the existing connections
            * Until the `terminationDeadline` all the req that have been accepted will be completed
            * and only than the shutdown will continue
            */
          binding.terminate(terminationDeadline).transform {
            _.map { _ =>
              classicSystem.log.warning("CoordinatedShutdown: [http-api.terminate]")
              Done
            }
          }
        }

        //app level resources
        /*cShutdown.addTask(CoordinatedShutdown.PhaseServiceStop, "stop.bots") { () =>
          //Best effort attempt to guarantee that all locally running bots have been asked to stopped
          worker.tell(Worker.GracefulExit)
          akka.pattern.after(4.seconds, classicSystem.scheduler)(Future.successful(akka.Done))
            .transform { r =>
              classicSystem.log.info(s"CoordinatedShutdown: [stop.bots]")
              r
            }
        }*/

        //forcefully kills connections that are still open
        cShutdown.addTask(CoordinatedShutdown.PhaseServiceStop, "close.connections") { () =>
          Http().shutdownAllConnectionPools().transform {
            _.map { _ =>
              classicSystem.log.warning("CoordinatedShutdown: [close.connections]")
              Done
            }
          }
        }

        cShutdown.addTask(PhaseActorSystemTerminate, "sys.term") { () =>
          Future.successful {
            classicSystem.log.warning("CoordinatedShutdown successfully reaches last phase [actor-system-terminate]")
            Done
          }
        }
    }
}
