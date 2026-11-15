# Code completion over Fleet RPC (JetBrains Client 263.7307.200, IntelliJ split mode)

Read from the decompiled client jars and checked against a recorded session (2026-11-15). Our implementation: `client/src/main/kotlin/intellijandroid/client/session/FleetRpc.kt` and `Completion.kt`; results in section 6.

Tags: **[Seen]** = read in decompiled code or bytecode (class/method named). **[Inferred]** = reasoned from code, the backend side is not in the client jars.

Short answer to "does completion need Rhizome/kernel DB entities?": **No for the payloads. EditorId and ProjectId are plain UID strings that arrive over rd.** Details in section 5.

---------------------------------------------------------------------------------------------------

## 4 (first, because everything else rides on it). Fleet RPC wire rules

### 4.1 Transport: rd signals carrying JSON strings
- rd ext model `FleetRpcModel` (`com.jetbrains.rd.ide.model.FleetRpcModel`, serializationHash -594712100210095910) has two async signals named `incoming` and `outgoing`, each of type `FleetTransportMessage { message: String }`. [Seen: FleetRpcModel bytecode]
- `FleetRdTransportBridgeManager.getOrCreateTransportBridge`: for `IdKind.Client` it reads `model.incoming` and writes `model.outgoing`; for `IdKind.Server` it is the other way round. [Seen]
- Each signal value's `message` is the JSON text of a `TransportMessage` (class `fleet.rpc.core.TransportMessage`). `RdTransportFactory` does `TransportMessage.deserialize(request.message)` on receive and `FleetTransportMessage(request.serialize())` on send. [Seen]

### 4.2 TransportMessage (outer envelope)
Sealed class, discriminator key `"type"`. Encoded with `Json.Default` (defaults omitted). [Seen: TransportMessage, RpcMessage.seal]

| type | fields |
|---|---|
| `envelope` | `destination: UID`, `origin: UID`, `payload: String` (the RpcMessage as a JSON string) |
| `opened` | `address: UID` (route opened; sent by host side, `RpcClient` just reacts) |
| `closed` | `address: UID` (route closed; pending calls on that route fail with `RouteClosedException`) |

UID = JSON string matching `^[A-Za-z0-9_-]{1,36}$`, case sensitive. `UID.random()` = 20 chars from the alphabet `0123456789abcdefghijklmnopqrstuv` (13 random bytes in base32). Example: `"85ma8o6plbbp314b5hig"`. [Seen: fleet.util.UID]

