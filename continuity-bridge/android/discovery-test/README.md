# Local ADB discovery regression suite

Run `./run-embedded-discovery-tests.sh` from the Android directory. It uses `JAVA_HOME`,
or Android Studio's bundled JBR, and compiles with `javac -Xlint:all -Werror`.
An optional first argument selects a saved `LocalAdbDiscovery.java` source for a
regression comparison; the runner copies it into its own temporary tree.

The suite substitutes only the Android Context, NSD, and Log APIs. Discovery runs
on a worker thread while tests deliver NSD callbacks. Real `127.0.0.1`
`ServerSocket` instances use ephemeral ports to verify these outcomes:

- A stale first advertisement is skipped for a later listening local endpoint.
- A nonlocal advertised address is rejected even when its port listens locally.
- Interrupting pending discovery propagates `InterruptedException` and stops NSD.
- Closed candidates expire at the overall discovery deadline instead of succeeding.
- A reported NSD start failure propagates an error and stops NSD.

Successful probes are accepted by the fixture and checked for EOF without data.
All sockets and workers are closed, and the runner removes only its own `mktemp`
directory. The suite takes about eight seconds because it exercises the real
discovery timeout. It does not exercise Android's NSD implementation, ADB TLS,
pairing, or a physical device.
