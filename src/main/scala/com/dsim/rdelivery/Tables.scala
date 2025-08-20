package com.dsim.rdelivery

import org.jboss.netty.util.internal.ThreadLocalRandom
import scalapb.*
import slick.jdbc.*
import slick.basic.DatabaseConfig

import java.util.UUID
import scala.concurrent.*
import scala.reflect.ClassTag

final case class ReservationRow(
  reservationId: UUID,
  status: String, // NEW->PROCESSING->DONE
  insertTs: Long,
  processingTs: Option[Long],
  failureTs: Option[Long],
  confirmationTs: Option[Long]
)

class SlickTablesGeneric(val profile: slick.jdbc.MySQLProfile) {

  import profile.api._

  implicit val GetResultUuid: GetResult[UUID] = slick.jdbc.GetResult { rs =>
    val bts = java.nio.ByteBuffer.wrap(rs.nextBytes())
    new java.util.UUID(bts.getLong(), bts.getLong())
  }

  implicit def pbMapper[T <: GeneratedMessage: ClassTag](implicit
    companion: GeneratedMessageCompanion[T]
  ): BaseColumnType[T] =
    MappedColumnType.base((pb: T) => pb.toByteArray, (bts: Array[Byte]) => companion.parseFrom(bts))

  implicit val ec: ExecutionContext = ExecutionContext.parasitic

  // How to scale this table ? RESERVATIONS_1, RESERVATIONS_2, RESERVATIONS_3, RESERVATIONS_4
  class ReservationsView(tag: Tag) extends Table[ReservationRow](tag, "RESERVATIONS") {

    // def id: Rep[Long] = column[Long]("ID", O.PrimaryKey, O.AutoInc)

    def reservationId: Rep[UUID] = column[UUID]("RESERVATION_ID")

    def status: Rep[String] = column[String]("STATUS", O.Length(100))

    def insertTs: Rep[Long] = column[Long]("INSERT_TS")

    def pickedUpForProcessingTs: Rep[Long] = column[Long]("PROCESSING_TS")

    def failedTs: Rep[Long] = column[Long]("FAILED_TS")

    def confirmedTs: Rep[Long] = column[Long]("CONFIRMED_TS")

    def pk: slick.lifted.PrimaryKey = primaryKey("RESERVATIONS_ID_PK", reservationId)

    def index: slick.lifted.Index = index("RESERVATIONS_STATUS_INSERT_TS__IND", (status, insertTs))

    def * : slick.lifted.ProvenShape[ReservationRow] =
      (reservationId, status, insertTs, pickedUpForProcessingTs.?, failedTs.?, confirmedTs.?) <>
        ((ReservationRow.apply _).tupled, ReservationRow.unapply)
  }

  object reservations extends TableQuery(new ReservationsView(_)) {
    self =>

    def markDone(id: UUID): Future[Int] =
      db.run(
        self
          .filter(_.reservationId === id)
          .map(rep => (rep.status, rep.confirmedTs))
          .update(("DONE", System.currentTimeMillis()))
      )

    def markFailed(id: UUID): Future[Int] =
      db.run(
        self
          .filter(_.reservationId === id)
          .map(rep => (rep.status, rep.failedTs))
          .update(("NEW", System.currentTimeMillis()))
      )

    def getNext(isNew: Boolean): Future[Option[UUID]] = {

      val selectProcessing = {
        val timeLimitInProcessing = 1_000 * 120 // 2 min
        val now                   = System.currentTimeMillis()
        sql"""SELECT RESERVATION_ID FROM RESERVATIONS where STATUS = "PROCESSING" and (PROCESSING_TS + #$timeLimitInProcessing) < #$now LIMIT 1 FOR UPDATE SKIP LOCKED"""
          .as[UUID]
      }

      val selectNewAndFailed =
        sql"""SELECT RESERVATION_ID FROM RESERVATIONS where STATUS in ("NEW", "FAILED") ORDER BY INSERT_TS LIMIT 1 FOR UPDATE SKIP LOCKED"""
          .as[UUID]

      val dbio: DBIO[Option[UUID]] = (if (isNew) selectNewAndFailed else selectProcessing)
        .flatMap { id =>
          if (id.nonEmpty)
            self
              .filter(_.reservationId === id.head)
              .map(rep => (rep.status, rep.pickedUpForProcessingTs))
              .update(("PROCESSING", System.currentTimeMillis()))
              .map(_ => id.headOption)
          else
            DBIO.successful(id.headOption)
        }
        .asTry
        .map(_.fold({ ex => println("Error: " + ex.getMessage); None }, identity))
        .transactionally

      db.run(dbio)
    }
  }

  val tables           = Seq(reservations)
  val ddl: profile.DDL = tables.map(_.schema).reduce(_ ++ _)
  val db               = DatabaseConfig.forConfig[MySQLProfile]("akka-persistence-jdbc.shared-databases.slick").db

  def createAllTables(): Future[Unit] =
    db.run(ddl.createIfNotExists) // DBIO.successful(())
      .flatMap { _ =>
        val rows = (0 to 150).map { _ =>
          ReservationRow(UUID.randomUUID(), "NEW", System.currentTimeMillis(), None, None, None)
        }
        db.run(DBIO.sequence(rows.map(reservations.insertOrUpdate)).map(_.size))
      }

  def nextReservation() =
    reservations.getNext(ThreadLocalRandom.current().nextDouble() > .2)

  def markDone(id: UUID) =
    reservations.markDone(id)

  def markFailed(id: UUID) =
    reservations.markFailed(id)

  def shutdown =
    db.shutdown

}

object Tables extends SlickTablesGeneric(slick.jdbc.MySQLProfile)

/*
akka.pattern.retry(
    attempt = () => mkF(ownerId, definition),
    attempts = 8,
    delayFunction = { i => Option(75.millis) }
)(system.executionContext, system.scheduler.toClassic)
 */
