# Compose client

A native client for IntelliJ's split mode (remote development), written with Compose. It runs on the desktop JVM and, as an Android app, on a phone ([`android/`](android/README.md)); both build the same sources in `src/main/kotlin`. It connects to a backend and shows IntelliJ's Project tree and an editor with the backend's highlighting that you can type in, in IntelliJ's Islands Dark look ([`docs/intellij-look.md`](../docs/intellij-look.md)). How the protocol works: [`docs/split-protocol.md`](../docs/split-protocol.md), sections "Connection setup", "Our own client (step 2)" and "Editing (step 4)"; the edit messages in detail: [`docs/edit-protocol.md`](../docs/edit-protocol.md).

| Folder | What | Platform |
|---|---|---|
| `src/main/kotlin/…/connection/` | join link, client certificate (BouncyCastle), TLS 1.3, DEFLATE frames, handshake, the rd wire | JVM and Android |
| `src/main/kotlin/…/session/` | rd protocol and the models the editor needs; exposes a `StateFlow<SessionState>` | JVM and Android, with the rd jars |
| `src/main/kotlin/…/ui/` | Project tree, editor, window layout; common Compose APIs only. Fonts, icons and the editor's text input come in through `LocalClientFonts`, `LocalIcons`, `LocalEditorTextInput` | JVM and Android |
| `src/desktop/kotlin/` | `Main.kt` and `desktop/`: window, fonts and icons read from the JetBrains Client install, off-screen snapshots | desktop only |
| `android/` | the Android app: its own Gradle build over `src/main/kotlin` | Android only |

## Run

Needs:
- Java 25 or newer, because JetBrains Client's protocol jars are Java 25 class files. IntelliJ's bundled JBR works.
- JetBrains Client **263.7307.200**, the same build as the backend. Gateway downloads it.

```sh
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export JETBRAINS_CLIENT_LIB="$HOME/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib"
./gradlew run --args="'tcp://127.0.0.1:5990#jt=…&fp=…' [/absolute/path/on/the/backend/src/demo/Main.java]"
```

The optional second argument opens that file at start; otherwise double-click a file in the tree. Fonts (JetBrains Mono, Inter) and icons (`intellij.platform.icons.jar`) are read from the same JetBrains Client install.

Debug aids, all off by default:

| Variable | Effect |
|---|---|
| `CLIENT_SNAPSHOT=/tmp/client.png` | renders the window off-screen into that PNG every 3 s (works with a locked or hidden screen) |
| `CLIENT_HIGHLIGHT_DUMP=/tmp/highlights.txt` | writes every highlight of the open file: range, layer, attribute keys, covered text |
| `CLIENT_EDIT_DEBUG=1` | prints every edit request, the backend's answer and whether it matched the local edit (`[edit] …` lines) |

The first argument is the join link the backend prints, with `0.0.0.0` replaced by its address. The join token in it works for one client only, so restart the backend before connecting a second time. Starting a backend on the Mac without disturbing a running IntelliJ: `docs/split-protocol.md`, "Running a backend on the Mac for client work".

`./gradlew test` checks the frame encoding against the bytes in the protocol doc, and the version hashes against numbers recorded from the real client.
