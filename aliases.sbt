addCommandAlias(
  "a",
  "runMain com.dsim.Runner " +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=127.0.0.1\n"+
    "-Dakka.management.http.hostname=127.0.0.1"
)

//sudo ifconfig lo0 127.0.0.2 add
//sudo ifconfig lo0 alias 127.0.0.2 up
addCommandAlias(
  "b",
  "runMain com.dsim.Runner " +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=127.0.0.2\n" +
    "-Dakka.management.http.hostname=127.0.0.2",
)

addCommandAlias(
  "d",
  "runMain com.dsim.Runner " +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=127.0.0.3\n" +
    "-Dakka.management.http.hostname=127.0.0.3",
)
