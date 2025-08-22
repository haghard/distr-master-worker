addCommandAlias(
  "a",
  "runMain com.dsim.Runner " +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=127.0.0.1\n"+
    "-Dakka.management.http.hostname=127.0.0.1"
)

//sudo ifconfig lo0 127.0.0.2 add
addCommandAlias(
  "b",
  "runMain com.dsim.Runner " +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=127.0.0.2\n" +
    "-Dakka.management.http.hostname=127.0.0.2",
)
