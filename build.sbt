import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import sbt.Keys.connectInput


val AkkaVersion = "2.6.21"
val CassandraPluginVersion = "1.0.5" //"1.1.1" //"1.0.5"
val AkkaMngVersion  = "1.4.1"
val AkkaHttpVersion = "10.2.10"
val DiagnosticsV = "2.1.1"


//https://doc.akka.io/docs/akka-dependencies/24.05
/*
val AkkaVersion = "2.9.4"
val AkkaHttpVersion = "10.6.3"
val AkkaMngVersion = "1.5.2"
val CassandraPluginVersion = "1.1.1"// last version with CassandraLauncher  //"1.2.1"
val DiagnosticsV = "2.1.1"
*/

//val AkkaPersistenceR2dbcVersion = "1.2.4"
//val AkkaProjectionVersion = sys.props.getOrElse("akka-projection.version", "1.5.4")
//val AkkaDiagnosticsVersion = "2.1.1"

//"com.lightbend.akka" %% "akka-persistence-r2dbc" % "1.2.4"
// "com.lightbend.akka" %% "akka-persistence-jdbc" % "5.4.1"
//val AkkaPersistenceJdbcVersion = "5.4.1"
val AkkaPersistenceR2dbcVersion = "1.2.4"
val AkkaProjectionV = "1.5.4"

//"com.typesafe.akka" %% "akka-stream-kafka" % "6.0.0"

//https://repo1.maven.org/maven2/com/lihaoyi/ammonite-compiler_3.3.1/3.0.0-M2-3-b5eb4787/
val AmmoniteVersion = "3.0.2"

lazy val java17Settings = Seq(
  "-XX:+UseZGC", // https://www.baeldung.com/jvm-zgc-garbage-collector
  "--add-opens",
  "java.base/java.nio=ALL-UNNAMED",
  "--add-opens",
  "java.base/sun.nio.ch=ALL-UNNAMED"
)

lazy val scalacSettings = Seq(
  /*scalacOptions ++= Seq(
    "-release:17",
    //"-target:11",
    //"-target:jvm-14",
    //"-deprecation",             // Emit warning and location for usages of deprecated APIs.
    "-unchecked",               // Enable additional warnings where generated code depends on assumptions.
    "-encoding", "UTF-8",       // Specify character encoding used by source files.
    //"-Ywarn-dead-code",         // Warn when dead code is identified.
    "-Ywarn-extra-implicit",    // Warn when more than one implicit parameter section is defined.
    "-Ywarn-numeric-widen",     // Warn when numerics are widened.
    "-Ywarn-unused:implicits",  // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports",    // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals",     // Warn if a local definition is unused.
    "-Ywarn-unused:params",     // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars",    // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates",   // Warn if a private member is unused.
    "-Ywarn-value-discard"      // Warn when non-Unit expression results are unused.
  )*/
  scalacOptions ++= Seq(
    "-Xsource:3-cross",
    "-language:experimental.macros",
    //"-Wnonunit-statement",
    "-release:17",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Yrangepos",
    "-Xlog-reflective-calls",
    "-Xlint",

    //"-Xfatal-warnings",

    //https://github.com/apache/pekko-grpc/blob/88e8567e2decbca19642e5454729aa78cce455eb/project/Common.scala#L64
    // Generated code for methods/fields marked 'deprecated'
    "-Wconf:msg=Marked as deprecated in proto file:silent",

    //silent pb
    s"-Wconf:src=${(Compile / target).value}/scala-2.13/src_managed/.*:silent",

    "-Xmigration", //Emit migration warnings under -Xsource:3 as fatal warnings, not errors; -Xmigration disables fatality (#10439 by @som-snytt, #10511)
    "-Wconf:cat=other-match-analysis:error" //Transform exhaustivity warnings into errors.
  )
)

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

