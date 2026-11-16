# Compose client

A native client for IntelliJ's split mode (remote development), written with Compose. It runs on the desktop JVM and, as an Android app, on a phone ([`android-app.md`](android-app.md)); both use the `compose` module, which holds everything but the platform pieces. It connects to a backend and shows IntelliJ's Project tree and an editor with the backend's highlighting that you can type in, in IntelliJ's Islands Dark look ([`intellij-look.md`](intellij-look.md)). How the protocol works: [`split-protocol.md`](split-protocol.md), sections "Connection setup", "Our own client (step 2)" and "Editing (step 4)"; the edit messages in detail: [`edit-protocol.md`](edit-protocol.md).

| Folder | What | Platform |
|---|---|---|
| `client/compose/src/commonMain/kotlin/…/connection/` | join link, client certificate (BouncyCastle), TLS 1.3, DEFLATE frames, handshake, the rd wire | JVM and Android |
| `client/compose/src/commonMain/kotlin/…/session/` | rd protocol and the models the editor needs; exposes a `StateFlow<SessionState>` | JVM and Android, with the rd jars |
| `client/compose/src/commonMain/kotlin/…/ui/` | Project tree, editor, window layout; common Compose APIs only. Fonts, icons and the editor's text input come in through `LocalClientFonts`, `LocalIcons`, `LocalEditorTextInput` | JVM and Android |
| `client/desktop/` | the JVM launcher: `Main.kt` and `desktop/`: window, fonts and icons read from the JetBrains Client install, off-screen snapshots | desktop only |
| `client/android/` | the Android app: activity, connect screen, Termux, fonts and icons from assets, soft keyboard | Android only |

One Gradle build in `client/` with three modules (`client/settings.gradle.kts`):

| Module | Plugin | Depends on |
|---|---|---|
| `:compose` | Kotlin Multiplatform with two targets, `jvm` and `android` (`com.android.kotlin.multiplatform.library`). All code is in `commonMain`, tests in `jvmTest` | JetBrains Compose foundation 1.12.2, BouncyCastle, kotlinx-serialization-json; the protocol jars as `compileOnly` |
| `:desktop` | Kotlin JVM + Compose Desktop (`application`, `run`) | `:compose`, the protocol jars as they are, `compose.desktop.currentOs` |
| `:android` | Android application | `:compose`, the protocol jars patched for ART ([`android-app.md`](android-app.md)) |

How the shared code builds (checked 2026-11-16 with Kotlin 2.3.21, AGP 9.5.1):
- Kotlin's docs say a source set can't be shared between a JVM and an Android target. With only those two targets, though, Gradle prints `compileCommonMainKotlinMetadata SKIPPED` and compiles `commonMain` once per target, each with its full platform API. `java.io.File`, rd and BouncyCastle work in `commonMain`. The IDE may still mark JDK calls there as unresolved.
- The protocol jars are `compileOnly` in `:compose` because each app needs its own copy at run time: the desktop the original Java 25 jars, Android the jars lowered to Java 17 with the `Unsafe` shim.
- On Android, `org.jetbrains.compose.foundation:foundation:1.12.2` resolves to `androidx.compose.foundation:foundation:1.12.1`, so the app still runs on androidx Compose.

## Run

Needs:
- Java 25 or newer, because JetBrains Client's protocol jars are Java 25 class files. IntelliJ's bundled JBR works.
- JetBrains Client **263.7307.200**, the same build as the backend. Gateway downloads it.

```sh
cd client
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export JETBRAINS_CLIENT_LIB="$HOME/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib"
./gradlew :desktop:run --args="'tcp://127.0.0.1:5990#jt=…&fp=…' [/absolute/path/on/the/backend/src/demo/Main.java]"
```

The optional second argument opens that file at start; otherwise double-click a file in the tree. Fonts (JetBrains Mono, Inter) and icons (`intellij.platform.icons.jar`) are read from the same JetBrains Client install.

Debug aids, all off by default:

| Variable | Effect |
|---|---|
| `CLIENT_SNAPSHOT=/tmp/client.png` | renders the window off-screen into that PNG every 3 s (works with a locked or hidden screen) |
| `CLIENT_HIGHLIGHT_DUMP=/tmp/highlights.txt` | writes every highlight of the open file: range, layer, attribute keys, covered text |
| `CLIENT_IDENTITY=/tmp/identity.txt` | keeps the client identity in that file, so a restarted client can use the same join link again (`split-protocol.md`, step 7) |
| `CLIENT_EDIT_DEBUG=1` | prints every edit request, the backend's answer and whether it matched the local edit (`[edit] …` lines) |

The first argument is the join link the backend prints, with `0.0.0.0` replaced by its address. The join token in it works for one client only, so restart the backend before connecting a second time. Starting a backend on the Mac without disturbing a running IntelliJ: `split-protocol.md`, "Running a backend on the Mac for client work".

`./gradlew :compose:jvmTest` checks the frame encoding against the bytes in the protocol doc, and the version hashes against numbers recorded from the real client.
