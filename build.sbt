import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

val akkaVersion = "2.6.13"
val cassandraPluginVersion = "1.0.4"  //"0.103"
val AkkaManagementVersion  = "1.0.9"

lazy val scalacSettings = Seq(
  scalacOptions ++= Seq(
    "-target:jvm-14",
    //"-deprecation",             // Emit warning and location for usages of deprecated APIs.
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

//++ 2.12.13 or ++ 2.13.4
val `distr-master-worker` = project
  .in(file("."))
  .settings(SbtMultiJvm.multiJvmSettings: _*)
  .settings(scalacSettings)
  .settings(
    name := "dist-master-worker",
    version := "0.0.1",
    scalaVersion := "2.13.5",

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-typed"      % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed"  % akkaVersion, //to shade old akka-cluster-sharding

      //"com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra" % cassandraPluginVersion,
      // this allows us to start cassandra from the sample
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % cassandraPluginVersion,

      "com.typesafe.akka" %% "akka-coordination" % akkaVersion,
      //"com.lightbend.akka.management" %% "akka-lease-kubernetes" % AkkaManagementVersion,


      //"org.iq80.leveldb"            % "leveldb"          % "0.7",
      //"org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8",

      //"com.typesafe.akka" %% "akka-cluster-sharding-typed"  % akkaVersion, //to shade old akka-cluster-sharding

      "com.typesafe.akka" %% "akka-http"               % "10.2.4",
      "com.typesafe.akka" %% "akka-http-spray-json"    % "10.2.4",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaManagementVersion,

      "com.typesafe.akka" %% "akka-slf4j"       %   akkaVersion,
      "ch.qos.logback"    %  "logback-classic"  %   "1.2.3",

      //"ru.odnoklassniki" % "one-nio" % "1.2.0",
      
      //("com.lihaoyi" % "ammonite" % "2.3.8-32-64308dc3" % "test").cross(CrossVersion.full),

      "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion),

      //fork in run := true,
      parallelExecution in Test := false,
  ) configs MultiJvm


scalafmtOnCompile := true

//Global / cancelable := false

// transitive dependency of akka 2.5x that is brought in
dependencyOverrides += "com.typesafe.akka" %% "akka-protobuf"               % akkaVersion
dependencyOverrides += "com.typesafe.akka" %% "akka-persistence"            % akkaVersion
dependencyOverrides += "com.typesafe.akka" %% "akka-cluster-sharding"       % akkaVersion
//dependencyOverrides += "com.typesafe.akka" %% "akka-persistence-typed"      % akkaVersion
//dependencyOverrides += "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion

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