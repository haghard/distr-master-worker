package com.dsim

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
}
