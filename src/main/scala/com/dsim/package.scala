package com

import com.dsim.Worker.WProtocol
import akka.actor.typed.receptionist.ServiceKey

package object dsim {

  val MasterWorkerKey = ServiceKey[WProtocol]("master-worker")

}
