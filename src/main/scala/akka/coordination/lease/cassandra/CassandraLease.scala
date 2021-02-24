package akka.coordination.lease.cassandra

import java.util.concurrent.atomic.AtomicBoolean
import akka.actor.ExtendedActorSystem
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.Lease
import akka.util.ConstantFun
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.dsim.CassandraSessionExtension

import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object CassandraLease {
  val configPath = "akka.coordination.lease.cassandra"
}

//select name, owner from leases where name = 'dsim-akka-sbr';
//see KubernetesLease
final class CassandraLease(system: ExtendedActorSystem, leaseTaken: AtomicBoolean, settings: LeaseSettings)
    extends Lease(settings) {

  //dsim-akka-sbr
  //implicit val timeout = Timeout(settings.timeoutSettings.operationTimeout)
  val to = 3.seconds

  val ses = CassandraSessionExtension(system.classicSystem).session

  implicit val ec = ses.ec
  val cqlSession  = ses.underlying()

  val insert = SimpleStatement
    .builder("INSERT INTO msg.leases (name, owner) VALUES (?,?) IF NOT EXISTS")
    .addPositionalValues(settings.leaseName, settings.ownerName)
    .build()

  val delete = SimpleStatement
    .builder("DELETE FROM msg.leases WHERE name = ? IF owner = ?")
    .addPositionalValues(settings.leaseName, settings.ownerName)
    .build()

  system.log.warning(s"★ ★ ★ ★ CassandraLease: $settings ★ ★ ★ ★")

  def this(leaseSettings: LeaseSettings, system: ExtendedActorSystem) =
    this(system, new AtomicBoolean(false), leaseSettings)

  override def checkLease(): Boolean = {
    system.log.warning(s"★ ★ ★ ★ CassandraLease:checkLease: false ★ ★ ★ ★")
    false
  }

  override def release(): Future[Boolean] =
    cqlSession
      .flatMap { cqlSession ⇒
        system.log.warning("★ ★ ★ ★ CassandraLease:release {} by {} ★ ★ ★ ★", settings.leaseName, settings.ownerName)
        cqlSession.executeAsync(delete).toScala.map(_ ⇒ true) //ignore the result because we have ttl on the table
      }

  override def acquire(): Future[Boolean] =
    acquire(ConstantFun.scalaAnyToUnit)

  override def acquire(leaseLostCallback: Option[Throwable] ⇒ Unit): Future[Boolean] =
    cqlSession
      .flatMap { cqlSession ⇒
        system.log.warning("★ ★ ★ ★ CassandraLease:acquire {} by {} ★ ★ ★ ★", settings.leaseName, settings.ownerName)
        //Can in fail but still acquired the lease ???
        cqlSession.executeAsync(insert).toScala.map(_.wasApplied())
      }

  /*override def acquire(leaseLostCallback: Option[Throwable] ⇒ Unit): Future[Boolean] =
    cqlSession
      .flatMap { cqlSession ⇒
        system.log.warning("★ ★ ★ ★ CassandraLease:acquire {} by {} ★ ★ ★ ★", settings.leaseName, settings.ownerName)
        cqlSession.executeAsync(insert).toScala.flatMap { rs ⇒
          if (rs.wasApplied()) Future.successful(true)
          else akka.pattern.after(to, system.scheduler)(acquire(leaseLostCallback))
        }
      }
      .recoverWith { case NonFatal(_) ⇒
        akka.pattern.after(to, system.scheduler)(acquire(leaseLostCallback))
      }*/
}
