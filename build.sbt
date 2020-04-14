import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import sbt.CrossVersion

val akkaVersion = "2.6.4"

lazy val scalacSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",             // Emit warning and location for usages of deprecated APIs.
    "-unchecked",               // Enable additional warnings where generated code depends on assumptions.
    "-encoding", "UTF-8",       // Specify character encoding used by source files.
    "-Ywarn-dead-code",         // Warn when dead code is identified.
    "-Ywarn-extra-implicit",    // Warn when more than one implicit parameter section is defined.
    "-Ywarn-numeric-widen",     // Warn when numerics are widened.
    "-Ywarn-unused:implicits",  // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports",    // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals",     // Warn if a local definition is unused.
    "-Ywarn-unused:params",     // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars",    // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates",   // Warn if a private member is unused.
    "-Ywarn-value-discard"      // Warn when non-Unit expression results are unused.
  )
)

val `distr-master-worker` = project
  .in(file("."))
  .settings(SbtMultiJvm.multiJvmSettings: _*)
  .settings(scalacSettings)
  .settings(
    name := "dist-master-worker",
    version := "0.0.1",
    scalaVersion := "2.13.1",

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
      "org.sisioh"        %% "akka-cluster-custom-downing" % "0.1.0",
      "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion, //to shade old akka-cluster-sharding

      "com.typesafe.akka" %% "akka-http" % "10.1.11",
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.11",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % "1.0.6",

      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      
      ("com.lihaoyi" % "ammonite" % "2.0.4" % "test").cross(CrossVersion.full),
      "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion),

    //
    fork in run := false,

    // disable parallel tests
    parallelExecution in Test := false,
    
    javaOptions ++= Seq("-Xmx512m", "-Xms256m", "-XX:+UseG1GC", "-XX:+HeapDumpOnOutOfMemoryError")

  ) configs MultiJvm

scalafmtOnCompile := true

//test:run test:console
sourceGenerators in Test += Def.task {
  val file = (sourceManaged in Test).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

promptTheme := ScalapenosTheme

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value
)

libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"