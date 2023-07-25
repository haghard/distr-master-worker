package com.dsim

import akka.cluster.ddata.ReplicatedData

package object crdt {

  // format: OFF
  /**
    *
    * When the job is submitted, it is recorded as "New”. Then it becomes "Started", "Running" and finally "Finished"
    * (ignoring failures and retries for the sake of simplicity here).
    *
    * But another possibility is that the client that submitted the job decides to aborted its execution because the computation is no longer needed.
    *
    * Performance test case status values as CRDTs with their merge ordering indicated by state progression arrows: walking in the direction of the
    * arrows goes from predecessor to successor in the merge order.
    *
    *                           +-------> Failed ----+
    *                           |                    |
    *                 +----->Running ---> Aborted ---+
    *                 |         |                    |
    * New -------->Started      +--------------------+-----> Finished
    *                 |                              |
    *                 +----->Rejected(over capacity)-+
    *
    *
    * In the case of Running versus Rejected the decision is
    *
    * merge(Running, Rejected) = Rejected
    * merge(Aborted, Rejected) = Aborted
    * merge(Failed, Rejected)  = Failed
    * merge(Failed, Aborted)  = Aborted
    *
    * If the task was rejected on the master, it hasn't passed the capacity validation, so we will pick Rejected status.
    *
    */
  // format: ON
  val New: Status      = Status("New")(Set.empty, Set(Started))
  val Started: Status  = Status("Started")(Set(New), Set(Running, Rejected))
  val Running: Status  = Status("Running")(Set(Started), Set(Finished, Failed, Aborted))
  val Rejected: Status = Status("Rejected")(Set(Started), Set(Finished))
  val Aborted: Status  = Status("Aborted")(Set(Running), Set(Finished))
  val Failed: Status   = Status("Failed")(Set(Running), Set(Finished))
  val Finished: Status = Status("Finished")(Set(Running, Failed, Aborted, Rejected), Set.empty)

  val RunningVsRejectedConflict = Set(Running, Rejected)
  val AbortedVsRejectedConflict = Set(Aborted, Rejected)
  val FailedVsRejectedConflict  = Set(Failed, Rejected)
  val FailedVsAbortedConflict   = Set(Failed, Aborted)

  val new0     = ReplicatedPerformanceTest(New)
  val started  = ReplicatedPerformanceTest(Started)
  val running  = ReplicatedPerformanceTest(Running)
  val rejected = ReplicatedPerformanceTest(Rejected)
  val aborted  = ReplicatedPerformanceTest(Aborted)
  val failed   = ReplicatedPerformanceTest(Failed)
  val finished = ReplicatedPerformanceTest(Finished)

  final case class Status(name: String)(preds: => Set[Status], succs: => Set[Status]) extends ReplicatedData {
    type T = Status
    lazy val predecessors = preds
    lazy val successors   = succs

    override def merge(that: Status): Status =
      Set(this, that) match {
        case RunningVsRejectedConflict => Rejected
        case AbortedVsRejectedConflict => Aborted
        case FailedVsRejectedConflict  => Failed
        case FailedVsAbortedConflict   => Aborted
        case _                         => mergeLoop(this, that)
      }

    private def mergeLoop(left: Status, right: Status): Status = {
      def loop(candidate: Status, exclude: Set[Status]): Status =
        if (isSuccessor(candidate, left, exclude))
          candidate
        else {
          val nextExclude = exclude + candidate
          val branches    = candidate.successors.map(succ => loop(succ, nextExclude))
          branches.reduce((l, r) => if (isSuccessor(l, r, nextExclude)) r else l)
        }

      def isSuccessor(candidate: Status, fixed: Status, exclude: Set[Status]): Boolean =
        if (candidate == fixed) true
        else {
          val toSearch = candidate.predecessors -- exclude
          toSearch.exists(pred => isSuccessor(pred, fixed, exclude))
        }

      loop(right, Set.empty)
    }
  }

  final case class ReplicatedPerformanceTest(status: Status) extends ReplicatedData {
    type T = ReplicatedPerformanceTest

    override def merge(that: ReplicatedPerformanceTest): ReplicatedPerformanceTest = {
      val mergedStatus = status.merge(that.status)
      ReplicatedPerformanceTest( /*test,*/ mergedStatus)
    }
  }
}
