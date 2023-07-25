addSbtPlugin("com.typesafe.sbt"   % "sbt-multi-jvm"   %   "0.4.0")
addSbtPlugin("com.scalapenos"     % "sbt-prompt"      %   "1.0.2")
addSbtPlugin("com.timushev.sbt"   % "sbt-rewarn"      %   "0.1.3")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"        %  "2.5.0")
addSbtPlugin("com.github.sbt"     % "sbt-native-packager" %  "1.9.9")
addSbtPlugin("com.github.sbt"     % "sbt-dynver"          %  "5.0.1")
addSbtPlugin("com.eed3si9n"       % "sbt-buildinfo"       %  "0.11.0")

//https://scalameta.org/docs/semanticdb/guide.html#consuming-semanticdb
addCompilerPlugin("org.scalameta" % "semanticdb-scalac" % "4.7.8" cross CrossVersion.full)
