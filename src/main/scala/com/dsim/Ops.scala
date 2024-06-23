package com.dsim

import scala.io.Source
import scala.util.Try
import scala.util.Using

trait Ops {
  val Opt = """(\S+)=(\S+)""".r

  def argsToOpts(args: scala.collection.immutable.Seq[String]): Map[String, String] =
    args.collect { case Opt(key, value) => key -> value }.toMap

  def applySystemProperties(options: Map[String, String]): Unit =
    for ((key, value) <- options if key.startsWith("-D")) {
      val k = key.substring(2)
      println(s"Set $k: $value")
      System.setProperty(key.substring(2), value)
    }

  def setEnv(key: String, value: String): String = {
    val field = System.getenv().getClass.getDeclaredField("m")
    field.setAccessible(true)
    val map = field.get(System.getenv()).asInstanceOf[java.util.Map[java.lang.String, java.lang.String]]
    map.put(key, value)
  }

  def readFile(path: String): Try[String] =
    Try(Source.fromFile(path)).map { src =>
      Using.resource(src) { reader =>
        val buffer = new StringBuffer()
        val iter   = reader.getLines()
        while (iter.hasNext)
          buffer.append(iter.next())

        buffer.toString
      }
    }
}
