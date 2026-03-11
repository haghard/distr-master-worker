addSbtPlugin("com.typesafe.sbt"   % "sbt-multi-jvm"   %   "0.4.0")
addSbtPlugin("com.scalapenos"     % "sbt-prompt"      %   "1.0.2")
addSbtPlugin("com.timushev.sbt"   % "sbt-rewarn"      %   "0.1.3")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"        %  "2.5.6")
addSbtPlugin("com.github.sbt"     % "sbt-dynver"          %  "5.0.1")
addSbtPlugin("com.eed3si9n"       % "sbt-buildinfo"       %  "0.13.1")

//https://scalameta.org/docs/semanticdb/guide.html#consuming-semanticdb
addCompilerPlugin("org.scalameta" % "semanticdb-scalac" % "4.15.2" cross CrossVersion.full)
