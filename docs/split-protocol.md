# Split-mode protocol: how JetBrains Client talks to the IntelliJ backend

Research for the native Android frontend (`client/`). Started 2026-11-15.

Everything here was read out of **JetBrains Client 263.7307.200** (the build JetBrains Gateway downloads for IntelliJ IDEA 2026.3, `IU-263.7307.200`) and checked, where it says so, on the phone (Samsung SM-S926B, Android 17, API 37, arm64). Nothing here comes from JetBrains documentation unless a link says so.

How sure each claim is:

| Mark | Meaning |
|---|---|
| **Seen** | Read in the decompiled code or bytecode, or printed by a test run |
| **Inferred** | Follows from what was seen, but not observed directly |
| **Unknown** | Not determined yet; listed in [Open questions](#open-questions) |

## Summary

| Question | Answer |
|---|---|
| What carries the frontend–backend traffic? | One TLS 1.3 connection: TCP → TLS → raw DEFLATE → length-prefixed frames → JetBrains' **rd** protocol. Seen. |
| What runs on top of rd? | Three things: generated **rd models** (project view, editor tabs, debugger, …), **Fleet RPC** (JSON messages carried by one rd model), and **Lux** (backend-painted Swing UI sent as drawing commands). Seen. |
| How do plugins reach the frontend? | Plugins not written for split mode run only on the backend; their logic works, and their own Swing UI reaches the frontend through Lux. Split plugins have a frontend module that runs inside JetBrains Client and calls its backend module over Fleet RPC. |
| Can the rd library run on Android? | **Yes, with one patched class.** `UnsafeBuffer` uses two `sun.misc.Unsafe` methods and 15 constants ART lacks. With those calls routed to a small shim, two rd protocols exchange a signal over a socket on the phone. Seen. |
| Can the generated protocol models run on Android? | **Yes.** D8 refuses their Java 25 class files (version 69). With the version lowered to 61 they dex, load, serialize, and resolve polymorphic types from their resource files on the phone. Seen. |
| Can we reuse JetBrains' connection code? | **No.** It depends on the IntelliJ platform, Swing, JNA and more (see [dependencies](#what-each-jar-depends-on)). The client has to reimplement the connection layer, which is small: [Connection setup](#connection-setup). |
| What does Android lack for the connection? | `SHA3-256` (used for the server fingerprint) and an X.509 certificate builder (for the client certificate). Both come from BouncyCastle. TLS 1.3 and raw DEFLATE are built in. Seen. |

What this means for the client: build it on JetBrains' own rd library and generated models (patched as below), and write only the connection layer and the UI ourselves.

## Layers on one connection

```
 JetBrains Client (frontend)                                 IntelliJ backend (phone, port 5990)
 ───────────────────────────                                 ──────────────────────────────────
 rd models ─┐  Fleet RPC (JSON) ─┐  Lux (draw commands) ─┐
            │   via FleetRpcModel │   via LuxModel        │
            └────────────┬────────┴───────────────────────┘
                 rd Protocol "ThinClient", IdKind.Client
                         │  each rd message = one MessageFrame
                 wire: sequence numbers + acknowledgements
                         │
                 frames: int32 length + body        (ping = length 0)
                         │
                 raw DEFLATE stream, sync flush after every write
                         │
                 TLS 1.3 (client certificate required, server checked by SHA3-256 fingerprint)
                         │
                 TCP  ─────────────────────────────────────────────►  tcp://<phone>:5990
```

The stack, as built by `TransportWrapperImpl` (seen), from the socket upward:

| Layer | Class (package `com.intellij.platform.split.connection.protocol`) | Effect on the bytes |
|---|---|---|
| Socket | `transport.SocketStreamTransport` | plain blocking `SocketChannel`, no socket options |
| Stats | `transport.StatStreamTransport` | counters and the idle watchdog; no change |
| TLS | `channel.TlsByteChannel` (tlschannel library over a JDK `SSLEngine`) | TLS 1.3 |
| Telemetry | `channel.TelemetryByteChannel` | counters; no change |
| Compression | `channel.CompressingByteChannel` | raw DEFLATE, both directions |
| Write merging | `channel.BufferedByteChannel` | merges writes made within 3 ms; no change to the byte stream |
| Framing | `channel.FramedByteChannelImpl`, `channel.frame.*` | int32 length + body |
| Wire | `wire.SecureWireOverStreamTransport` + `ServiceMessageProcessor` | sequence numbers, acks, resends; implements rd's `IWire` |

## Connection setup

### The join link

The backend prints a link like this (values shortened):

```
Join link: tcp://0.0.0.0:5990#jt=3f9c…e1&p=IU&fp=9A41…07C2&cb=263.7307.200&newUi=true&jb=25.0.4.1b635.79
```

| Part | Example | Used for | Sent on the wire? |
|---|---|---|---|
| scheme, host, port | `tcp://192.168.0.105:5990` | where to connect. Scheme must be `tcp` or `tcp-direct`, port > 0. Replace `0.0.0.0` with the phone's address. | — |
| `jt` | `3f9c…e1` | one-time join token: once a client has connected with it, other clients using it are declined (`Declining invalid token`) | **yes, inside the client certificate** (subject `PSEUDONYM`), not as a frame. Seen. |
| `fp` | `9A41…07C2` (64 upper-case hex digits) | expected server certificate fingerprint | no |
| `cb` | `263.7307.200` | which JetBrains Client build Gateway downloads | no; no version check on the wire was found |
| `jb` | `25.0.4.1b635.79` | which JBR build Gateway downloads | no |
| `p` | `IU` | product code, stored only | no |
| `newUi` | `true` | not referenced in the connection code (Unknown) | no |
| `pp` | (absent) | optional local proxy port; skip | — |

Seen in `TCPConnectionHandler` and `ClientTransportCreationUtil` (package `com.intellij.platform.frontend.split.connection`).

### TLS

Client side (seen in `TlsByteChannel` and the frontend `ClientTrustManager`):

- `SSLContext.getInstance("TLSv1.3")`, no SNI, no ALPN. The client sends the ClientHello right after TCP connects; there is no plaintext preamble.
- **Server check:** the server must present exactly one certificate. Its fingerprint is `UPPERCASE_HEX(SHA3-256(DER bytes of the certificate))`, no separators, and must equal `fp` (`String.equals`). No CA chain, dates or host name are checked. Code: `com.jetbrains.rd.platform.util.CertificateExKt.getSha3Fingerprint`:

  ```java
  MessageDigest md = MessageDigest.getInstance("SHA3-256");
  byte[] digest = md.digest(certificate.getEncoded());
  return StringUtil.toHexString(digest).toUpperCase(Locale.getDefault());
  ```

- **Client certificate (required, the server side sets `setNeedClientAuth(true)`):** generated per connection by `ClientTransportCreationUtil.createClientCertificate` and `SelfSignedCertificate.generateNewCertificate`:

  | Field | Example value |
  |---|---|
  | key | RSA 2048 |
  | validity | now → now + 1 day; serial = now in epoch milliseconds |
  | subject `SERIALNUMBER` (2.5.4.5) | `tcp://192.168.0.105:5990` (the link without its `#…` part) |
  | subject `UNIQUE_IDENTIFIER` (2.5.4.45) | the client id: `<SHA3-256 fingerprint of the CA certificate below>-<epoch ms>`, e.g. `C62C256C…514E-1794743186847`. The backend rejects any other shape ("clientId does not start with client fingerprint"). |
  | subject `T` (2.5.4.12) | anonymized install id |
  | subject `CN` | local user name, e.g. `alex` |
  | subject `PSEUDONYM` (2.5.4.65) | **the `jt` value, verbatim** |
  | issuer | a random per-process CA, `CN=<uuid>`, RSA 2048, 60 days |
  | signature | `SHA512WithRSA`, signed with the leaf's own key |
  | extensions | Subject/Authority Key Identifier, BasicConstraints(CA=false), optional product info under OID `1.3.6.1.4.1.57712.78.1` |
  | chain sent | `[leaf, CA]` |

  How the backend checks this certificate is Unknown (that code is in the backend jars). Since the CA is random per process, the backend cannot be checking a chain; it most likely reads `PSEUDONYM` and compares it with the token (Inferred).

### Frames

Everything after the TLS handshake, in both directions, is **one continuous raw DEFLATE stream** (`new Deflater(level, true)` / `new Inflater(true)`: no zlib header). It is flushed with `SYNC_FLUSH` after every write, so each write ends with `00 00 FF FF`. Keep one `Deflater` and one `Inflater` for the whole connection, because the dictionary carries over. The client uses level 1, the backend level 4. Seen in `CompressingByteChannel`.

Inside that stream (all integers big-endian, seen in `FramedByteChannelImpl` and `FrameFactory`):

```
frame        := int32 N  ||  body[N]            0 ≤ N ≤ 300,000,000
body, N == 0 := ping
body, first int32 == -1 := system frame  := int32 -1 || int32 status || data
otherwise    := message frame := int32 seq || int32 lastReceivedSeq || rdPayload
```

System frame statuses (`WireConnectStatus` ordinals):

| # | Status | Data | Used by a basic client? |
|---|---|---|---|
| 0 | `ACCEPTED` | empty, or an 8-byte `NegotiationOffer` | yes |
| 1 | `USER_DECLINED` | — | handle as failure |
| 2 | `PASSWORD_REQUIRED` | — | no (fail) |
| 3 | `JOIN_PIN` | 4 bytes, each 0–9 | yes (read and ignore) |
| 4 | `DECLINED` | empty | handle as failure |
| 5 | `INTERNAL_ERROR` | — | handle as failure |
| 6 | `GUEST_LIMIT_REACHED` | — | handle as failure |
| 7 | `SESSION_TOKEN` | token bytes | no: not used on `tcp://` |
| 8 | `TRANSPORT_ACCEPTANCE` | 1 byte, `01` = accepted | yes |
| 9 | `TRANSPORT_INDEX` | int32 | yes |
| 10 | `WIRE_EXIST` | 1 byte | yes |
| 11 | `CURRENT_CONFIRMED_FRAME_ACK` | int32: last frame number received from the other side | **yes**: both sides send it once right after `ACCEPTED` (found in step 2) |
| 12 | `FORCE_TERMINATE_WIRE` | int32 reason | close on receipt |
| 13 | `PRE_PROTOCOL_MESSAGE` | `PreProtocolMessage` | no |

Example frames, **before** compression:

| Frame | Bytes |
|---|---|
| ping | `00 00 00 00` |
| `TRANSPORT_INDEX` = 0 | `00 00 00 0C` `FF FF FF FF` `00 00 00 09` `00 00 00 00` |
| `TRANSPORT_ACCEPTANCE` = accepted | `00 00 00 09` `FF FF FF FF` `00 00 00 08` `01` |
| `WIRE_EXIST` = no | `00 00 00 09` `FF FF FF FF` `00 00 00 0A` `00` |
| `JOIN_PIN` 4 2 7 1 | `00 00 00 0C` `FF FF FF FF` `00 00 00 03` `04 02 07 01` |
| `ACCEPTED`, no offer | `00 00 00 08` `FF FF FF FF` `00 00 00 00` |
| message, seq 1, nothing received yet | `<int32 8+P>` `00 00 00 01` `00 00 00 00` `<P bytes of rd payload>` |

### Order of steps

Seen in `TransportCreatorEngineImpl`, `WireReuseUtil`, and the bytecode of `SecureWireOverStreamTransport$runConnectionFlow$1` (Fernflower could not decompile it). The backend's side is Inferred from what the client expects.

```
Client                                              Backend
  │── TCP connect ───────────────────────────────────►│
  │── TLS 1.3 handshake (client certificate) ────────►│   fp check on the client side
  │   … from here on: DEFLATE + frames; client pings every 1 s …
  │── TRANSPORT_INDEX (0) ───────────────────────────►│
  │◄─────────────────────────── TRANSPORT_ACCEPTANCE (1) │   client waits up to 4 s
  │── WIRE_EXIST (0) ────────────────────────────────►│
  │◄──────────────────────────────────── WIRE_EXIST (x) │
  │◄────────────────────────────────── JOIN_PIN (4 digits)
  │◄──────────────────────── ACCEPTED (maybe an offer) │   PASSWORD_REQUIRED → password flow
  │── ACCEPTED (empty) ──────────────────────────────►│
  │── CURRENT_CONFIRMED_FRAME_ACK (0) ───────────────►│   both directions; found in step 2
  │◄──────────────────── CURRENT_CONFIRMED_FRAME_ACK (0) │
  │   connected: rd messages as message frames, both ways
```

Rules for message frames (seen in `ServiceMessageProcessor` bytecode):

- Our `seq` starts at **1** and goes up by one per message frame we send. `lastReceivedSeq` is the highest `seq` we have accepted from the backend, starting at **0**.
- Acks ride on every message frame; there is no separate ack frame. When the backend's `lastReceivedSeq` arrives, drop our stored frames up to it.
- Incoming `seq == last + 1`: accept. `seq > last + 1`: accept (logged as a gap). `seq ≤ last`: duplicate, drop.
- The backend keeps our unconfirmed frames, up to 100 MiB (`rdct.wire.storage.wire.buffer.size.bytes`). So we must send message frames now and then, not only read. rd normally does (Inferred).
- Pings every 1 s (`rdct.wire.send.pings.interval.seconds`). If nothing arrives for 15 s (`rdct.wire.timeout.pings`), the client gives up on the connection.

Needed for a first working connection: everything in the diagram above, plus sequence numbers and pings.

Can be skipped for now:
- session token (no token handler on `tcp://`), password flow, negotiation;
- wire reuse and resending after a reconnect: always send `WIRE_EXIST` = 0 and start a fresh wire (a stored client id still needs a higher `TRANSPORT_INDEX`: [Connect screen and reconnecting (step 7)](#connect-screen-and-reconnecting-step-7));
- QUIC, STUN/TURN, WebSocket and proxy transports, telemetry.

### Where rd starts

The wire (`SecureWireOverStreamTransport`, an rd `IWire`) is handed to:

```kotlin
Protocol("ThinClient", Serializers(IdeRootMarshallersProvider), Identities(IdKind.Client), scheduler, wire, lifetime)
```

Each message frame's payload is one rd message: `RdId (int64) || message context || body`. The receiver reads the `RdId` and passes the rest to rd's `MessageBroker.dispatch`. The context and body formats belong to the rd library itself, which we reuse, so we don't need to reimplement them. Seen in `ThinClientConnectionFactory.createConnection` bytecode.

## What runs on top of rd

### Generated rd models

rd keeps models in sync: properties, maps, signals and calls whose values match on both sides. The models are generated code, shipped in two jars:

| Jar | Classes | Main packages |
|---|---|---|
| `lib/intellij.platform.split.protocol.jar` | 1,164 | `com.jetbrains.codeWithMe.model` (640), `com.jetbrains.rd.ide.model` (513) |
| `lib/intellij.rd.ide.model.generated.jar` | 3,355 | `com.jetbrains.rd.ide.model`, `com.jetbrains.ide.model.uiautomation`, … |

Example models: `ProjectViewModel`, `EditorTabsModel`, `SearchEverywhereModel`, `DebuggerModel`, `NavigationBarModel`, `RemoteSettingsModel`, `ReworkedTerminalModel`.

Types sent polymorphically go on the wire as a 64-bit id. The jars map ids to classes in resource files, e.g. `intellij.platform.split.protocol.marshallers/splitMarshallers.txt` (409 lines) and `marshallers/marshallers.txt` (1,330 lines):

```
-1127600733612500137:com.jetbrains.codeWithMe.model.RdTreeFocusNode
-1162693106004481801:com.jetbrains.codeWithMe.model.LineBreakpointTypeModel
```

`Serializers(DefaultSplitMarshallersProvider.INSTANCE)` loads classes on demand through these files, so **the resource files must ship with the dexed classes**. Each root model also has a `serializationHash` (e.g. `LuxModel.serializationHash = 5589913469715632981`). Both sides must use the same build, which is another reason to reuse the generated jars of the exact backend build (Inferred).

### Fleet RPC, carried by one rd model

The newer API that JetBrains recommends for plugins (RPC interfaces plus Rhizome, a database that syncs state between frontend and backend) does not open its own connection. Seen in `com.intellij.platform.rpc.split.RdTransportFactory` (`plugins/remdev-plugin/lib/modules/intellij.platform.rpc.split.jar`):

- the rd model `FleetRpcModel` (an rd extension model) has two signals, `incoming` and `outgoing`, of type `FleetTransportMessage`;
- `FleetTransportMessage` has one field, `message: String`;
- that string is `fleet.rpc.core.TransportMessage` serialized to **JSON** with kotlinx.serialization (`TransportMessage.Companion.deserialize(String)` uses `Json.Default`).

So a client that speaks rd can also speak Fleet RPC by sending and receiving JSON strings on those two signals. The RPC interfaces themselves (`@Rpc` Kotlin interfaces) are defined per feature. Completion is one example: `RpcCompletionRequest` in `intellij.platform.completion.frontend`.

### Lux: backend-painted Swing

Any Swing UI that has no frontend version is painted on the backend and sent as drawing commands. Seen in the `com.jetbrains.rd.ide.model.Lx*` classes:

- `LuxModel` holds a map of components, `components: Map<Int, LxAbstractComponent>`.
- Each component has `graphics`, `size`, `position`, `visible`, `cursor`, an `events` signal, and a `requestRepaint` call.
- Drawing commands: `LxDrawShapeCommand`, `LxFillShapeCommand`, `LxDrawTextCommand`, `LxDrawGlyphCommand`, `LxDrawImageCommand`, `LxCopyAreaCommand`, `LxClearStateCommand`.
- State changes: clip, transform, font, paint, stroke, composite, anti-aliasing.
- Shapes: line, rectangle, rounded rectangle, ellipse, arc, polygon, path, quad and cubic curves.
- Paints: colour, linear, radial and texture paints.
- Images: `LxPngImageContent`, plus image caching with `LxCachedImage` and `LxDiffImage`.
- Windows: `LxFrame`, `LxDialog`, `LxWindow`, popups.
- Input sent back: `LxMouseEvent`, `LxMouseWheelEvent`, `LxKeyEvent`, `LxInputMethodEvent`, `LxWindowEvent`.

This is how a dialog or tool window from any backend plugin can appear on the frontend. For the Android client, drawing `Lx*` commands on an Android `Canvas` and sending touch and key events back would show any backend UI without per-feature work (Inferred; not tried).

## Plugins

From the [JetBrains split-mode docs](https://plugins.jetbrains.com/docs/intellij/split-mode-and-remote-development.html), the [RPC docs](https://plugins.jetbrains.com/docs/intellij/remote-procedure-calls.html) and the [plugin manager docs](https://plugins.jetbrains.com/docs/intellij/plugin-management-in-split-mode.html), matched against the jars above:

| Kind of plugin | Where it runs | What reaches the frontend |
|---|---|---|
| Not split (most Marketplace plugins) | backend only | Inspections, intentions, completion, highlighting, run configurations: through the platform's own rd models and RPC. The plugin's own Swing UI: through Lux, as painted pixels (can feel slow). |
| Split (modules `shared`, `frontend`, `backend`) | frontend module inside JetBrains Client, backend module on the backend | The frontend module calls the backend module through RPC interfaces defined in `shared`, e.g. `ChatRepositoryRpcApi.getInstance().sendMessage(projectId, text)`; data is serialized with kotlinx.serialization. The plugin manager installs each part where its dependencies are met. |

For the Android client: plugins that aren't split work fully except for their custom UI, which needs Lux. **Frontend modules of split plugins won't run**: they are IntelliJ/Swing code written to run inside JetBrains Client. Their backend halves still run.

## Running rd and the models on Android

Code and a script to reproduce all of this: [`client/spikes/rd-on-art/`](../client/spikes/rd-on-art/). Run it with:

```sh
CLIENT_LIB="$HOME/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-89c5c2813f.ide.d/IntelliJ IDEA.app/Contents/lib" \
KOTLIN_STDLIB=<path to kotlin-stdlib-2.3.21.jar> \
JAVA_HOME=<JDK 17+> \
ADB_SERIAL=192.168.0.105:5555 \
client/spikes/rd-on-art/run-on-phone.sh
```

It runs the programs with `app_process` as the ADB shell user, in `/data/local/tmp`. It does not touch Termux or the backend.

### What each jar depends on

`jdeps -verbose:package`, ignoring `java.lang/util/io/nio/net/time/math/text/security`, Kotlin and the jar's own packages:

| Jar | Class version | Other dependencies | Android? |
|---|---|---|---|
| `intellij.libraries.rd.core.jar` | 61 (Java 17) | `org.apache.commons.logging` (not loaded in our tests) | yes |
| `intellij.libraries.rd.framework.jar` | 61 | `sun.misc.Unsafe` (only `UnsafeBuffer`), `javax.management.openmbean.InvalidOpenTypeException` (only `CustomExtScheduler`, not used by us) | after patching `UnsafeBuffer` |
| `intellij.libraries.rd.text.jar` | 61 | none | yes |
| `intellij.platform.split.protocol.jar` | **69 (Java 25)** | rd, `intellij.rd.ide.model.generated`, `org.jetbrains.annotations` | after lowering the class version |
| `intellij.rd.ide.model.generated.jar` | **69** | rd, rd-text; `com.intellij.openapi.Disposable` / `ExtensionPointName` only in `AggregatedMarshallersProvider` and `LazyAggregatedMarshallersProvider` (not needed: we use `DefaultSplitMarshallersProvider`) | after lowering the class version |
| `plugins/remdev-plugin/lib/modules/intellij.platform.split.connection.jar` (the connection) | 69 | ~60 IntelliJ platform packages, `java.awt`, `javax.swing`, JNA, ice4j, Guava, OpenTelemetry, tlschannel | **no**: reimplement |

### Problem 1: `sun.misc.Unsafe` on ART

rd's `UnsafeBuffer` (the buffer every rd message is written into) calls 18 `Unsafe` methods and reads 15 `Unsafe` constants. `UnsafeProbe` on the phone:

| Used by `UnsafeBuffer` | On ART (Android 17) |
|---|---|
| `allocateMemory(long)`, `freeMemory(long)` | present |
| `reallocateMemory(long,long)` | **missing** |
| `copyMemory(Object,long,Object,long,long)` (called 22 times) | **missing** |
| `get/put{Byte,Short,Char,Int,Long,Float,Double}(Object,long)` | present, but a `null` base is not treated as a raw address as it is on HotSpot (Inferred; the shim avoids relying on it) |
| constants `ARRAY_*_BASE_OFFSET` (8 array types) and `ARRAY_*_INDEX_SCALE` (7, all but `BYTE`) | **missing** (`NoSuchFieldError: ARRAY_CHAR_BASE_OFFSET`) |

The unpatched failure on the phone:

```
java.lang.NoSuchMethodError: No virtual method reallocateMemory(JJ)J in class Lsun/misc/Unsafe;
    at com.jetbrains.rd.framework.UnsafeBuffer.reallocateMemory(UnsafeBuffer.kt:52)
```

Fix, in the spike: `RewriteUnsafeCalls` (ASM, using the copy shipped with JetBrains Client) rewrites `UnsafeBuffer.class`. All 105 `Unsafe` references become calls and field reads on `com.jetbrains.rd.framework.ArtUnsafeShim`:

- `reallocateMemory` = allocate, copy, free; the shim tracks allocation sizes.
- `copyMemory` copies byte by byte.
- `get*/put*` with a `null` base use ART's address-only methods.
- The `ARRAY_*` constants are computed with `arrayBaseOffset` / `arrayIndexScale`.

`UnsafeBuffer` is created only by `AbstractBufferKt.createAbstractBuffer` and `SocketWire.Base`, and no other rd class uses `Unsafe`, so no other class needs changes. The byte-by-byte copy is the known slow spot (marked `ponytail:` in the shim). If profiling shows it matters, replace it with bulk copies, or with a `UnsafeBuffer` written on `ByteBuffer`.

### Problem 2: class file version 69

```
Error in intellij.platform.split.protocol.jar:com/intellij/platform/split/protocol/DefaultSplitMarshallersProvider.class:
java.lang.IllegalArgumentException: Unsupported class file major version 69
```

D8 from build-tools 35.0.0 (D8 8.6.2) rejects class file version 69. `downgrade-class-version.py` rewrites the version field of every class file from 69 to 61. The Kotlin compiler's output doesn't use anything newer, and D8 then converts all 4,519 classes without errors. The result is one 7.6 MB `classes.dex` for the two model jars. The only messages are warnings that D8 can't parse newer Kotlin metadata, which only matters for Kotlin reflection. A newer D8 that reads version 69 would avoid the rewrite.

### Problem 3: resource files

D8 drops non-class files. `splitMarshallers.txt` and `marshallers.txt` have to be added back next to the dex (in an APK, as Java resources). Without them, polymorphic reads can't find classes.

### Gotcha: loopback is IPv6 on Android

`InetAddress.getLoopbackAddress()` is `::1` on Android, while `SocketWire.Server` listens on `127.0.0.1`. The first phone run therefore failed with `ECONNREFUSED`. Use `InetAddress.getByName("127.0.0.1")`. This was only a test issue, but the same thing will matter when connecting through an `adb forward` or `ssh -L`.

### Results on the phone

Output of `run-on-phone.sh` on 2026-11-15 (Samsung SM-S926B, Android 17):

```
== CryptoProbe
SHA3-256: MISSING
TLSv1.3: present (AndroidOpenSSL), default protocols TLSv1.2,TLSv1.3
raw DEFLATE + SYNC_FLUSH: OK (22 bytes, ends 00 00 FF FF)
security providers: AndroidOpenSSL,CertPathProvider,BC,HarmonyJSSE
== RdOnAndroidCheck
UnsafeBuffer round trip: OK
SocketWire loopback on port 42111: OK (hello from the client)
ALL CHECKS PASSED
== ModelsOnAndroidCheck
Generated marshaller round trip: OK (12.5, -3.0)
Polymorphic read via splitMarshallers.txt: OK (28 bytes on the wire)
LuxModel.serializationHash = 5589913469715632981
ALL MODEL CHECKS PASSED
```

What each check covers:

| Program | Checks |
|---|---|
| `UnsafeProbe` | which `Unsafe` methods `UnsafeBuffer` needs exist on ART (table above) |
| `CryptoProbe` | `SHA3-256`, TLS 1.3, raw DEFLATE with sync flush |
| `RdOnAndroidCheck` | patched `UnsafeBuffer` round-trips ints, longs, doubles, strings and arrays. Two rd `Protocol`s connected by `SocketWire` over loopback deliver an `RdSignal<String>` value. |
| `ModelsOnAndroidCheck` | a generated model class (`LxPoint`) round-trips through its marshaller. A polymorphic read with fresh `Serializers(DefaultSplitMarshallersProvider.INSTANCE)` finds the class via `splitMarshallers.txt`. `LuxModel` initializes. |

Android's built-in BouncyCastle (`BC` above) has no SHA3, so the client needs its own BouncyCastle (`bcprov` for SHA3-256, `bcpkix` to build the client certificate).

## A recorded session

To learn what a client must do after the handshake, the real JetBrains Client was recorded talking to the phone backend on 2026-11-15, 16:48–16:51 IST. The recording is rd's own trace log; nothing was sniffed off the wire. Everything in this section is **Seen** in that log unless it says otherwise.

### How it was recorded

rd logs every message it sends and receives under three logger names: `protocol.SEND`, `protocol.RECV`, `protocol.INIT` (`Protocol.logCategory = "protocol"` plus `sublogger("SEND")` etc. in rd-framework). JetBrains Client turns them on from the system property `idea.log.trace.categories`, which `LogLevelConfigurationManager` reads. The launcher adds `IJ_JAVA_OPTIONS` to the JVM options.

```sh
# phone (Termux): backend on the smoke project
IDEA_JDK=$PREFIX/lib/jvm/jbr-25 REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS=0 \
  ~/intellij/idea-IU-263.7307.200/bin/remote-dev-server.sh run ~/intellij/smoke --listenOn 0.0.0.0 --port 5990

# Mac: tunnel, then the client with rd tracing
ssh -p 8022 -N -L 127.0.0.1:5990:127.0.0.1:5990 192.168.0.105
IJ_JAVA_OPTIONS='-Didea.log.trace.categories=protocol.SEND,protocol.RECV,protocol.INIT' \
  "…/JetBrainsClient-263.7307.200-aarch64…/IntelliJ IDEA.app/Contents/MacOS/jetbrains_client" \
  thinClient 'tcp://127.0.0.1:5990#jt=…&p=IU&fp=…&cb=263.7307.200&newUi=true&jb=25.0.4.1b635.79'
```

The trace is large: in about 3 minutes the client wrote 75 MB of log in six rotated files (`idea.5.log` is the oldest, `idea.log` the newest), 9,456 trace records. IntelliJ keeps only a few rotated files, so stop the client within a few minutes, or the start of the session is lost. Join the parts oldest first and run `client/spikes/session-capture/analyze-capture.py capture.log`. The log contains the join token, so it is not committed.

The recorded session: the client connects, the backend opens `smoke` with `Main.java` already open from the last session, the client shows the editor with highlighting. No dialog was opened.

### Timeline

| Time (IST) | What happened | Who |
|---|---|---|
| 16:48:21.296 | TCP connect to `127.0.0.1:5990` | client |
| 16:48:21.697 | TLS done; trust manager received 1 certificate (the backend's) | both |
| 16:48:21.698 | Sends `TRANSPORT_INDEX` = 0, waits for acceptance | client |
| 16:48:21.794 | Transport `ACCEPTED` | host |
| 16:48:21.821 | `Unable to validate session tokens. Session token data is not provided. Skip.`; no password asked | client |
| 16:48:21.979 | Wire state `CONNECTED` | both |
| 16:48:21.995 | rd `Protocol` set up on the wire; 24 built-in types registered (`kotlin.Byte` id 1 … `kotlin.BooleanArray` id 38) | client |
| 16:48:22.061 | First model created: `CodeWithMeDiagnosticsModel` (then 12 more, see below) | client |
| 16:48:22.114 | `Connection established after: 938 ms` | client |
| 16:48:22.926 | Host adds the project: `SolutionModel.solutions[0] = Solution(projectId = "6d9b208e-…:3M6rEz4…")` | host |
| 16:48:23.035 | Host info: `productCode = "IU"`, `osName = "Linux"`, `osVersion = "6.1.162-android14-11"` | host |
| 16:48:26.091 | Sets `solutions[0].solutionLifecycle.readyForExtsRegistration = true` | client |
| 16:48:26.158–26.926 | Host creates 16 project-level models (`projectExtrasModel`, `DocumentsOperationModel` at 26.377, `projectViewModel`, `statusBarModel`, …) | host |
| 16:48:26.690 | `solutions[0].isLoaded = true` | host |
| 16:48:27.414 | Calls `fileEditorsModel.requestComposite(fileId = 46836, fileUrl = "file:///data/data/com.termux/files/home/intellij/smoke/src/demo/Main.java")` | client |
| 16:48:29.658 | `DocumentsOperationModel.documents` Add: the document for file 46836 | host |
| 16:48:29.746 | `documents[…].textControls` Add: the editor (`kernelEditorId = "85ji68dcvrnt5a74vu15"`, `kind = EditorTab`) | host |
| 16:48:29.831 | The file's text arrives (see [The editor's data](#the-editors-data)) | host |
| 16:48:30.318 | First syntax highlighters | host |
| 16:48:30.491 | `Thin client starter end` (the window is up) | client |

From TCP connect to a highlighted editor: about 9 seconds against the phone. Most of it is the backend opening the project (16:48:22.9 → 16:48:26.7).

### How a model gets connected: both sides create it

The root models are rd *extensions* ("exts") of one root named `ThinClient`. An ext is connected only when **both** sides create it. Each side announces its own creation on a shared signal:

```
SEND signal `ThinClient.ProtocolExtCreated` value = ExtCreationInfo(rName=ThinClient.CodeWithMeDiagnosticsModel, rdId=null, hash=-9170676275752222606, …)
SEND ext `ThinClient.CodeWithMeDiagnosticsModel` (11394414885316123752) :: Ready
RECV ext `ThinClient.CodeWithMeDiagnosticsModel` (11394414885316123752) :: remote: ReceivedCounterpart
```

`hash` is the model's `serializationHash`. For all 53 exts created by both sides, the client's hash equals the backend's: the model jars must come from the **exact** backend build. A model one side never creates simply stays unconnected; the log shows no error for that (for example `IjLightModel`, `quickDocHostModel`, `hyperlinkHighlightingModel` were created by the host only).

Who created each ext first:

| Created first by the client (at connect, 16:48:22.06–22.33) | Created first by the host |
|---|---|
| `CodeWithMeDiagnosticsModel`, `AsyncActionsModel`, `LoggerModel`, `StartupSequenceModel`, `SshAgentProxyModel`, `VfsModel`, `DistributedTestBridgeModel`, `RemoteSettingsModel`, `LambdaTestBridgeModel`, `FleetRpcModel`, `CodeWithMeConnectionExtModel`, `SettingsModel`, **`SolutionModel`** | `UIAutomationInteractionModel`, `PortForwardingModel`, `LanguagePluginsSyncModel`, **`HighlighterRegistrationHostModel`**, **`LuxModel`**, `NewNotificationsModel`, `JbaInfoModel`, `RemoteSearchableOptions`, `HostInfoModel`, `CodeWithMeUnattendedHostControlModel`, `ShellModel`, **`DocumentsOperationModel`**, `CodeVisionSettingsModel` |
| Later, per project (`solutions[0].…`): **`fileEditorsModel`**, `navigationBarModel`, `ideFrameSynchronizerModel`, `searchEverywhereModel`, `problemFileModel`, `terminalModel`, `debuggerConsoleInfoModel`, `debuggerModel`; plus `ClipboardModel`, `SyncedSettingsModel`, `CefModel`, `UiContextModel` | Per project: `codeWithMeRunConfigurationsModel`, `toolWindowModel`, `projectExtrasModel`, `gatewayCustomizationModel`, `hyperlinkHighlightingModel`, `quickDocHostModel`, `gitModel`, `vcsModel`, `diffViewersModel`, `userFocusModel`, `codeStructureTreeModel`, `backendRunContentModel`, `filterProviderModel`, `cWMExternalSystemModel`, **`projectViewModel`**, `localChangesModel`, `testsRunContentModel`, **`statusBarModel`**, **`editorTabsModel`**, `virtualFilesExtrasModel` |

The gate between the two phases is one property the client sets: `solutions[0].solutionLifecycle.readyForExtsRegistration = true` (16:48:26.091). Of the 20 project-level models the host created first, 16 came in the 0.8 s after it (16:48:26.158–26.926); 2 came before (`codeWithMeRunConfigurationsModel` 16:48:23.708, `toolWindowModel` 16:48:25.163) and 2 with the first editor (`editorTabsModel`, `virtualFilesExtrasModel`, 16:48:29.8). **Inferred:** the host waits for this property before registering most project models.

The client also reports its window: `ideFrameSynchronizerModel.frameSize = LxSize(width = 1728, height = 1084)`, `frameLocation = LxPoint(0, 33)`. An Android client would send its screen size here.

### The editor's data

An open file is a document plus one *text control* per editor showing it, all under `DocumentsOperationModel`:

```
RECV map `ThinClient.DocumentsOperationModel.documents` Add
  key   = RdDocumentIdWithFileId(fileId = RdFileIdNew(backendId = 46836, frontendId = 46836, managingFsCreationTimestamp = 1794673134749))
  value = PatchEngineDocumentModel(textControls = RdMap […], extensions = [MarkupDocumentExtension(…), UndoParametersDocumentExtension(isUndoDisabled = false)],
                                   creationSide = Backend, isWriteThreadOnly = true, uniqueId = "unique-id-backend-0")

RECV map `…documents.[46836].textControls` Add
  key   = KernelAwareTextControlId(kernelEditorId = "85ji68dcvrnt5a74vu15", documentId = …46836, tabIndex = 1, kind = EditorTab, clientId = "64CE2B91…-1794741500033", projectId = …)
```

The document model holds **no text**. The text comes as a patch on `DocumentsOperationModel.requestResponseModel.backendPatches`:

```
RECV signal `ThinClient.DocumentsOperationModel.requestResponseModel.backendPatches`
  value = RdPatchWithMetadata(patch = RdPatch(changes = [RdResetDocumentChange(text = "package demo;\n\npublic class Main {\n    public static void main(final String[] arguments) {\n …")], …))
```

The same signal carries other backend state changes, for example `RdToggleDumbMode(projectId = …, enabled = false)` (dumb mode, IntelliJ's "indexing in progress" state, is off). The client sends its own requests on `requestResponseModel.requestsWithTime` (`RdRequests(requests = [RdRequestHolder(requestId = 0, request = RdNavigationBarRequest())])`) and gets `responsesWithTime` back. **Inferred:** typing goes back the same way, as patches from the client; to be confirmed with a capture that edits text.

Syntax highlighting arrives per text control, as ranges tagged with attribute keys, not colors:

```
RECV signal `…textControls.[85ji68dcvrnt5a74vu15].extensions[1].markup.$.highlightersAdded`
  value = HighlighterList(elements = [HighlighterElement(highlighter = SyntaxHighlighterModel(
            textAttributesKeys = [IdeaTextAttributesKey(externalName = "JAVA_KEYWORD", fallback = "DEFAULT_KEYWORD")],
            layer = 1000, documentVersion = RdDocumentVersion(value = 0, hash = -5446235163934729545), …), …)])
```

The colors for those keys come once, at connect, from the host-created `HighlighterRegistrationHostModel`: `registry.colorsScheme = ColorsSchemeModel(name = "Darcula", attributes = …)` (237 KB in the log).

### Fleet RPC in this session

`FleetRpcModel` carried the most messages: 2,864, with 109 different service methods. Each message is a JSON envelope:

```json
{"type":"envelope","destination":"fleet","origin":"ep375gqi478gvjhs6aom",
 "payload":"{\"type\":\"call\",\"requestId\":\"3tti8bg3n90v1ogttc1b\",\"service\":\"com.jetbrains.rd.platform.codeWithMe.settings.fileBasedSync.FileBasedSettingsSyncApi\",\"method\":\"hasInitialSyncCompletedBefore\",\"args\":{},\"meta\":{\"CompressedVectorClock\":{\"clientId\":\"c49m718s59qtlkbbh93f\",\"consumedTxs\":0,\"sentTxs\":0}},\"streamBudget\":100}"}
```

| Message kind | Client → host | Host → client |
|---|---|---|
| `call` | 765 | — |
| `call_result` | — | 304 |
| `call_failure` | — | 1,226 |
| `stream_init` / `stream_data` / `stream_closed` | 75 / 1 / 1 | 150 / 290 / 52 |

What the calls were for:

- **Failures.** 1,224 of the 1,226 failures are one call repeated: `AIAssistantAgentsApi.agentStatusFlow` → `unresolvedService: "API for com.intellij.ml.llm.chat.shared.rpc.AIAssistantAgentsApi#agentStatusFlow could not be found"` (the client has the AI Assistant frontend; the backend doesn't have the plugin).
- **Plugin management.** `PluginManagerApi.getInstalledPlugins` / `getVisiblePlugins`: the largest replies of the session, 3.2–3.7 MB of JSON each.
- **Editor extras.** These calls name the editor id they got over rd: `IdentifierHighlightingRemoteApi.getMarkupData`, `BackendInlineCompletionRemoteApi.getBackendEvents`, `KubernetesFloatingToolbarRemoteApi.isToolbarApplicable`, `CodeNotesUiRemoteApi.parentSelection`, `InlineProgressIndicatorRemoteApi.getStates`.
- **Settings sync.** `FileBasedSettingsSyncApi.*`.
- **Rhizome DB.** `RemoteKernel.subscribeWithChunkedSnapshot` and `RemoteKernel.transact`, one call each.

The document, editor, highlighting, project tree, status bar and actions all came over rd models, not Fleet RPC. **Inferred:** a first client can skip Fleet RPC and still show a highlighted editor. Whether the backend holds anything back without the Rhizome DB subscription is **Unknown**.

### Lux in this session

The host created `LuxModel`, but only 1 message went out and 3 came in. Nothing was painted through Lux, because no dialog or backend-only tool window was opened. A Lux capture needs a session that opens one (Settings, for example).

### The heaviest rd messages

| Message | Size in the log | Needed for a first client? |
|---|---|---|
| `RemoteSearchableOptions.requestOptions` reply (all searchable setting names) | 20 MB | No: only the client asked for it, for the Settings search |
| `AsyncActionsModel.registerActions` (every action with its presentation) | 5.8 MB | Only for menus and toolbars |
| `HighlighterRegistrationHostModel.registry.colorsScheme` | 237 KB | Yes, for highlight colors |

Sizes are of the log text, which prints every received message twice (`onWireReceived` and `dispatched`), not bytes on the wire.

### What a first client has to do, from this recording

**Confirmed in step 2** ([Our own client](#our-own-client-step-2)), with the markup extensions it adds:

1. Connect: handshake as in [Order of steps](#order-of-steps), then an rd `Protocol` named `ThinClient` over the wire.
2. Create `SolutionModel`, and wait for the host to add `solutions[0]`.
3. Set `solutions[0].solutionLifecycle.readyForExtsRegistration = true`.
4. Create `DocumentsOperationModel`, `HighlighterRegistrationHostModel`, and per project `fileEditorsModel` and `editorTabsModel`. Later also `projectViewModel`, `statusBarModel`, `LuxModel`.
5. Open a file with `fileEditorsModel.requestComposite(fileId, fileUrl)`, or take the editors the host restores. Then read the document text from `backendPatches` and the highlighters from the text control's markup.

Everything else the real client creates (diagnostics, test bridges, SSH agent, settings sync, Fleet RPC) is optional or can come later. That is a guess until a client that skips them still gets an editor.

## Our own client (step 2)

`client/` now holds a Compose Multiplatform desktop (JVM) client that connects to a backend, opens one file and shows it with the backend's syntax highlighting. It reuses JetBrains Client's rd and model jars and writes everything else itself. Tested 2026-11-15 against the phone backend (17:12–17:20 IST) and, after the phone went offline, against the same IntelliJ build on the Mac (17:24–17:31 IST). Everything in this section is **Seen** unless it says otherwise.

What the client does, in order. On the Mac this whole sequence took about 1 second:

| Time (IST) | Step |
|---|---|
| 17:30:38.087 | TCP connect to `127.0.0.1:5990` |
| 17:30:38.145 | TLS 1.3 done; the server's SHA3-256 fingerprint matches `fp` |
| 17:30:38.155–.168 | `TRANSPORT_INDEX` → acceptance → `WIRE_EXIST` → `JOIN_PIN` → `ACCEPTED` → our `ACCEPTED` |
| 17:30:38.179 | `CURRENT_CONFIRMED_FRAME_ACK` exchange (see below) |
| 17:30:38.223 | rd `Protocol("ThinClient", …)` up; our models created |
| 17:30:38.288 | Host adds `solutions[0]`; we set `readyForExtsRegistration = true` |
| 17:30:38.393 | Color scheme `Darcula` received: 213 attribute keys with a foreground color |
| 17:30:38.661 | `VfsModel.findFile(path)` → `RdFileIdNew(backendId = 5055, …)` |
| 17:30:38.830 | `fileEditorsModel.requestComposite(fileId, "file://…/Main.java")` → `SuccessfulRequestComposite` |
| 17:30:39.036–.076 | Document and editor added; text arrives as `RdResetDocumentChange` (260 characters) |
| 17:30:39.119 | 36 highlighters in the editor's markup, 15 of them with a color in `Darcula` (keywords, strings, `;`) |

The models it creates: `SolutionModel`, `HighlighterRegistrationHostModel` (sets `registry = DefaultRegistryModel()`), `DocumentsOperationModel` with `requestResponseModel`, `VfsModel`, and per project `fileEditorsModel` and `editorTabsModel`. Nothing else: no Fleet RPC, no Rhizome DB subscription, no Lux. The backend gave us the editor anyway, which answers the open question from the recording.

### What the recording didn't show, found by failing

| Attempt | Backend said (its `idea.log`, which logs in UTC on the phone) | Fix |
|---|---|---|
| 1 | `protocol error: clientId does not start with client fingerprint` | The certificate's `UNIQUE_IDENTIFIER` is the client id: `<SHA3-256 fingerprint of the per-process CA certificate>-<epoch ms>`, e.g. `C62C256C…514E-1794743186847` (JetBrains Client's `ThinClientId`). Not a UUID. |
| 2 | (connected; then our side failed) | rd's `UnsafeBuffer(256)` is native memory and `getArray()` throws; use `UnsafeBuffer(ByteArray(256))`. `Protocol` calls `startDeliveringMessages()` itself; calling it again throws. |
| 3 | `Declining invalid token from safeuq` → we get `USER_DECLINED` | The join token `jt` works once. After a client has connected with it, a new client id with the same `jt` is refused. Restart the backend, or keep the client id ([step 7](#connect-screen-and-reconnecting-step-7)), for a new attempt. |
| 4 | `Expected to get frame of type: <SystemFrame>, but received: <Message Frame>` | After `ACCEPTED`, both sides send `CURRENT_CONFIRMED_FRAME_ACK` (status 11, int32 = last frame number received from the other side, `0` for a new wire) and read the other's, before any message frame (`ServiceMessageProcessor.resendUnconfirmedFrames`). |
| 5 | `null cannot be cast to non-null type T of …ProtocolMarkupExtensionsKt.getExtension`, `lineMarkerActionModel is null`; no highlighters | Every markup model the client creates must carry the extensions JetBrains Client puts there, looked up by key (`TOOLTIP_PROVIDER_MODEL_KEY`, `LINE_MARKER_ACTIONS_KEY`, `MARKUP_REVALIDATION_KEY`, … 20 for an editor, 13 for a document). See `client/src/main/kotlin/intellijandroid/client/session/MarkupExtensions.kt`. |

The client creates three markup models: one for the editor (`textControls[…].extensions[MarkupTextControlExtension].markup`) and two for the document (`markups[RdMarkupKey(null)]` and `markups[RdMarkupKey(projectId)]`). Syntax highlighting arrives in the editor's model. Errors and warnings from code analysis ("daemon" results) are expected in the per-project document model (**Inferred**; the client doesn't show them yet).

The colors: the backend sends **every** installed scheme on `registry.colorsScheme` (`Default`, `Darcula`, `Light`, `Islands Dark`, `Dark purple`, … 9 on this build). The client picks one by name. Highlighters carry attribute keys, not colors (`JAVA_KEYWORD` with fallback `DEFAULT_KEYWORD`). Look a key up in the scheme, then its fallbacks.

### Running a backend on the Mac for client work

The Mac's IntelliJ IDEA (`~/Applications/IntelliJ IDEA.app`) is the same build, `263.7307.200` IU, so the protocol jars match. Two traps:

- **It must not share folders with a running desktop IntelliJ.** On the first try, `remote-dev-server.sh` didn't start a backend: the launcher found the running IDE and handed it the command (`External instance command received` → `UnattendedHostUnavailableStarter`; the script printed `The command 'remoteDevHost' requires Remote Development backend`). `IDEA_PROPERTIES` with separate paths did **not** prevent this. Passing the paths as JVM options did: `IJ_JAVA_OPTIONS="-Didea.config.path=… -Didea.system.path=… -Didea.log.path=… -Didea.plugins.path=…"`. Check the first lines of the new `idea.log` before connecting. (The desktop IDE shut down normally a minute after that first try; there was no exit in the handed-over command, but the two may be related.)
- **A fresh config doesn't trust the project.** The project open stops at a trust prompt that our client can't show yet. Set `REMOTE_DEV_TRUST_PROJECTS=1`, and `REMOTE_DEV_NON_INTERACTIVE=1`.

```sh
BACKEND=/path/to/scratch/mac-backend      # holds config/ system/ log/ plugins/ and the project
REMOTE_DEV_TRUST_PROJECTS=1 REMOTE_DEV_NON_INTERACTIVE=1 \
IJ_JAVA_OPTIONS="-Didea.config.path=$BACKEND/config -Didea.system.path=$BACKEND/system -Didea.log.path=$BACKEND/log -Didea.plugins.path=$BACKEND/plugins" \
  "$HOME/Applications/IntelliJ IDEA.app/Contents/bin/remote-dev-server.sh" run "$BACKEND/smoke" --listenOn 127.0.0.1 --port 5990
```

Running the client: see `client/README.md`.

### Not done yet

- Editing: typing goes back to the backend; the format is still open (see the questions below). `RdCaretStateSnapshot` and other patch items are logged and ignored.
- Daemon results (errors, warnings, the grey "unused" variable), the project tree, tabs, Lux.
- The real client's `HighlighterRegistrationHostModel` also gets `demoTextTagsMap`, `colorSettingsPage` and `severities`; ignored.
- No resend after a reconnect: a dropped connection ends the session (reconnecting with the same identity: [step 7](#connect-screen-and-reconnecting-step-7)).

## Project tree and editor (step 3)

The client now shows IntelliJ's Project tree and a read-only editor with the backend's full highlighting. Tested 2026-11-15 against the Mac backend; recorded from the real client the same day (`protocol.*` trace, 17:49 IST). **Seen** unless marked.

### The Project tree

The tree is `solutions[0].projectViewModel` (the client creates this ext too). Its `panes` list holds one entry per view; the Project view is the `RdProjectViewAsyncPane` with `id = "ProjectPane"`. The client sets `projectViewModel.currentPane = "ProjectPane"`.

Nodes arrive in the pane's `items` map, keyed by an int id:

```
RECV map `…projectViewModel.panes.[0].items` Add :: key = 1 :: value = RdProjectViewItem (
  parentId = <null>
  descriptor = RdProjectViewItemDescriptor (
    presentation = [ RdColoredFragment ( text = "smoke", attributes = RdSimpleTextAttributes ( fgColor = <null>, style = 1 ) ) ]
    icon = RowIconModel ( subNodes = [ ImageIconModel ( id = ImageIconModelId ( path = "expui/nodes/module.svg", plugin = "com.intellij" ), width = 16, height = 16 ) ] )
    hasChildren = true, isDirectory = true, sortKey = -1
    path = "/…/mac-backend/smoke"
  ) )
```

| Part | Meaning |
|---|---|
| `parentId` | `null` for top-level nodes (`smoke` -1, `External Libraries` 0, `Scratches and Consoles` 1 = `sortKey`) |
| `presentation` | label fragments; `style` is `SimpleTextAttributes` bits (1 bold: the project root is bold), `fgColor` an ARGB int or null |
| `icon` | `ImageIconModel` with a path in `intellij.platform.icons.jar`; `RowIconModel` = several icons; `DeferredIconModel` = a placeholder (`baseIcon`) replaced later by an item update |
| `expand` | `RdCall<Int, Unit>`: the backend adds the children to `items` (with `parentId` = the node) before it answers, then fires `childrenLoaded(id)` |
| `selection` | `IProperty<Int>`: the client sets it when the user selects a row |
| `navigate`, `navigateByPath`, `expandByPath`, `dnd` | not used yet; the client opens files itself (`VfsModel.findFile` + `requestComposite`) |

Collapsing is client-only. IntelliJ shows the project root expanded at start; the real client calls `expand(1)` itself.

### Highlighting in the editor

Two markup models carry highlighters for an open file, and the client creates both (with the extensions in `MarkupExtensions.kt`):

| Markup | Filled with | Example |
|---|---|---|
| the editor's (`textControls[…].extensions[MarkupTextControlExtension].markup`) | lexer highlighting, layer 1000 (`SyntaxHighlighterModel`, several keys per range) | `JAVA_KEYWORD`, `JAVA_STRING`, `JAVA_SEMICOLON` |
| the document's per project (`documents[…].extensions[MarkupDocumentExtension].markups[RdMarkupKey(projectId)]`) | code analysis: semantic colors (layer 3000), unused (4000), warnings, errors (5000), inspections; also indent guides (`IndentGuideHighlighterModel(indentLevel)`) | `METHOD_DECLARATION_ATTRIBUTES` on `main`, `STATIC_FINAL_FIELD_ATTRIBUTES` on `out`, `NOT_USED_ELEMENT_ATTRIBUTES` on `unused`, `DEBUGGER_SUGGESTION_ATTRIBUTES` ("'System.out.println()' call can be replaced with a logpoint") on `println` |

**Code analysis runs only for an editor the backend thinks is selected and visible.** Without the following, the per-project markup stays empty:

| Message (client → backend) | Value |
|---|---|
| `fileEditorsModel.selectionChanged` | `FileEditorManagerEventModel(origin = Frontend, oldEditorId = <previous or null>, newEditorId = RdFileEditorId(compositeId, "text-editor", 1))`; `compositeId` is the `SuccessfulRequestComposite` answer of `requestComposite` |
| `fileEditorsModel.backendDeferredComposites[compositeId].model.$.visible` | `true` (the backend adds the entry; the client sets `visible` on the composite it shows) |
| `textControls[…].visibleChangedFromFrontend` | `true` |
| `textControls[…].focusChangedFromFrontend` | `true` |
| `textControls[…].visibleRange` | `VisibleRange(startOffset, endOffset, collapsedRegions = [])` (the client sends the whole document for now) |

Highlighters carry attribute keys, not colors. The backend sends each color scheme with only its **own** entries: "Islands Dark" has 154 attribute entries and 62 colors; the rest come from its parent "Darcula", sent separately. The client resolves like IntelliJ: for each key, Islands Dark, then Darcula, then the key's fallback (`JAVA_KEYWORD` → `DEFAULT_KEYWORD`), repeat. Scheme colors (`CARET_ROW_COLOR` `#1F2024`, `LINE_NUMBERS_COLOR` `#4B5059`, `SELECTION_BACKGROUND`, `INDENT_GUIDE`, …) resolve the same way. The protocol doesn't say which scheme is a scheme's parent; the client hard-codes Islands Dark → Darcula.

Look and metrics (line height 21 px for JetBrains Mono 12 at spacing 1.2, gutter widths, tree row 22 px, indent 19 px, selection radius 4, Islands panels): [`intellij-look.md`](intellij-look.md).

### Backend on the Mac: JDK

The copied phone project names its JDK `openjdk-21`; the Mac backend's fresh config has no such JDK, so code analysis marks `String` and `System` as `WRONG_REFERENCES_ATTRIBUTES` (red). Registering `openjdk-21` in the backend's `config/options/jdk.table.xml` (home + one `jrt://<home>!/<module>` class root per module) while the backend is stopped fixes it; the JDK is scanned about 6 s after start (28,689 files). A client that connects before that gets the unresolved results and, so far, no re-analysis (**Unknown** why: real IntelliJ re-analyses after indexing). The client's test runs wait for `Number of scanned files` in the backend log before connecting.

### Not done yet

- ~~Typing~~, ~~caret position from the backend~~, ~~gutter icons~~: done in [Editing (step 4)](#editing-step-4).
- Folding, breadcrumbs, scroll bars.
- Re-analysis after the backend finishes indexing (see above).

## Editing (step 4)

The editor now takes typing, Enter, Backspace, Delete, caret moves, selections (Shift+arrows, mouse drag, Cmd+A), undo and redo. Tested 2026-11-15 against the Mac backend; the real client was recorded typing the same day (`protocol.*` trace). The full message spec is [`edit-protocol.md`](edit-protocol.md); in short:

| Step | Message |
|---|---|
| The editor opens | `backendPatches` sends `RdResetDocumentChange(text, v0)` and an `RdCaretStateSnapshot`. Their versions are the starting versions |
| Type a character | change the text locally, then `RdTypeKeyRequest(char, editorId, patch)` on `requestResponseModel.requestsWithTime` |
| Enter, Backspace, Delete | `RdCallEditorActionRequest("EditorEnter" / "EditorBackSpace" / "EditorDelete", editorId, patch, timestampSetModel)` |
| Move the caret or select | `RdApplyPatchRequest` with one `RdCaretStateChange` |
| After every edit | an empty `RdApplyPatchRequest` command with `isTransparent = true`, as the real client sends |
| Undo, redo | `RdUndoRedoRequest(fileEditorId, [fileId], undoSizes, redoSizes, isUndo)`, no local change |
| The answer | `responsesWithTime`: `RdSuccessResponse(patch, requestId)`. Same changes as ours: keep the local text. Different: roll back every pending local edit, apply the backend's patch |

Each patch carries `expectedItemVersions` (the versions before) and `updatedItemVersions` (after):

| Version | Example (260-char `Main.java`, Enter at 210) |
|---|---|
| document | `RdDocumentVersion(0, -5446235163934729545)` → `RdDocumentVersion(1, -6257571537734692227)`; the hash folds `offset, moveOffset, oldLength, oldText, newText` into the previous hash (`h * 31 + v`, signed 64-bit) |
| editor | `RdEditorVersion(caretModelVersion, documentHash, caretHash)`; `caretModelVersion` goes up by 2 per edit and by 1 per caret move; caret at 210 → `caretHash` 122288 |
| command | `RdCmdItemVersion(RdCmdVersion(-3))`: the id of the previous command; ids count down from -2 |

Gutter icons come in the per-project markup as `GutterMarkHighlighterModel` (the run triangles on lines 3 and 4 of `Main.java`, icon `expui/gutter/run.svg`). Icons can also be `CompositionIconModel(subNodes)`, drawn on top of each other.

### Not done yet

- Smart typing locally (closing brackets, smart backspace): the backend does it and the client follows, so the text jumps once the answer arrives.
- Multiple carets, folding, copy and paste. (Soft keyboard: done in [The Android app (step 5)](#the-android-app-step-5).)
- Indent guides: `IndentGuideHighlighterModel` never arrived in the tests.

## The Android app (step 5)

`client/android/` builds the client as an Android app from the same sources as the desktop client (`client/src/main/kotlin`). Kotlin Multiplatform can't share one source set between a JVM target and an Android target, so the app is a separate Gradle build that adds that folder to its sources. Tested 2026-11-15 21:08–21:11 IST on the Android emulator (Pixel 3a image, API 34) against the Mac backend, through `adb reverse tcp:5990 tcp:5990`: the client connects, shows the Project tree and the editor with all highlighting, expands folders, and takes typing from the soft keyboard. **Seen** unless marked.

What the build does with JetBrains Client's files (`client/android/build.gradle.kts`):

| Input | Done at build time | Why |
|---|---|---|
| the five protocol jars | class files above version 61 get version 61 | D8 rejects 69 ([Problem 2](#problem-2-class-file-version-69)); not checked whether AGP 9.5's D8 still does |
| `intellij.libraries.rd.framework.jar` | `UnsafeBuffer`'s 105 `sun.misc.Unsafe` uses are rewritten to `ArtUnsafeShim`, and the shim is added to the jar | [Problem 1](#problem-1-sunmiscunsafe-on-art); the shim is compiled against the JDK because `android.jar` has no `sun.misc.Unsafe` |
| `splitMarshallers.txt`, `marshallers.txt` | kept in the jars; AGP packages them as Java resources | [Problem 3](#problem-3-resource-files) |
| JetBrains Mono, Inter, `intellij.platform.icons.jar` | packaged as assets | the desktop client reads them from the install |

Platform pieces in the app, all in `client/android/src/main/kotlin/`:

| Piece | How |
|---|---|
| Icons | AndroidSVG draws each SVG into a bitmap at the screen's density. Before drawing, the SVG's `width="16"` must be changed to `100%`: with an absolute size AndroidSVG draws 16 px, about a third of 16 dp on a 2.75× screen |
| Soft keyboard | a `PlatformTextInputModifierNode` on the editor opens an input connection with `inputType = TYPE_NULL`. Gboard then sends key events (`EditorInfo inputType=0x0(NULL)` in its log), which reach the editor's key handler like a hardware keyboard's. No suggestions or composing text |
| Layout | landscape only; the 300 dp Project tree leaves no room in portrait |

Found while testing:

- Gboard's Enter after `System.out.println(greeting);x` gave a new line indented by 12 spaces, not 4: the backend applies IntelliJ's continuation indent to the unfinished statement, and the client follows its answer ([edit-protocol.md §11](edit-protocol.md#11-checked-with-our-client-against-the-mac-backend-2026-11-15)).
- The keyboard covers the lower half of a landscape screen. The editor now scrolls the caret's line into view whenever the caret moves or the view's height changes. The same code runs on the desktop, where it is not tested yet.
- AGP 9.5 needs Gradle 9.6 and, with the 2026.09 Compose BOM, `compileSdk 37`; dex merging the model jars needs more than Gradle's default 512 MB heap.

### Not done yet

- Not run on a real phone yet. On the phone the backend runs in Termux on the same device, so its join link (`tcp://127.0.0.1:5990#…`) should work as printed (**Inferred**).
- A layout for portrait screens, touch selection handles, pinch zoom, copy and paste.

## Code completion (step 6)

The editor shows IntelliJ's completion popup: it opens by itself after a typed letter or `.`, and with Ctrl+Space. Typing narrows it, Up/Down move the selection, Enter or Tab inserts, Esc closes, and a tap or click on a row inserts that row. Tested 2026-11-15 against the Mac backend, after recording the real client doing the same (`protocol.*` trace, 22:13–22:16 IST). Full message reference: [`completion-protocol.md`](completion-protocol.md). **Seen** unless marked.

Completion is the first feature here that doesn't run over rd models but over **Fleet RPC**: JSON messages carried as strings on the rd signals `FleetRpcModel.outgoing` / `incoming` ([Fleet RPC, carried by one rd model](#fleet-rpc-carried-by-one-rd-model)). The client opens three streams on `RpcCompletionService` once per connection:

| Call | Direction | Carries |
|---|---|---|
| `autopopupRequests(projectId)` | backend → client | "open the popup now" after a typed letter: `{"editorId":{"id":"70vmfjiscs4p4bgvtqfk"},"completionType":"BASIC","caretModelVersion":9,"documentVersion":4}` |
| `commandFlow(commands)` | client → backend | `OpenSession`, `StartRequest`, `PrefixChanged`, `LookupEvent` (selection, item chosen, cancel), `CloseSession` |
| `responseFlow()` | backend → client | per session: `NewItems`, `NewArrangement` (order and which items match), `CompletionItemsFinished`, `CompletionFinished` |

The ids come over rd, so no Rhizome database is needed:

| Fleet RPC id | Taken from | Example |
|---|---|---|
| `editorId` | `KernelAwareTextControlId.kernelEditorId` of the editor's rd text control | `70vmfjiscs4p4bgvtqfk` |
| `projectId` | `Solution.solutionOpenStrategy` → `RdOpenSolution.description` → `RdCodeWithMeProject.kernelProjectId` | `m3nniruk9k7j4qsibjui` (rd project id: `23888a6f-…:3M6rEz4vrG2nGEN0sL75lPcBKBz`) |

A request names no offset or text: `StartRequest` carries the editor's `caretModelVersion` and the document's version value, and the backend uses its own copy of the editor. The call `meta` carries `PatchEngineSynchronizationData.lastQueuedRequest`, the id of the last rd request, so the backend applies the typed text before it answers.

Choosing an item is two messages, in this order: `ItemSelected` on `commandFlow`, then an ordinary rd editor action `EditorChooseLookupItem` whose patch inserts the rest of the word (prefix `gre`, item `greeting`: `change(218, +"eting")`). The backend runs the item's insert handler itself; for `length()` its answer adds `(` and `)` and moves the caret between them, and the client follows ([Editing (step 4)](#editing-step-4)).

### Not done yet

- Ctrl+Space a second time (more results) is sent but not checked; smart and class-name completion are not bound to keys.
- Characters that insert the selected item and then themselves (`.` or `(` in a focused popup) close the popup instead.
- The documentation popup beside the list, the "press '.' to show commands" hint and the footer's "Next Tip" are not shown.
- On Android: no Ctrl+Space on the soft keyboard; the popup opens by itself, and rows are tapped.

## Connect screen and reconnecting (step 7)

The Android app opens on a connect screen: the link prompt, backends running on this phone, and a history of connections. Tapping a history entry connects again with the same join link, also after the app was closed. What the screen does and the `intellij-backend` contract it calls: [`connections.md`](connections.md). Tested 2026-11-16 on the emulator and the desktop client against the Mac backend. **Seen** unless marked.

**Same join token, same client id.** The backend accepts a `jt` only from the client id that first used it ([attempt 3](#what-the-recording-didnt-show-found-by-failing)). So a history entry stores the client identity the backend accepted (CA key and certificate, leaf certificate, client id), and a reconnect presents it again. Two things are different from a first connection:

| Step | First connection | Same identity again, e.g. after an app restart |
|---|---|---|
| `TRANSPORT_INDEX` we send | `0` | higher than the last one; the client sends seconds since 1970, e.g. `1794804094` |
| backend's answer to index `0` | accepted | `Transport index is outdated or already set` → `DECLINED` (the backend counts transports per client id: `Current transport index: <0>`) |
| `WIRE_EXIST` from the backend | `0` | `1`: it kept the dropped wire (`Suspend wire for client … in the storage`) |
| our `WIRE_EXIST` | `0` | `0`: we have nothing to resume |
| backend then | `New Wire` | `Remote wire exist for client: false` → `Terminate wire` (the old one) → `New Wire`; app and project sessions for that client id are replaced (`already registered and will be replaced`) |

After that the session works as a new one: Project tree, editor, highlighting, typing.

**Except the autopopup.** On a reconnected client id, the backend closes `autopopupRequests` right away, every time it is called: `producerCancelled: … CancellationException: cancelled with reason`. The cause is in the backend (`BackendAutopopupScheduler`, `intellij.platform.completion.backend.split.jar`): `getRequestFlow()` is `channel.consumeAsFlow()` on one channel per client id, and a `consumeAsFlow` channel can be collected once. When the old connection's collector ended, the channel was cancelled with it. **Inferred** from the bytecode plus the log; JetBrains Client never meets this, because a restarted JetBrains Client makes a new client id (and a new `jt` is needed). The client handles it: when that stream closes, it asks for the autopopup itself after a typed letter or `.` (`StartRequest` with `invocationCount` 0 once the edit is answered). The backend's typed handler also skips the autopopup in strings and comments; the client relies on the backend answering with no items there.

The desktop client stores its identity too when started with `CLIENT_IDENTITY=<file>` (see `client/README.md`).

### Not done yet

- SSH history entries (the third kind in `connections.md`).
- The Termux entries are built against the `intellij-backend` contract; the command doesn't exist yet, so they are untested.
- No automatic reconnect: when the connection drops, the app goes back to the connect screen, and a tap on the history entry reconnects.
- A history entry keeps the client's private key in the app's private storage. It is only useful with that backend's `jt`, until the backend restarts.

## Plan for the client that follows from this

1. **Reuse:** rd-core, rd-framework (with `UnsafeBuffer` patched), rd-text, and the two generated model jars of the **exact** backend build (class version lowered, resource files kept). Plus kotlin-stdlib and kotlinx-coroutines.
2. **Write ourselves:** the connection layer (TLS with client certificate and fingerprint check → DEFLATE → frames → handshake → sequence numbers → `IWire`). It's small and fully described above.
3. **Then** create the root models over that wire in the order of [What a first client has to do](#what-a-first-client-has-to-do-from-this-recording), starting with what the editor needs, and render Lux for everything else.

## Open questions

| Question | How to answer it |
|---|---|
| ~~How does the backend check the client certificate?~~ | Answered in step 2: `PSEUDONYM` = `jt` plus a client id starting with the CA's fingerprint; no product-info extension needed. |
| ~~Does the backend accept an empty `ACCEPTED` from us?~~ | Yes (`Peer made no negotiation offer, skipping`). |
| ~~Which rd models does the client have to bind first, and in what order?~~ | Answered by [A recorded session](#a-recorded-session). |
| ~~Does the host give a client an editor if it creates only the editor's models (no Fleet RPC, no Rhizome DB)?~~ | Yes: [Our own client](#our-own-client-step-2). |
| ~~How does typing travel?~~ | Answered in [Editing (step 4)](#editing-step-4): requests with patches on `requestResponseModel`. |
| What does Lux traffic look like when a dialog opens? | A capture that opens Settings. |
| rd message context bytes | rd library source (JetBrains/rd, public), or capture. Not needed while we reuse the library. |
| Is the byte-by-byte `copyMemory` shim fast enough? | Measure once real traffic flows. |
| Does a newer D8 accept class version 69 directly? | Try the latest build-tools / R8. |

## Sources

All from JetBrains Client 263.7307.200 on the Mac:
`~/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-89c5c2813f.ide.d/IntelliJ IDEA.app/Contents/`

| Jar | Read for |
|---|---|
| `lib/intellij.libraries.rd.{core,framework,text}.jar` | rd library; `javap`, `jdeps`, runs on the phone |
| `lib/intellij.platform.split.protocol.jar`, `lib/intellij.rd.ide.model.generated.jar` | generated models, marshaller lists, Lux and Fleet RPC models |
| `plugins/remdev-plugin/lib/modules/intellij.platform.split.connection.jar` | transport, TLS, compression, frames, wire (decompiled with Fernflower from IntelliJ's `java-decompiler.jar`) |
| frontend and base split-connection modules, `lib/intellij.rd.platform.jar` | client certificate, trust manager, fingerprint, `tcp://` handler, `ThinClientConnectionFactory` (bytecode) |
| `plugins/remdev-plugin/lib/modules/intellij.platform.rpc.split.jar` | Fleet RPC over rd (`RdTransportFactory`, `FleetRdTransportBridge`) |
| `lib/fleet.rpc.jar` | `TransportMessage` JSON serialization |

Class and method names are given above rather than line numbers, because Fernflower's line numbers change between runs and versions.
