/*

package akka.coordination.lease.cassandra

import akka.actor.ExtendedActorSystem
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.Lease
import akka.util.ConstantFun
import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.servererrors.{WriteTimeoutException, WriteType}
import com.dsim.CassandraSessionExtension

import java.util.concurrent.atomic.AtomicBoolean
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.Future
import scala.util.control.NonFatal

object CassandraLease0 {
  //leaseName = dsim-akka-sbr
  val configPath = "akka.coordination.lease.cassandra"
}

//see akka.coordination.lease.kubernetes.KubernetesLease
/**  CREATE TABLE IF NOT EXISTS msg.leases (name text PRIMARY KEY, owner text) with default_time_to_live = ttl
  *  where ttl == akka.cluster.split-brain-resolver.stable-after * 2.5
  */
final class CassandraLease0(system: ExtendedActorSystem, leaseTaken: AtomicBoolean, settings: LeaseSettings)
    extends Lease(settings) {

  def this(leaseSettings: LeaseSettings, system: ExtendedActorSystem) =
    this(system, new AtomicBoolean(false), leaseSettings)

  system.log.warning(s"★ ★ ★ ★ CassandraLease: $settings ★ ★ ★ ★")

  val cassandraSession = CassandraSessionExtension(system.classicSystem).session

  implicit val ec = cassandraSession.ec
  val cqlSession  = cassandraSession.underlying()

  //https://github.com/haghard/linguistic/blob/1b6bc8af7674982537cf574d3929cea203a2b6fa/server/src/main/scala/linguistic/dao/Accounts.scala
  //https://github.com/dekses/cassandra-lock/blob/master/src/main/java/com/dekses/cassandra/lock/LockFactory.java

  val ksName = system.settings.config.getString("akka.persistence.cassandra.journal.keyspace")

  val select = SimpleStatement
    .builder(s"SELECT owner FROM $ksName.leases WHERE name = ?")
    .addPositionalValues(settings.leaseName)
    .setConsistencyLevel(ConsistencyLevel.QUORUM)
    .setSerialConsistencyLevel(ConsistencyLevel.SERIAL)
    .setTracing()
    .build()

  val insert = SimpleStatement
    .builder(s"INSERT INTO ${ksName}.leases (name, owner) VALUES (?,?) IF NOT EXISTS")
    .addPositionalValues(settings.leaseName, settings.ownerName)
    .setConsistencyLevel(ConsistencyLevel.QUORUM)
    .setSerialConsistencyLevel(ConsistencyLevel.SERIAL)
    .setTracing()
    .build()

  val delete = SimpleStatement
    .builder(s"DELETE FROM ${ksName}.leases WHERE name = ? IF owner = ?")
    .addPositionalValues(settings.leaseName, settings.ownerName)
    .setConsistencyLevel(ConsistencyLevel.QUORUM)
    .setSerialConsistencyLevel(ConsistencyLevel.SERIAL)
    .setTracing()
    .build()

  override def checkLease(): Boolean = false

  override def release(): Future[Boolean] =
    cqlSession
      .flatMap { cqlSession ⇒
        system.log.warning("★ ★ ★ ★ CassandraLease:Release {} by {} ★ ★ ★ ★", settings.leaseName, settings.ownerName)
        cqlSession.executeAsync(delete).toScala.map(_ ⇒ true) //ignore the result because we have ttl on the table
      }

  override def acquire(): Future[Boolean] =
    acquire(ConstantFun.scalaAnyToUnit)

  override def acquire(leaseLostCallback: Option[Throwable] ⇒ Unit): Future[Boolean] =
    cqlSession
      .flatMap { cqlSession ⇒
        system.log.warning("★ ★ ★ ★ CassandraLease:Acquire {} by {} ★ ★ ★ ★", settings.leaseName, settings.ownerName)
        //Can in fail but still acquired the lease ???
        cqlSession.executeAsync(insert).toScala.map(_.wasApplied())
      }
      .recoverWith {
        case e: WriteTimeoutException ⇒
          system.log.error(e, "Cassandra write error :")
          if (e.getWriteType eq WriteType.CAS) {
            //The timeout has happened while doing the compare-and-swap for an conditional update.
            //In this case, the update may or may not have been applied so we try to re-read it.
            cqlSession.flatMap(_.executeAsync(select).toScala.map(_.one().getString("owner") == settings.ownerName))
            //akka.pattern.after(1.second, system.scheduler)(cqlSession.flatMap(_.executeAsync(select).toScala.map(_.one().getString("owner") == settings.ownerName)))
          } else Future.successful(false)
        case NonFatal(ex) ⇒
          system.log.error(ex, "CassandraLease. Acquire error")
          Future.successful(false)
      }
}
*/