Values for origin/destination:
- Client to host: `destination = "fleet"` (the host's route; the literal is not in the client jars, but it is in your captured traffic), `origin = <your own random UID>`. Pick one UID per connection and use it for every message. [Seen in captured sample; the constant itself Inferred]
- Host to client: server code does `message.seal(clientId, route)`, so `destination = <your origin>`, `origin = host route` (should be `"fleet"`). [Seen: RpcExecutor uses `.seal(clientId, this.route)`; value "fleet" Inferred]
- Streams: stream messages use the same two fields; the sender puts the peer route in `destination` and itself in `origin` (`serveStream$transportMessage`, `RpcMessage.seal(route, origin)`). For a client that means `destination="fleet"`, `origin=<your origin>` on every stream_* message too. [Seen/Inferred]

Example envelope (as in your capture):
```json
{"type":"envelope","destination":"fleet","origin":"85ma8o6plbbp314b5hig",
 "payload":"{\"type\":\"call\",\"requestId\":\"agb7csle4gvaa1ki7ceu\",\"service\":\"com.intellij.platform.completion.common.split.RpcCompletionService\",\"method\":\"responseFlow\",\"args\":{},\"streamBudget\":100}"}
```
(`meta` is optional, see 4.6.)

### 4.3 RpcMessage (the payload string)
Sealed class `fleet.rpc.core.RpcMessage`, discriminator `"type"`, `@SerialName` values below. The payload string is produced with `Json.Default` (so default values are omitted, unknown keys not an issue on decode because RpcMessage fields are fixed). [Seen: RpcMessage.seal -> `Json.Default.encodeToString`]

| type | fields (required unless noted) | notes |
|---|---|---|
| `call` | `requestId: UID`, `service: String` (InstanceId, plain string = API FQN), `method: String`, `args: {paramName: JsonElement}`, `meta: {string: JsonElement}` (optional, default `{}`), `streamBudget: Int` (optional, default 0) | `args` keys are the Kotlin parameter names of the method |
| `call_result` | `requestId`, `result: JsonElement`, `meta` (optional) | for a Flow return, `result` is the stream id string |
| `call_failure` | `requestId`, `error: FailureInfo` | FailureInfo = object with 8 optional strings: `authenticationError, securityError, requestError, transportError, producerCancelled, conflict, unresolvedService, serviceNotReady` |
| `cancel_call` | `requestId` | client abandons a call |
| `stream_init` | `streamId: UID` | sent by BOTH ends when they register their side of a stream |
| `stream_data` | `streamId`, `data: JsonElement` | one stream element |
| `stream_next` | `streamId`, `count: Int` | consumer gives the producer `count` more messages of budget |
| `stream_closed` | `streamId`, `error: FailureInfo` (optional) | end of stream or cancel, either direction |
| `resource_consumed` | `resourcePath: InstanceId` | only for RemoteResource returns; not used by completion |

requestId format: any UID (20 char base32 from `UID.random()` is what the real client uses). [Seen]

### 4.4 Inner JSON (args, results, stream elements)
The argument values, results and stream elements are `JsonElement`s created with the RPC Json (`SerializationKt.rpcJsonImplementationDetail`). Its settings: [Seen: `SerializationKt$RpcJson$2$1`]
- `classDiscriminator = "type"`
- `encodeDefaults = true` (the real client writes default fields; you may omit them when the field has a default)
- `ignoreUnknownKeys = false` (the host rejects unknown keys: do NOT send extra fields)
- `allowStructuredMapKeys = true`
- explicitNulls is the kotlinx default (true): nullable fields WITHOUT a default must be present as `null`.
- Sealed classes: `{"type":"<serial name>", ...fields}`. Serial name of a nested class without `@SerialName` is the full dotted name (e.g. `com.intellij.platform.completion.common.protocol.RpcCompletionCommand.StartRequest`). Objects (`Truncate`, `Backend`) encode as `{"type":"..."}` only.
- Enums encode as the constant name string (`"BASIC"`). `Char` as a 1-char string (`"a"`). `Int`/`Long` as JSON numbers. `Unit` as `{}`.

### 4.5 Streams (Flow arguments and Flow results)
A `Flow<T>` / `ReceiveChannel<T>` in a signature is transferred as a **stream id string** (a UID) at the position of the value. [Seen: `ReceiveChannelSerializer.toData` creates `UID.random()` and registers a `StreamDescriptor`; `fromData(uid)` registers the receiving end]
- Flow argument (client to host), e.g. `commandFlow`: `"args":{"commands":"b4a6dh7tbi3ojtkuu02q"}`. The client generates the UID. [Seen: signature `RemoteKind.Flow` for parameter `commands`]
- Flow result (host to client), e.g. `responseFlow`: `call_result.result` is a JSON string holding the stream id, e.g. `"result":"c9p2hj1qkm4n0a7b8d3e"`. The host generated it. [Seen: `RpcExecutor` registers result streams after the call returns; `FlowSerializer`]

Protocol for a stream (from `fleet.rpc.core.RpcStreamKt.serveStream` bytecode):
- Producer side (sends elements): sends `stream_init{streamId}`, then for each element waits until it has budget (`Budget.withdrawSuspend`), sends `stream_data{streamId,data}`. When the budget hits 0 it waits for `stream_next`. At the end sends `stream_closed{streamId}` (with `error` on failure). Initial budget = the `streamBudget` of the `call` that created the stream. [Seen]
- Consumer side (receives elements): sends `stream_init{streamId}`; then sends `stream_next{count = prefetch.streamStarted() - producerInitialBudget}` only if that is > 0. `Default` prefetch strategy: `streamStarted() = 100`, and after each received element, if `remaining < 10` it sends `stream_next{count:100}`. [Seen: `PrefetchStrategy.Default`, `serveStream$4`]
- Consequence: send the call with `"streamBudget":100`. Then no initial `stream_next` is needed in either direction (100 - 100 = 0). **A minimal client must still:** (a) as consumer, count received `stream_data` and send `stream_next{streamId,count:100}` when fewer than 10 of the budget remain; (b) as producer, stop after 100 sent `stream_data` messages until the host sends `stream_next`, then add `count` to the budget. Otherwise a long session stalls after about 100 events. [Seen/Inferred: budget semantics read from Budget.withdraw/refill and server `StreamNext` handler -> `budget.refill(count)`]
- If a `stream_init` arrives for an unknown stream the host answers `stream_closed`. Unknown `stream_data` is dropped with a debug log. [Seen: RpcExecutor]
- Cancel: consumer cancels with `stream_closed{streamId, error: toFailureInfo(CancellationException("Cancelled from user side"))}`; call cancel is `cancel_call{requestId}`. [Seen]
- Order that works for a Flow argument call (matches the real client): send `call` (carrying the stream id) first, then `stream_init`, then `stream_data` elements. The host registers argument streams when it receives the `call`, before it runs the method. [Seen: RpcExecutor registers arg streams at call receipt, line ~310]

### 4.6 meta
- `meta` is a free `Map<String,JsonElement>` added by interceptors. [Seen]
- `CompressedVectorClock` = `{"clientId":"<UID>","consumedTxs":<Long>,"sentTxs":<Long>}` (class `fleet.util.CompressedVectorClock`). Added by `ClientCausalityRequestInterceptor.withClock` **only if a Rhizome `DbContext` is bound and `RebaseLoopKt.getClientClock(db())` is non-null**, and never when `WithoutCausalityContextElement` is in the coroutine context. Otherwise the call goes out without it. So the meta key is optional in the client. [Seen] Whether the HOST accepts a call without it, or waits on it, is **[Inferred]**: very likely it accepts (the client code path without a DB sends none), but the server interceptor is not in the client jars. Plan B if the host stalls: send `{"clientId":"<your origin>","consumedTxs":0,"sentTxs":0}`; I could not verify the host treats zero clocks as "no wait".
- On the way back, `interceptCallResult` reads `result.meta["CompressedVectorClock"]` and waits until the local DB has caught up with it (`CausalsKt.await`). A client without Rhizome can ignore result meta. [Seen]
- `PatchEngineSynchronizationData` = `{"lastQueuedRequest": <Long>}` (`com.jetbrains.rd.platform.codeWithMe.patchEngine.PatchEngineSynchronizationData`, meta key constant `PatchEngineSynchronizationDataKey = "PatchEngineSynchronizationData"`). Set by `PatchEnginePlatformRpcRequestInterceptorExtension` from `FrontendAsyncRequestExecutor.getLastQueuedRequest()` (fallback -1 when no frontend session). In `FrontendAsyncRequestExecutorOld` it is an `AtomicLong(-1)`; every rd async request queued gets `id = incrementAndGet()` (so first request id is 0). So it is the id of the latest rd request your client has queued, `-1` if none. It tells the host "before running this RPC, make sure you have applied my rd requests up to this id" (document edits/caret moves). [Seen: field and increment. Meaning Inferred]. Send it if you send document edits over rd; with a read-only/no-edit client send `-1` or omit.
- `ij.execution.initiator` = `"USER"` (also `"MCP"`, `"LS"` exist). Added by `ExecutionInitiatorRpcRequestInterceptorExtension` when the coroutine context carries an `ExecutionInitiatorElement`. Optional. [Seen]

### 4.7 Minimal call sequences (examples)
All `payload` shown unescaped for reading; on the wire it is a string.

1. Open the response stream (once per connection):
```json
{"type":"call","requestId":"agb7csle4gvaa1ki7ceu","service":"com.intellij.platform.completion.common.split.RpcCompletionService","method":"responseFlow","args":{},"streamBudget":100}
```
host replies `{"type":"call_result","requestId":"agb7csle4gvaa1ki7ceu","result":"<streamIdR>"}`; you send `{"type":"stream_init","streamId":"<streamIdR>"}`; the host sends `stream_init` and `stream_data`.
2. Open the command stream (once per connection):
```json
{"type":"call","requestId":"x1...","service":"...RpcCompletionService","method":"commandFlow","args":{"commands":"b4a6dh7tbi3ojtkuu02q"},"streamBudget":100}
{"type":"stream_init","streamId":"b4a6dh7tbi3ojtkuu02q"}
{"type":"stream_data","streamId":"b4a6dh7tbi3ojtkuu02q","data":{ ...RpcCompletionCommand... }}
```
The `commandFlow` call result (`{}`) only comes back when the stream ends (the method suspends while collecting). [Inferred from `suspend fun commandFlow(commands: Flow<...>): Unit`]
3. Autopopup subscription: `method:"autopopupRequests","args":{"projectId":{"id":"<projectUid>"}},"streamBudget":100`, result is a stream id.

---------------------------------------------------------------------------------------------------

## 1. RpcCompletionService (`com.intellij.platform.completion.common.split.RpcCompletionService`)

`@Rpc interface RpcCompletionService : RemoteApi<Unit>`. `service` string in the call = that FQN. [Seen: `_generated_RemoteApiDescriptor.getApiFqn`, `getSignature`]

| # | method | args (JSON key: type) | returns | kind |
|---|---|---|---|---|
| 1 | `responseFlow` | none (`{}`) | `Flow<RpcCompletionSessionEvent>` | suspend returning a stream (host to client) |
| 2 | `commandFlow` | `commands: Flow<RpcCompletionCommand>` | `Unit` | client to host stream argument; call stays pending while stream is open |
| 3 | `scheduleExpensiveRendering` | `requestId: RpcCompletionRequestId`, `completionItemId: RpcCompletionItemId` | `Unit` | plain suspend call |
| 4 | `computePreview` | `requestId`, `completionItemId` | `RpcPreviewResult` (`{"info": RdIntentionPreviewInfo}`) | plain suspend call; for the documentation/preview popup, skip it |
| 5 | `autopopupRequests` | `projectId: ProjectId` | `Flow<RpcAutoPopupRequest>` | suspend returning a stream (host to client) |
| 6 | `noCompletionOnFrontend` | `editorId: EditorId` | `Unit` | called when no PSI is available on the frontend (`FrontendNewRdCompletionSupport.noPsiAvailable`) |
| 7 | `checkNonWriteAccessCommandCompletionApplicable` | `originalEditorId: EditorId`, `offset: Int`, `charTyped: Char` | `Boolean` | command completion (`..` style), skip |
| 8 | `createNonWriteAccessCommandCompletionDocument` | `frontendDocumentId: FrontendDocumentId`, `originalEditorId: EditorId`, `offset: Int`, `charTyped: Char` | `BackendDocumentId?` | command completion, skip |

Key facts:
- Only `responseFlow`, `commandFlow` and `autopopupRequests` are needed for the popup. The client opens ONE `responseFlow` and ONE `commandFlow` per app (frontend service `FrontendCompletionResponseFlowService` loops `DurableKt.durable(...)` re-establishing; `FrontendCompletionCommandFlowService` retries with `RE_ESTABLISH_DELAY`). All sessions and requests are multiplexed over them by `sessionId` / `requestId`. [Seen]
- `commandFlow` is fed from a channel: `FrontendCompletionCommandFlowService.send(command)` does `channel.trySend(command)`; it drops the command (warn "command flow is not established") when no stream is up. [Seen]
- `scheduleExpensiveRendering` is called from `FrontendAsyncRendering` for items whose `hasExpensiveRenderer = true`; the host then sends `ExpensivePresentations`. [Seen]
- Gate: the frontend only uses this path if registry `remdev.completion.on.frontend` is true and it is a remote-dev controller frontend and not CLion/Rider (`FrontendNewRdCompletionSupport.isFrontendRdCompletionOnImpl`). Your client does not need that gate, but the host may check that a frontend subscribed. [Seen]
- A second, small interface `AutoPopupControllerRpc.autoPopupParameterInfo(editorId, projectId, item: RpcCompletionItemId?)` exists (parameter info popup after an item is inserted). Not needed.

---------------------------------------------------------------------------------------------------

## 2. Data shapes

All class names below are in `com.intellij.platform.completion.common.protocol` unless a full name is given. Field order = serial order.

### 2.1 Id types
| type | JSON | notes |
|---|---|---|
| `fleet.util.UID` | `"85ma8o6plbbp314b5hig"` | string, see 4.2 |
| `EditorId` (`com.intellij.openapi.editor.impl.EditorId`) | `{"id":"<UID>"}` | see section 5 for where it comes from |
| `ProjectId` (`com.intellij.platform.project.ProjectId`) | `{"id":"<UID>"}` | see section 5 |
| `FrontendDocumentId`, `BackendDocumentId` (`com.intellij.ide.rpc`) | `{"uid":"<UID>"}` | not used in the popup |
| `RpcCompletionSessionId` | `{"id":"<UID>"}` | client creates (`UID.random()`) one per lookup |
| `RpcCompletionRequestId` | `{"id":"<UID>"}` | client creates one per request |
| `RpcCompletionItemId`, `RpcCompletionArrangementId` | `{"id":"<UID>"}` | host creates |
| `TextRangeDto` | `{"startOffset":0,"endOffset":3}` | |
| `IconId` | `{"serializedValue":<fleet.openmap.SerializedValue>}` (optional field) | opaque icon reference; ignore or map yourself |
| `ColorId` | `{"serializedValue":...}` (optional) | opaque colour reference |

Note: `RpcCompletionRequestId.id`, `RpcCompletionItemId.id`, `RpcCompletionArrangementId.id` have default "random UID" in the Kotlin class, but with `encodeDefaults=true` they are written. The IDs are plain objects (not inline value classes): `PluginGeneratedSerialDescriptor`, not an inline descriptor. [Seen]

### 2.2 RpcCompletionRequest
Serial name `com.intellij.platform.completion.common.protocol.RpcCompletionRequest`. [Seen]
```json
{
  "id": {"id":"r4q7..."},
  "sessionId": {"id":"s9k2..."},
  "editorId": {"id":"<kernelEditorId>"},
  "projectId": {"id":"<kernelProjectId>"},
  "startingEditorVersion": 57,
  "startingDocumentVersion": 211,
  "completionType": "BASIC",
  "invocationCount": 0,
  "isWriteActionRestart": false
}
```
- Required: `id, sessionId, editorId, projectId, startingEditorVersion, startingDocumentVersion`. Defaults: `completionType=BASIC` (enum `BASIC | SMART | CLASS_NAME`), `invocationCount=0`, `isWriteActionRestart=false`. [Seen]
- The editor is identified by `editorId` only. The caret and the text are NOT sent; the host uses its own copy of the document/caret, kept in sync by rd. The two version ints say which state the client saw. [Seen: `prepareCompletionRequest`]
- `startingEditorVersion` = `RdEditorVersion.caretModelVersion` of the editor's `EditorSynchronizer` (rd model class `com.jetbrains.rd.ide.model.RdEditorVersion {caretModelVersion:Int, documentHash:Long, hash:Long}`); `-1` if none. `startingDocumentVersion` = `RdDocumentVersion.value` of the document synchronizer (`RdDocumentVersion {value:Int, hash:Long}`); `-1` if none. [Seen: FrontendCompletionRequestSessionImplKt.prepareCompletionRequest]
- `invocationCount`: autopopup uses `parameters.getInvocationCount()` which is 0 for autopopup, 1 for first Ctrl+Space, 2 for the second press. [Seen as a passthrough of CompletionParameters; values Inferred from platform behaviour]

### 2.3 RpcCompletionCommand (client to host, elements of `commandFlow`)
Sealed interface, `"type"` = full name. [Seen]

| `type` suffix (after `...protocol.RpcCompletionCommand.`) | fields | when |
|---|---|---|
| `OpenSession` | `sessionId`, `editorId`, `projectId` | once per lookup, before the first request; `registerSessionOnBackend` |
| `StartRequest` | `request: RpcCompletionRequest` | start a completion; `startRequest` |
| `PrefixChanged` | `requestId`, `update: RpcPrefixUpdate` | user typed / deleted in the popup |
| `LookupEvent` | `event: RpcLookupElementEvent` | selection moved / item chosen / popup cancelled |
| `ScheduleModCommand` | `requestId`, `completionItemId` | ask the host to compute the ModCommand for the selected item (see 2.6) |
| `ReleaseRequests` | `sessionId`, `requestIds: [RpcCompletionRequestId]` | the client dropped cached results; host can free them |
| `CloseSession` | `sessionId` | popup is gone |

`RpcPrefixUpdate` (sealed): `{"type":"...RpcPrefixUpdate.Append","char":"a"}` or `{"type":"...RpcPrefixUpdate.Truncate"}` (Truncate = backspace/removed last char; `afterTruncate` in `FrontendCompletionPrefixChangeListener`). [Seen]

`RpcLookupElementEvent` (sealed, `...protocol.RpcLookupElementEvent.X`): [Seen]
- `LookupStateChanged{requestId, selectedItemId: RpcSelectedItem, focusDegree?: LookupFocusDegree, sortedItemIds?: [RpcCompletionItemId]}` where `RpcSelectedItem = {"value": <RpcCompletionItemId|null>}` (field has a default, null). `focusDegree` is sent only when it changed (`FOCUSED | SEMI_FOCUSED | UNFOCUSED`); `sortedItemIds` only when the visible order changed.
- `ItemSelected{projectId, requestId, selectedItemId?: RpcCompletionItemId, itemPattern?: String}` (the 2 optional fields default to null). `itemPattern` = `lookup.itemPattern(item)` (the typed prefix as the item sees it).
- `Cancel{projectId, requestId}`.

### 2.4 RpcCompletionSessionEvent / RpcCompletionResponseEvent (host to client, elements of `responseFlow`)
Top element (sealed with one case): [Seen]
```json
{"type":"com.intellij.platform.completion.common.protocol.RpcCompletionSessionEvent.Response",
 "sessionId":{"id":"s9k2..."},
 "event":{ "type":"com.intellij.platform.completion.common.protocol.RpcCompletionResponseEvent.<Case>", ... }}
```
Cases of `RpcCompletionResponseEvent` (all carry `requestId` except where noted):

| Case | fields | meaning / what the frontend does (`processEvent`) |
|---|---|---|
| `BackendSettings` | `requestId`, `mayHaveCustomPreview: Boolean` | first event; enables the preview machinery |
| `NewItems` | `requestId`, `newItems: [RpcCompletionItem]` (default `[]`), `completionArrangement: RpcCompletionArrangement` | add items; arrangement always rides with items. Can arrive many times per request (batches) |
| `NewArrangement` | `requestId`, `completionArrangement` | re-rank / re-filter, e.g. after `PrefixChanged`; frontend calls `lookup.arranger.prefixChanged` + `refreshUi` |
| `ExpensivePresentations` | `requestId`, `presentations: [{completionItemId, newPresentation: RpcCompletionItemPresentation}]` | replace presentation of items that had `hasExpensiveRenderer` |
| `ModCommandResults` | `requestId`, `results: [{completionItemId, modCommand: RpcModCommand}]` | answer to `ScheduleModCommand` |
| `Advertisement` | `requestId`, `message: String`, `icon?: IconId` | footer hint text |
| `AddWatchedPrefix` | `requestId`, `condition: RpcRestartPrefixCondition` | "restart completion when the prefix matches this condition"; client calls `result.restartCompletionOnPrefixChange(pattern)` |
| `CompletionItemsFinished` | `requestId` | no more items; releases the "all items" waiter |
| `CompletionFinished` | `requestId`, `reason: RpcCompletionFinishReason` | final; reason is `SUCCEEDED | SKIPPED | CANCELLED | FAILED` (objects) or `RestartingAfterWriteAction{request: RpcCompletionRequest}` (host restarted with a new request after a write action: client adopts the new request) |

`RpcRestartPrefixCondition`: `AlwaysTrue` (object), `EqualsTo{value}`, `Serialized{descriptor}` (descriptors like `LongerThanDescriptor`, `StartsWithDescriptor`, ... wrapped as in 2.7). [Seen]

### 2.5 RpcCompletionItem, presentation, arrangement
`RpcCompletionItem` (serial name `RpcCompletionItem` under the protocol package). Fields, `*` = required, others default: [Seen]
```
id* {id}, prefixMatcher* (RpcPrefixMatcher), insertHandler = Backend object,
lookupString* String, allLookupStrings = null (Set<String>?), presentation* ,
hasExpensiveRenderer=false, requiresCommittedDocuments=true,
autoCompletionPolicy=SETTINGS_DEPENDENT (NEVER_AUTOCOMPLETE|SETTINGS_DEPENDENT|GIVE_CHANCE_TO_OVERWRITE|ALWAYS_AUTOCOMPLETE),
isCaseSensitive=true, shouldStopLookupInsertion=false, isDirectInsertion=false,
isWorthShowingInAutoPopup=false, commandState=null, hasModCommand=false,
isTopPriorityItem=false, isNeverAutoselectTopPriorityItem=false,
completionGroup=null ({order:Int, displayName:String}), hasCustomPreview=false
```
`RpcCompletionItemPresentation` (all optional except `itemTextForeground`): [Seen]
```
icon: IconId?=null, typeIcon: IconId?=null, typeIconRightAligned=false,
itemText="" , typeText="", strikeout=false,
itemTextForeground* : ColorId, itemTextBold=false, itemTextUnderlined=false, itemTextItalic=false,
itemNameDecorations=[ {textRange:{startOffset,endOffset}, decoration: LookupItemDecoration} ],
itemTailDecorations=[ same ], typeGrayed=false,
tail=[ {text*, grayed=false, italic=false, fgColor: ColorId?=null} ]
```
`tail` = the tail text fragments ("(String s)" part), `typeText` = right-aligned type text, `strikeout` = deprecated items, `itemNameDecorations` = ranges with decoration enum (e.g. bold/underline-like `LookupElementPresentation.LookupItemDecoration`).
Rendering in the real client: `RpcCompletionItemPresentationKt.render(presentation, LookupElementPresentation)`. For your own UI map: icon (opaque), `itemText`, tail fragments, `typeText`, flags.

`RpcCompletionArrangement` (ordering + filtering result from the host): [Seen]
```json
{"id":{"id":"a1"},
 "weightsOfMatchedItems":[{"id":{"id":"i1"},"weight":0},{"id":{"id":"i2"},"weight":1}],
 "nonMatchedItems":[{"id":{"id":"i9"}}],
 "startMatches":[{"id":{"id":"i1"}}]}
```
- All four fields are optional in the class (`id` defaults to a random id, the three lists default to empty). Order = sort **ascending by `weight`**; an item without a weight sorts last (`Int.MAX_VALUE`). [Seen: `FrontendClassifier.getWeight`, comparator `compareValues(weightA, weightB)`]
- `nonMatchedItems` = items the host says do not match the current prefix (hidden); for items with the `Backend` matcher, `FrontendPrefixMatcher.prefixMatches` is just `!nonMatchedItems.contains(id)`. `startMatches` = items counted as "start match" (`isStartMatch`). [Seen]
- The newest arrangement replaces the older one (`ArrangementHistory`).

### 2.6 Selecting / inserting an item
What the real frontend does when the user commits an item (Enter/Tab/click): [Seen: LookupImpl bytecode + FrontendCompletionLookupMirror + FrontendInsertHandler]
1. Standard IntelliJ `LookupImpl.finishLookupInWritableFile`: inside a write action it replaces the typed prefix by `item.lookupString` in the **frontend editor document** (`insertLookupString`), and only after that calls `fireItemSelected`.
2. `FrontendCompletionLookupMirror.itemSelected` sends `LookupEvent{ItemSelected{projectId, requestId, selectedItemId, itemPattern}}` on `commandFlow`.
3. `FrontendInsertHandler.handleInsert` (the item's `handleInsert`) then chooses:
   - If a `ModCommand` for the item was already received (`ModCommandResults`): it is deserialised and run on the frontend (`ModCompletionInserter.executeModCommand`) i.e. the client applies text edits itself (`RpcUpdateFileText {filePath, oldText, newText, updatedRanges:[{offset,oldLength,newLength}]}` etc.). `ScheduleModCommand` is sent whenever the selection changes to an item with `hasModCommand=true`, to precompute this.
   - Else if `insertHandler` is `{"type":"...RpcInsertHandler.Frontend","insertHandler":{fqn,value}}`: run that `FrontendFriendlyInsertHandler` locally (e.g. tail types); needs the IDE classes, so your client cannot run it generally.
   - Else (`...RpcInsertHandler.Backend`, the default): "delegating to backend": the frontend does nothing more; the host runs the real insert handler (imports, parentheses, templates) on its copy of the editor after receiving `ItemSelected`.
4. The host's extra edits come back to the frontend as ordinary **rd document patches** on the text control (not as an RPC reply). [Inferred: there is no RPC result carrying the edit; the frontend only sends `ItemSelected` and the document is synced by the patch engine]
So for a minimal client: replace prefix with `lookupString` locally (send as normal rd edit), send `ItemSelected`, then apply incoming rd patches. Ordering risk: your rd edit and the RPC `ItemSelected` travel on different channels; the real client protects RPC calls with `PatchEngineSynchronizationData.lastQueuedRequest`, but `commandFlow` stream elements have no meta, so the host may see `ItemSelected` before your edit; unknown how the host copes (Inferred risk). If it matters, wait for the rd edit to be acked before sending `ItemSelected`.

### 2.7 Prefix matcher descriptors (client-side filtering)
`RpcCompletionItem.prefixMatcher` (sealed `RpcPrefixMatcher`): [Seen]
- `{"type":"...RpcPrefixMatcher.Backend","id":{"id":"<itemId>"},"prefix":"fo"}`: the host decides matching; you only look at the arrangement (`nonMatchedItems`).
- `{"type":"...RpcPrefixMatcher.Frontend","descriptor":{"fqn":"<descriptor class FQN>","value":{...}}}`: the client re-creates a matcher (`descriptor.recreateMatcher()`) and filters itself. The `descriptor` field is serialised by `FrontendFriendlyPrefixMatcherSerializer` (an `ExtensionPointSerializer`) as `SerializerWrapper {fqn: String, value: JsonElement}` where `fqn` is the descriptor class name. Registered descriptors (plugin xml `completion.frontendFriendlyPrefixMatcher`):
  - `com.intellij.platform.completion.common.protocol.CamelHumpMatcherDescriptor` value `{"prefix":"fo","caseSensitive":true,"typoTolerant":false}` (for `CamelHumpMatcher`, the default for Java/Kotlin identifiers)
  - `...PlainPrefixMatcherDescriptor` value `{"prefix":"..","isStartMatchOnly":false}`
  - `...StartOnlyMatcherDescriptor` value `{"delegate": <another descriptor wrapper?>}` (delegate is a `PrefixMatcherDescriptor`, so wrapped the same way)
Inside `value`, the Json used has `ignoreUnknownKeys=true, encodeDefaults=true`.
When the user types another char, the real client does two things: (a) for Frontend matchers it clones the matcher with `prefix + prefixDelta` where `prefixDelta` = document text between the request caret offset and the current caret (`computeTypedDelta`); (b) for every case it sends `PrefixChanged` so the host recomputes and returns `NewArrangement`. [Seen]
Your client: implement CamelHump matching yourself (IntelliJ `MinusculeMatcher` rules: each hump of the prefix must match the start of a word part of the item, case-insensitive for lowercase prefix letters when `caseSensitive=false`). Use the host's `nonMatchedItems` as the source of truth after each `NewArrangement` to avoid mismatches.

---------------------------------------------------------------------------------------------------

## 3. Order of calls

### (a) Autopopup while typing an identifier
Autopopup is started by the **host**, not the frontend: `FrontendNewRdCompletionSupport.scheduleAutopopupOnFrontend` returns `false`. [Seen]
1. Once, at project open (`FrontendRpcAutopopupSchedulingActivity.execute`): `RpcCompletionService.autopopupRequests(projectId)` and `collectLatest` on the returned flow. Also once per app: `responseFlow()` (service `FrontendCompletionResponseFlowService` starts it in its constructor) and `commandFlow(commands)` on first send. [Seen]
2. User types a letter: the char is an rd text edit (patch) from the client to the host. The host's typed handler decides to auto-popup and emits `RpcAutoPopupRequest` on the autopopup stream: [Seen shape; trigger Inferred]
```json
{"editorId":{"id":"<kernelEditorId>"},"completionType":"BASIC","caretModelVersion":57,"documentVersion":211}
```
3. Frontend: if registry `remdev.completion.autopopup.check.editor.version` is on, it drops the request unless the local `RdEditorVersion.caretModelVersion == caretModelVersion` and `RdDocumentVersion.value == documentVersion` (stale otherwise; `versionMatches`). If OK it runs `AutoPopupController.scheduleAutoPopup(editor, completionType)` on the EDT. [Seen]
4. That creates a lookup and runs the contributor `FrontendCompletionContributor.fillCompletionVariants` -> `FrontendCompletionService.startCompletionRequest`: [Seen]
   - new session: `sessionId = UID.random()`; `commandFlow` element `OpenSession{sessionId, editorId, projectId}`
   - `commandFlow` element `StartRequest{request}` (see 2.2; `invocationCount = 0`)
   - host streams `Response{BackendSettings}`, `Response{NewItems...}` (several), `Response{NewArrangement}` as ranking improves, `Response{AddWatchedPrefix}`, `Response{Advertisement}`, `Response{CompletionItemsFinished}`, `Response{CompletionFinished{SUCCEEDED}}`.
   - The frontend blocks (`awaitAllItemsReceivedBlocking`) until `CompletionItemsFinished`/`CompletionFinished` before it finalises the first lookup UI.
5. User keeps typing a letter that still fits: `PrefixChanged{requestId, Append{char}}` (sent by `FrontendCompletionPrefixChangeListener.afterAppend`), host answers with `NewArrangement` (and possibly new `NewItems`). No new `StartRequest` unless the prefix breaks a watched condition (`AddWatchedPrefix`) or a cached result can't serve it. Backspace: `PrefixChanged{Truncate}`. [Seen]
6. Selection moves (up/down): `LookupEvent{LookupStateChanged{requestId, selectedItemId:{value}, focusDegree?, sortedItemIds?}}` (from `uiRefreshed` and `currentItemChanged`; only sent when something changed). Also `ScheduleModCommand` for the newly selected item if `hasModCommand`. [Seen]
7. Accept item: see 2.6 (`LookupEvent{ItemSelected}`); then the session ends: `CloseSession{sessionId}` is sent by `unregisterSession -> unregisterSessionOnBackend` when the session scope completes (lookup item selected / cancelled). [Seen]
8. Dismiss (Esc, caret moved away): `LookupEvent{Cancel{projectId, requestId}}` (`lookupCanceled`), then `CloseSession{sessionId}`. [Seen]
9. When the frontend reuses an old result (`FrontendResultCache.findUsable`: `Exact` -> reuse, `Stale` -> show stale items and issue a fresh request to swap) it may send `ReleaseRequests{sessionId, requestIds}` for dropped requests. Minimal client: always issue a fresh `StartRequest` and ignore this. [Seen]
10. Write-action restart: the host may send `CompletionFinished{reason: RestartingAfterWriteAction{request: <new request>}}`. The frontend registers a "pending pickup" for the new request id, restarts its indicator and then consumes events of the new request id instead. Events can arrive for the new `requestId` (same `sessionId`) before you asked for it; keep a per-request buffer. [Seen]

### (b) Explicit basic completion (Ctrl+Space)
Same as (a) from step 4 on. Differences: the frontend itself starts it (`CodeCompletionHandlerBase` on the frontend editor, no `autopopupRequests` event), `parameters.getInvocationCount()` is 1 on the first press and 2 on the second press (second press asks for more, e.g. static/non-imported members), `completionType` = `BASIC` (Ctrl+Space) / `SMART` (Ctrl+Shift+Space) / `CLASS_NAME` (Alt+Ctrl+Space). If you press again in an open popup the frontend issues a new `StartRequest` in the same session with `invocationCount=2`. [Seen for fields; key bindings are platform knowledge]
Before the request, the caret position in the host's copy must be current: the real client queues rd edits and the PatchEngine meta on the RPC makes the host wait for them. For the `commandFlow` stream there is no such meta, so make sure all rd edits are already acked/applied by the host before you send `StartRequest`, and put the matching `startingEditorVersion/startingDocumentVersion` into the request. [Inferred]

---------------------------------------------------------------------------------------------------

## 5. Rhizome / kernel DB entities: needed or not?

**Answer: NO for completion. No Rhizome entity ids are used in any completion message.**

Evidence:
- `RpcCompletionRequest.editorId` is `EditorId { id: UID }` (a string). `RpcCompletionCommand.OpenSession.projectId` / `RpcAutoPopupRequest.editorId` are the same kinds of strings. `RpcCompletionService` has no entity/DbContext/`Entity` parameter anywhere (all 8 method signatures listed in section 1). [Seen]
- Where the UID strings come from (all rd):
  - `EditorId`: `FrontendTextControlHost.registerTextControl` builds `KernelAwareTextControlId(kernelEditorId = editor.getUserData(KERNEL_EDITOR_ID_KEY).serializeToString(), documentId, tabIndex, location, clientId, projectId)` and for host-opened editors `ensureEditorOpened` does `editor.putUserData(KERNEL_EDITOR_ID_KEY, EditorId.deserializeFromString(textControlId.kernelEditorId))`. So the id is the string field `kernelEditorId` of the **rd** class `com.jetbrains.rd.ide.model.KernelAwareTextControlId` (subclass of `TextControlId`, constructor `(String kernelEditorId, RdDocumentId, int tabIndex, TextControlLocation, String clientId, RdProjectId)`). A frontend-created editor gets a random one from `EditorId.create()` (= `EditorId(UID.random())`). [Seen]
  - `ProjectId`: `ThinClientRdProjectViewSession.handleNewSolution` does `ProjectId.deserializeFromString(rdCodeWithMeProject.kernelProjectId)` then `ProjectIdKt.setNewProjectId(project, id)`. So it is the string `RdCodeWithMeProject.kernelProjectId` delivered over rd. [Seen]
- Rhizome only shows up in the *optional* `CompressedVectorClock` meta (4.6), which is attached only when a `DbContext` is bound.
- Things to verify with a live run (not provable from the client jars):
  1. The text controls your client opens/receives are `KernelAwareTextControlId` (not plain `TextControlId`). If the host creates the text control it sends the kernel variant with its own `kernelEditorId`; use that string verbatim. If your client creates its own text control (like `registerTextControl`), make a `KernelAwareTextControlId` with a fresh UID so the host can map `EditorId` to the editor. How the host resolves `EditorId` to an `Editor` is not visible here: client side the resolver is `EditorIdKt.findEditorOrNull` = scan `EditorFactory.getAllEditors()` for `KERNEL_EDITOR_ID_KEY` user data; the host mirrors this. [Inferred]
  2. That the host does not hold completion calls back waiting for a Rhizome vector clock when `CompressedVectorClock` is missing (see 4.6). [Inferred]

---------------------------------------------------------------------------------------------------

## Minimal client checklist (what to build)
1. Fleet layer: JSON `TransportMessage.envelope`/`RpcMessage` over `FleetRpcModel.outgoing/incoming` (4.1-4.3), with Json settings of 4.4, stream engine of 4.5 (stream_init, per-100 stream_next, stream_closed).
2. On connect: `responseFlow` (stream in), `commandFlow` (stream out), `autopopupRequests(projectId)` (stream in). Keep them for the whole connection; re-establish on drop.
3. Per popup: new `sessionId`; send `OpenSession`, `StartRequest`; collect `NewItems`/`NewArrangement` by `requestId`; render items sorted by arrangement weight, hide `nonMatchedItems`; on typing send `PrefixChanged`; on selection move send `LookupStateChanged`; on accept replace prefix locally + send `ItemSelected`; on dismiss `Cancel` + `CloseSession`.
4. For `RpcPrefixMatcher.Frontend` items either implement CamelHump matching or ignore the matcher and trust the host arrangement only (`nonMatchedItems`). The second is simpler and is how `Backend` matchers already behave.
5. Ignore: `computePreview`, `scheduleExpensiveRendering` (unless you want slow-rendered presentations), `ModCommand` paths, command completion methods 7-8, `ReleaseRequests`, `noCompletionOnFrontend`.
6. Unknown/not verifiable from the client jars: the host's behaviour without `CompressedVectorClock` / `PatchEngineSynchronizationData`, the exact "fleet" route value on host replies, and whether `ItemSelected` before the rd edit is tolerated.

---------------------------------------------------------------------------------------------------

## 6. Checked with our client against the Mac backend (2026-11-15)

Settled by the live runs, replacing the **Inferred** items above:

| Question | Answer |
|---|---|
| Does the host accept calls whose `CompressedVectorClock` is `{"clientId":<uid>,"consumedTxs":0,"sentTxs":0}`? | Yes: `responseFlow`, `commandFlow` and `autopopupRequests` all opened and streamed. |
| Is the host's route `"fleet"`? | Yes, host messages carry `"origin":"fleet"`. |
| Does the host send `RpcAutoPopupRequest` after a typed letter? | Yes. Its versions matched ours, so the client opened the popup without any frontend typing logic. |
| `ItemSelected` before the rd edit: does the host cope? | Yes. Recorded order (also ours): `CloseSession`, `ItemSelected`, then `RdCallEditorActionRequest("EditorChooseLookupItem")` with the inserted rest of the word. Prefix `gre`, item `greeting`: `change(218, +"eting")`, confirmed unchanged. |
| Where do the backend's insert-handler edits arrive? | In the answer to the `EditorChooseLookupItem` request: item `length()` after `greeting.len` came back as `+"gth"`, then `+"("`, `+")"`, caret 232, which the client follows like any other edit the backend changed. |

Known differences from JetBrains Client: the first autopopup of a session can carry `expui/fileTypes/java.svg` as the item icon where the real popup shows the variable icon; the footer tip seen in the real popup ("Press ^. to choose…") did not arrive as an `Advertisement` event in our runs.