//++ 2.12.17 or ++ 2.13.16
val `distr-master-worker` = project
  .in(file("."))
  .settings(SbtMultiJvm.multiJvmSettings: _*)
  .settings(scalacSettings)
  .settings(
    name := "dist-master-worker",
    version := "0.0.1",
    scalaVersion := "2.13.16",
    javaOptions ++= java17Settings,

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-typed"      % AkkaVersion,

      "com.typesafe.akka" %% "akka-persistence-typed"  % AkkaVersion, //to shade old akka-cluster-sharding
      "com.typesafe.akka" %% "akka-persistence-query"  % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra" % CassandraPluginVersion,

      // this allows us to start cassandra from the sample
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % CassandraPluginVersion,

      "com.typesafe.akka" %% "akka-discovery"               % AkkaVersion,

      //"com.lightbend.akka" %% "akka-persistence-r2dbc" % AkkaPersistenceR2dbcVersion,

      "com.lightbend.akka" %% "akka-projection-core" % AkkaProjectionV,

      //"com.lightbend.akka"      %% "akka-persistence-jdbc"          %     AkkaPersistenceJdbcVersion,
      //"com.swissborg"           %% "akka-persistence-postgres"      %     "0.5.0-M7",

      "com.typesafe.akka"         %% "akka-coordination" % AkkaVersion,
      "com.typesafe.akka"         %% "akka-cluster-sharding-typed" % AkkaVersion,

      //"com.lightbend.akka.management" %% "akka-lease-kubernetes" % AkkaManagementVersion,

      "io.aeron" % "aeron-driver" % "1.44.1",
      "io.aeron" % "aeron-client" % "1.44.1",

      "io.moia"  %% "streamee"  % "5.0.0",

      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,

      "com.lightbend.akka" %% "akka-diagnostics" %  DiagnosticsV,

      "com.lightbend.akka.management" %% "akka-management" % AkkaMngVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaMngVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaMngVersion,

      "com.typesafe.akka" %% "akka-slf4j"       %   AkkaVersion,
      "ch.qos.logback"    %  "logback-classic"  %   "1.5.18",

      //https://vladmihalcea.com/uuid-database-primary-key/
      "io.hypersistence" % "hypersistence-tsid" % "2.1.4",
      
      //"ru.odnoklassniki" % "one-nio" % "1.7.3",

      //https://repo1.maven.org/maven2/com/lihaoyi/ammonite_2.13.11/
      "com.lihaoyi" % "ammonite" % AmmoniteVersion % "test" cross CrossVersion.full,

      "com.typesafe.akka" %% "akka-multi-node-testkit" % AkkaVersion),

    // comment out for test:run
    run / fork := true,
    run / connectInput := true,

    //run / fork := true,
    //fork in run := true,
    Test / parallelExecution := false,
  ) configs MultiJvm

//Global / cancelable := false


// transitive dependency of akka 2.5x that is brought in
dependencyOverrides ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed"             % AkkaVersion,
  "com.typesafe.akka" %% "akka-protobuf"                % AkkaVersion,
  "com.typesafe.akka" %% "akka-protobuf-v3"             % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding"        % AkkaVersion,
  "com.typesafe.akka" %% "akka-discovery"               % AkkaVersion,
  "com.typesafe.akka" %% "akka-distributed-data"        % AkkaVersion,

  "com.typesafe.akka" %% "akka-persistence"             % AkkaVersion,
  "com.typesafe.akka" %% "akka-persistence-query"       % AkkaVersion,
  "com.typesafe.akka" %% "akka-persistence-typed"       % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor"                   % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster"                 % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed"  % AkkaVersion,
  "com.typesafe.akka" %% "akka-coordination"            % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream"                  % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools"           % AkkaVersion,

  "com.typesafe.akka" %% "akka-http"                    % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-core"               % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json"         % AkkaHttpVersion,
)

Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation", "-parameters")

scalafmtOnCompile := true

//test:run test:console
Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

promptTheme := ScalapenosTheme

Compile / PB.targets := Seq(scalapb.gen() -> (Compile / sourceManaged).value)

libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"


//test:run
Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

addCommandAlias("c", "compile")
addCommandAlias("r", "reload")
