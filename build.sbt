import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

val AkkaVersion = "2.6.21"
val cassandraPluginVersion = "1.1.1" //"0.103", 1.0.5

val AkkaMngVersion  = "1.4.0"
val AkkaHttpVersion = "10.2.10"

val AkkaPersistenceJdbcVersion = "5.0.4"

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
    "-Xsource:3",
    "-language:experimental.macros",
    //"-Wnonunit-statement",
    "-release:17",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Yrangepos", //semanticdb-scalac
    "-Xlog-reflective-calls",
    "-Xlint",
    //"-Wconf:cat=other-match-analysis:error" //Transform exhaustivity warnings into errors.
  )
)

//++ 2.12.17 or ++ 2.13.11
val `distr-master-worker` = project
  .in(file("."))
  .settings(SbtMultiJvm.multiJvmSettings: _*)
  .settings(scalacSettings)
  .settings(
    name := "dist-master-worker",
    version := "0.0.1",
    scalaVersion := "2.13.11",

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-typed"      % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed"  % AkkaVersion, //to shade old akka-cluster-sharding

      //"com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra" % cassandraPluginVersion,

      // this allows us to start cassandra from the sample
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % cassandraPluginVersion,

      //"com.lightbend.akka"      %% "akka-persistence-jdbc"          %     AkkaPersistenceJdbcVersion,
      //"com.swissborg"           %% "akka-persistence-postgres"      %     "0.5.0-M7",
      "com.typesafe.akka"         %% "akka-coordination" % AkkaVersion,

      "com.typesafe.akka"         %% "akka-cluster-sharding-typed" % AkkaVersion,

      //"com.lightbend.akka.management" %% "akka-lease-kubernetes" % AkkaManagementVersion,

      //transport = aeron-udp
      "io.aeron" % "aeron-driver" % "1.40.0",
      "io.aeron" % "aeron-client" % "1.40.0",

      "com.typesafe.akka" %% "akka-http"               % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json"    % AkkaHttpVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaMngVersion,

      "com.typesafe.akka" %% "akka-slf4j"       %   AkkaVersion,
      "ch.qos.logback"    %  "logback-classic"  %   "1.4.7",

      //"ru.odnoklassniki" % "one-nio" % "1.6.1",

      //https://repo1.maven.org/maven2/com/lihaoyi/ammonite_2.13.11/
      "com.lihaoyi" % "ammonite" % "3.0.0-M0-49-151446c5" % "test" cross CrossVersion.full,

      "com.typesafe.akka" %% "akka-multi-node-testkit" % AkkaVersion),

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
  val file = (sourceManaged in Test).value / "amm.scala"
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
