# Compose client

A native client for IntelliJ's split mode (remote development), written with Compose Multiplatform. For now it runs on the desktop JVM. It connects to a backend, opens one file, and shows it with the backend's syntax highlighting. How the protocol works: [`docs/split-protocol.md`](../docs/split-protocol.md), sections "Connection setup" and "Our own client (step 2)".

| Package | What | Platform |
|---|---|---|
| `connection/` | join link, client certificate (BouncyCastle), TLS 1.3, DEFLATE frames, handshake, the rd wire | plain JVM; should run on Android as is |
| `session/` | rd protocol and the models the editor needs; exposes a `StateFlow<SessionState>` | plain JVM + rd jars |
| `ui/` | the screen, common Compose APIs only | Compose (desktop now, Android later) |
| `Main.kt` | desktop window | desktop only |

## Run

Needs:
- Java 25 or newer, because JetBrains Client's protocol jars are Java 25 class files. IntelliJ's bundled JBR works.
- JetBrains Client **263.7307.200**, the same build as the backend. Gateway downloads it.

```sh
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export JETBRAINS_CLIENT_LIB="$HOME/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib"
./gradlew run --args="'tcp://127.0.0.1:5990#jt=…&fp=…' /absolute/path/on/the/backend/src/demo/Main.java"
```

The first argument is the join link the backend prints, with `0.0.0.0` replaced by its address. The join token in it works for one client only, so restart the backend before connecting a second time. Starting a backend on the Mac without disturbing a running IntelliJ: `docs/split-protocol.md`, "Running a backend on the Mac for client work".

`./gradlew test` checks the frame encoding against the bytes in the protocol doc.
