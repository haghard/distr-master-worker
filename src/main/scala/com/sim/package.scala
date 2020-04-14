package com

import com.sim.Worker.WProtocol
import akka.actor.typed.receptionist.ServiceKey

package object sim {

  val MasterWorkerKey = ServiceKey[WProtocol]("master-worker")

}
