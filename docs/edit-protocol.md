# JetBrains Client 263.7307.200: edit protocol (text, caret, undo) over rd

Legend: **[Seen]** = read in decompiled client code or verified against `capture-3-typing.log`. **[Inferred]** = not visible in client code or trace (mostly backend behaviour).
Code read: `intellij.rd.client.jar` (`com.jetbrains.rdclient.*`), `intellij.rd.platform.jar` (`com.jetbrains.rd.ide.*`, `com.jetbrains.rd.platform.undo.RdCmdModelSupport`), `intellij.libraries.rd.core.jar` (`IncrementalHashKt`), `intellij.rd.ide.model.generated.jar` (field lists). Decompiled sources are not in the repo.
All hash numbers below were recomputed in Python and match the trace exactly.

## 0. Summary of the model

* Everything goes over one signal: `DocumentsOperationModel.requestResponseModel.requestsWithTime` (client to backend) / `responsesWithTime` (backend to client). Backend-originated changes use `backendPatches`. [Seen]
* The frontend edits **locally first**. Listeners record the change into an `RdPatch` (`PatchBuilder`). The patch is sent inside a request. The backend answers with its own patch for the same request. The client compares the two (`speculativelyEquals`). If equal, it does nothing more to the text. If different, it rolls back its pending local requests, applies the backend patch and replays the rest (rebase). [Seen: `SpeculativeQueueProcessor`, `RdPatchUtilsKt.speculativelyEquals`]
* Three request types carry edits: `RdTypeKeyRequest` (typed char), `RdCallEditorActionRequest` (Enter, Backspace, arrows, ...), `RdApplyPatchRequest` (anything done locally: caret moves, mouse selection, "empty command" markers). Undo/redo is `RdUndoRedoRequest` and has no local patch.

## 1. Envelope (Seen: `FrontendAsyncRequestExecutorNew.createRequestWrapper$lambda$0$send`, `submitRequest`)

```
requestsWithTime.fire(RdRequests(
  requests  = [ RdRequestHolder(requestId, request, retryTimestamp = RdRetryTimestamp(timestamp=0, generation=0), data=<null>) ],
  timestamp = <epoch time in nanoseconds, microsecond resolution>   // TelemetryDataUtilKt.getCurrentTime(), e.g. 1794751587204748000
))
```

* `requestId`: one `AtomicLong.incrementAndGet()` per submitted request, **shared by all request kinds** (also `RdNavigationBarRequest`, etc.). Trace: Enter was 13, `RdNavigationBarRequest` 14, 15, next TypeKey 16. [Seen]
* `RdRequests.timestamp`: wall clock epoch nanoseconds when the holder was sent. One holder per `RdRequests` in the trace (sometimes two requests are sent back to back, not in one list). The backend does not use it for matching (client code never reads it back). [Seen send side; use by backend Inferred]
* `retryTimestamp`: global retry timestamp of the speculative queue (`PatchEngineRetryTimestampWithGeneration`). Starts at `(0,0)`. It only changes when the backend answers with `RdRetryResponse(requestId, retryTimestamp, message)`; the client then stores `retryTimestamp` as the new global value, bumps `request.retryCounter`, and re-sends every pending request with the new value. `cancelAndRemoveAllPending` increments `generation`. Every request in the whole trace has `(0,0)`. [Seen code; (0,0) Seen in trace]
* `data`: `List<RdTelemetryData>` (key `traceparent`). Optional. Trace requests have `<null>`; responses carry one `traceparent`. [Seen]
* Response: `RdResponses(responses=[RdSuccessResponse(patch, requestId, data)], timestamp=<backend ns time>)`. `requestId` equals the request's id. Failure variants: `RdFailureResponse(message)`, `RdNoEditorFoundResponse`, `RdRetryResponse`. [Seen]
* Pipelining: the client does not wait for a response before sending the next request (trace: ids 19, 21 sent before 19's response). Responses come back in request order; the client asserts `pendingRequests.peek().id == response.requestId`. [Seen `SpeculativeQueueProcessor.applyAllPendingResponses`]

## 2. Typing a character: `RdTypeKeyRequest` (Seen)

Flow (`FrontendAsyncRawTypedHandler.executeBackendTypedAction`, `LocalTypeKeyRequestFactory.createRequest`, `FrontendTypeKeyRequest.createModel`):
1. Condition for the "backend involved" path: patch engine enabled, not a local-only editor, `ActionCallPolicy.getActionCallStrategy(dataContext,"type%").isBackendInvolved()`, no active patch session, no live template active, and every `FrontendAsyncRawTypedHandlerPolicy` (EP `com.intellij.rdclient.typedHandlerPolicy`) returns `isAvailable`. If any condition fails, the plain local typed handler runs and the change is sent as a normal `RdApplyPatchRequest` (section 6) with no `char`.
2. The local `TypedActionHandler` runs inside an accumulating session. The recorded changes (caret snapshot, doc change, caret snapshot, wrapped in a command) become `speculativePatch`. Local typing assists therefore show up as extra `RdDocumentChange` items in the patch.
3. `RdTypeKeyRequest(char, editorId, speculativePatch)`. No `timestampSetModel`.

Fields: `char` (one UTF-16 char), `editorId: TextControlId` (a `KernelAwareTextControlId`), `speculativePatch: RdPatch`.

Auto-closing quotes/braces: the backend runs the real typing handler. If it inserts more than the client did (`"` becomes `""`), the response patch differs, `speculativelyEquals` gives Different, and the client rebases (rolls back its local `"`, applies the backend's patch). [Seen mechanism; backend producing the extra text Inferred]

### Example: type `x` at offset 120 in a 260-char document (symbolic hashes, see section 5 for the formulas)

Let `H0` = current document hash (version value `V`, say 17), caret id number 1, editor version before = `(caretModelVersion=C, documentHash=H0, hash=E(120))` with `E(120)=122198`, commandId `-N`, previous command id `-P`.

```
RdRequestHolder(requestId=R, retryTimestamp=(0,0),
 request = RdTypeKeyRequest(
  char = 'x',
  editorId = KernelAwareTextControlId(kernelEditorId, documentId, tabIndex=1, kind=EditorTab, clientId, projectId),
  speculativePatch = RdPatch(
   changes = [
    RdCmdItem(RdPatchCmdStart(commandId=-N, commandProjectId=<projectId>, groupId=RdDocGroupId(fileId), commandName=RdCommandName("#"),
              confirmationPolicy=DEFAULT, isTransparent=false, commandMeta=[RdUndoMeta x2]))
    RdCaretStateSnapshot(documentChangeWrapping=true,  editorId, state=RdCaretState(primaryCaretId=RdCaretId(isClient=true,number=1),
        carets=[RdCaret(id=RdCaretId(true,1), offset=DocOffsetAndVirtualModel(120,0,logicalLeansForward=false),
                        selection=RdSelection(start=DocOffsetAndVirtualModel(120,0,false), end=DocOffsetAndVirtualModel(120,0,false)))],
        documentVersion=RdDocumentVersion(V, H0)))
    RdDocumentChange(offset=120, moveOffset=120, newText="x", oldText="", modificationKind=NotSpecified, oldDocumentLength=260,
        text=<null>, isWholeTextReplaced=false, documentId=<docId>, oldVersion=RdDocumentVersion(V,H0),
        newVersion=RdDocumentVersion(V+1,H1), uniqueId="unique-id-backend-1")
    RdCaretStateSnapshot(documentChangeWrapping=false, editorId, state=...caret 121, selection 121..121, documentVersion=RdDocumentVersion(V+1,H1))
    RdCmdItem(RdPatchCmdFinish(commandId=-N, same project/group as start except groupId may differ, commandName=RdCommandName("#Typing"), ...))
   ],
   features = [],
   expectedItemVersions = [ RdCmdItemVersion(RdCmdVersion(hash=-P), id=RdCmdId()),
                            RdPatchEditorVersion(RdEditorVersion(C, H0, 122198), id=editorId),
                            RdPatchDocumentVersion(RdDocumentVersion(V,H0), uniqueId="unique-id-backend-1", id=<docId>) ],
   updatedItemVersions  = [ RdPatchEditorVersion(RdEditorVersion(C+2, H1, 122199), id=editorId),
                            RdPatchDocumentVersion(RdDocumentVersion(V+1,H1), uniqueId="unique-id-backend-1", id=<docId>) ])))
H1 = mix(mix(mix(mix(mix(H0,120),120),260),""),"x")   // section 5
```
Real values from the trace (typing `i` at 215, doc length 265, version 1 to 2): `H0=-6257571537734692227`, `H1=-2740688552363290787`, editor `(3, -6257571537734692227, 122293)` to `(5, -2740688552363290787, 122294)`, command start `-16`, `RdCmdVersion.hash=-14`. [Seen]

Immediately after every `RdTypeKeyRequest` the client sends a second request, `RdApplyPatchRequest`, see section 6 ("transparent command marker"). [Seen]

## 3. Enter, Backspace, Delete, arrows: `RdCallEditorActionRequest` (Seen)

`FrontendCallEditorActionRequest.createModel`: `RdCallEditorActionRequest(actionId = backendActionId(frontendActionId), editorId, speculativePatch, timestampSetModel = filterOutTransientTimestamps(...))`. The action is also executed locally first; the recorded patch is `speculativePatch`.

| Key | actionId (Seen in trace) | patch contents |
|---|---|---|
| Enter | `"EditorEnter"` | cmd start `"#Enter"`, snapshot(wrap=true), `RdDocumentChange(offset=210, moveOffset=210, oldText="", newText="\n    ", oldDocumentLength=260)` (auto-indent is part of newText), snapshot(wrap=false) caret 215, cmd finish `"#Typing"` |
| Backspace | `"EditorBackSpace"` | cmd start `"#Backspace"`, snapshot(wrap=true) caret 231, `RdDocumentChange(offset=230, moveOffset=230, oldText=";", newText="", oldDocumentLength=281)`, snapshot(wrap=false) caret 230, cmd finish `"#Backspace"` with `groupId=RdDelGroupId()` |
| Delete | `"EditorDelete"` [Inferred: standard IntelliJ action id, not in trace] | like Backspace, `oldText` is the char after the caret, `offset` = caret |
| Shift+Right | `"EditorRightWithSelection"` | `RdCaretStateChange(old=new=current state)`, then `RdCaretStateSnapshot(wrap=false, new state)` |
| Down | `"EditorDown"` | same shape (Change(old=new) + Snapshot) |
| Double-click word | `"EditorSelectWordAtCurrentCaret"` plus a separate `RdApplyPatchRequest` | `RdCaretStateChange(old caret 143, new caret 143 selection 140..148)` |

Enter example (all Seen, `requestId=13`):
```
RdCallEditorActionRequest(actionId="EditorEnter", editorId=KernelAwareTextControlId(...),
 speculativePatch=RdPatch(
  changes=[ RdCmdItem(RdPatchCmdStart(commandId=-14, groupId=<null>, commandName=RdCommandName("#Enter"), confirmationPolicy=DEFAULT, isTransparent=false, commandMeta=[RdUndoMeta,RdUndoMeta]))
            RdCaretStateSnapshot(documentChangeWrapping=true,  state=caret 210, sel 210..210, documentVersion=RdDocumentVersion(0,-5446235163934729545))
            RdDocumentChange(offset=210, moveOffset=210, newText="\n    ", oldText="", modificationKind=NotSpecified, oldDocumentLength=260, text=<null>, isWholeTextReplaced=false,
                             documentId=DOC, oldVersion=RdDocumentVersion(0,-5446235163934729545), newVersion=RdDocumentVersion(1,-6257571537734692227), uniqueId="unique-id-backend-1")
            RdCaretStateSnapshot(documentChangeWrapping=false, state=caret 215, sel 215..215, documentVersion=RdDocumentVersion(1,-6257571537734692227))
            RdCmdItem(RdPatchCmdFinish(commandId=-14, commandName=RdCommandName("#Typing"), ...)) ],
  features=[],
  expectedItemVersions=[ RdCmdItemVersion(RdCmdVersion(-12),RdCmdId()),
                         RdPatchEditorVersion(RdEditorVersion(caretModelVersion=1, documentHash=-5446235163934729545, hash=122288), editorId),
                         RdPatchDocumentVersion(RdDocumentVersion(0,-5446235163934729545),"unique-id-backend-1",DOC) ],
  updatedItemVersions=[ RdPatchEditorVersion(RdEditorVersion(3,-6257571537734692227,122293),editorId),
                        RdPatchDocumentVersion(RdDocumentVersion(1,-6257571537734692227),"unique-id-backend-1",DOC) ]),
 timestampSetModel=ActionTimestampSetModel(...))   // section 5.5
```

Backspace example (Seen, `requestId=55`): expected editor `(35, 218028870601022862, 122309)`, doc `(17, 218028870601022862)`; updated editor `(37, 8029041787485730192, 122308)`, doc `(18, 8029041787485730192)`; cmd `-50`, `RdCmdVersion(-48)`.

Typing vs Enter/Backspace: typing uses `RdTypeKeyRequest` because the typed handler is the raw typed handler (`"type%"`). Other editor actions use `RdCallEditorActionRequest` (`FrontendAsyncEditorActionHandler`). Actions the frontend handles purely locally (policy says backend not involved) are only sent as `RdApplyPatchRequest`. [Seen structure; the per-action policy table lives in `ActionCallPolicy`, not read]

## 4. Caret and selection (Seen)

Items inside a patch (class `RdSelectionChangeBase` extends `RdEditorChange(editorId)`):
* `RdCaretStateChange(oldState: RdCaretState, newState: RdCaretState, editorId)`: plain caret/selection move, used when the document version did not change (`SendingMode.CHANGE`). If the document version differs between old and new state it is sent as `RdCaretStateSnapshot(true, newState)` instead.
* `RdCaretStateSnapshot(documentChangeWrapping: Boolean, state: RdCaretState, editorId)`: full state. `documentChangeWrapping=true` marks the snapshot taken just before a document change (sent by `beforeDocumentChangeOrBulkEvent`) and also the "force" snapshots; `false` is the state after an action (`sendCaretStateSnapshot`).
* `RdCaretState(primaryCaretId, carets: List<RdCaret>, documentVersion: RdDocumentVersion)`: the document version the offsets refer to.
* `RdCaret(id: RdCaretId(isClient: Boolean, number: Long), offset: DocOffsetAndVirtualModel, selection: RdSelection(start,end))`. `DocOffsetAndVirtualModel(offset: Int, virtualDelta: Int, logicalLeansForward: Boolean?)`. `selection.start/end` are offsets (start <= end); collapsed caret is start == end == caret offset. Trace: caret id `RdCaretId(isClient=true, number=1)`; `logicalLeansForward` was `true` in the first snapshot of the Enter request and `false` elsewhere.

Where they travel: caret-only moves (mouse click, double-click selection) are sent as `RdApplyPatchRequest`; keyboard caret actions are `RdCallEditorActionRequest`. Consecutive caret-only `RdApplyPatchRequest`s are merged by the client (`FrontendApplyPatchRequest.mergeCaretChanges`, only if both patches hold a single caret change and the same `documentHash`).

### Move caret to offset 50 (document version `V`, hash `H`, editor version before `(C,H,E)`; mouse click) (modeled on `requestId=74`, Seen)
```
RdApplyPatchRequest(RdPatch(
 changes=[ RdCmdItem(RdPatchCmdStart(commandId=-N, commandProjectId=PID, groupId=<null>, commandName=RdCommandName("#"), confirmationPolicy=DEFAULT, isTransparent=false, commandMeta=[...]))
           RdCaretStateChange(oldState=RdCaretState(caret 230..., documentVersion=RdDocumentVersion(V,H)),
                              newState=RdCaretState(primaryCaretId, carets=[RdCaret(RdCaretId(true,1), offset(50,0,false), RdSelection(offset(50,0,false), offset(50,0,false)))], documentVersion=RdDocumentVersion(V,H)),
                              editorId),
           RdCmdItem(RdPatchCmdFinish(commandId=-N, ... same as start)) ],
 features=[],
 expectedItemVersions=[ RdCmdItemVersion(RdCmdVersion(-P),RdCmdId()), RdPatchEditorVersion(RdEditorVersion(C,H,E),editorId), RdPatchDocumentVersion(RdDocumentVersion(V,H),uniqueId,docId) ],
 updatedItemVersions =[ RdPatchEditorVersion(RdEditorVersion(C+1, H, 122128), editorId) ]))     // no document version in updated: the document did not change
```
Real: click 230 to 143: expected editor `(45, 8653263034029471048, 122308)`, updated `(46, 8653263034029471048, 122221)`.

### Select 40..48 (caret at 48, e.g. double-click word or Shift+arrows)
`RdCaretStateChange(old=<state>, new=RdCaretState(carets=[RdCaret(id, offset=DocOffsetAndVirtualModel(48,0,false), selection=RdSelection(start=(40,0,false), end=(48,0,false)))], documentVersion=(V,H)))`. Editor hash after: `14913904946` (formula in 5.3). Real: caret 143, selection 140..148 gave editor `(47, H, 14938339346)`. Shift+Right extended it with `EditorRightWithSelection`: caret 149, selection 140..149, hash `14938461564`. [Seen]

## 5. Versions and hashes

### 5.1 Document version `RdDocumentVersion(value: Int, hash: Long)` (Seen: `DocumentVersionsKt`, `IncrementalHashKt`, `DocumentVersionHolderImpl`)
All arithmetic is signed 64-bit wrap-around. `mix(h, int v) = h*31 + v`. `mix(h, String s) = fold over chars: h = h*31 + (char code)` (UTF-16 units, no +1).
* Initial (before any text): `RdDocumentVersion(-1, 0)`.
* Reset/full text (`RdResetDocumentChange`): `value = old.value+1`, `hash = mix(127, text)`. Example: the 260-char `Main.java` gives `RdDocumentVersion(0, -5446235163934729545)`; `oldVersion` was `(-1, 0)`. [Seen, verified]
* Incremental change: `value = old.value+1`, `hash = mix(mix(mix(mix(mix(old.hash, offset), moveOffset), oldDocumentLength), oldText), newText)`. Verified: Enter `mix(-5446235163934729545, 210, 210, 260, "", "\n    ") = -6257571537734692227`; `i`: `-6257571537734692227 -> -2740688552363290787`; Backspace: `218028870601022862 -> 8029041787485730192`.
* So the hash is a rolling hash of the whole edit history (not of the text). A client must **replay the same chain** from the version the backend sent in `RdResetDocumentChange.newVersion` (value and hash are given there), and use the backend-sent `newVersion` after each backend change instead of recomputing.
* `RdDocumentChange.oldDocumentLength` = document length before the change. `moveOffset` = `offset` for ordinary edits (differs only for moves of the caret-side of a replace). `modificationKind` = `NotSpecified` in all trace samples; `LeftSide`/`RightSide` appear only for insertions into an empty `oldText` and only affect marker positioning (`VersionsExKt.applyDocumentChange`; text result is the same). `uniqueId` = the document's unique id, equal to the backend's `RdResetDocumentChange.uniqueId` for that document (`"unique-id-backend-1"` in capture-3; `-0` in capture-1). [Seen]
* `RdPatchDocumentVersion(version, uniqueId, id = documentId)` is the document item version used in expected/updated lists.

### 5.2 Editor version `RdEditorVersion(caretModelVersion, documentHash, hash)` (Seen: `EditorVersionsKt`, `EditorVersionHolder`)
* After each caret state the client sends **or applies from the backend**: `caretModelVersion += 1`, `documentHash = state.documentVersion.hash`, `hash = computeHash(state)`. The first caret in `carets` only is hashed.
* `computeHash`: `init = mix(127, caretId.number)` (= `3938` for number 1). If `selection.start.offset != selection.end.offset`: `hash = init*31 + ((init*31+start)*(init*31+end))` (wrap-around), else `hash = init*31 + caret.offset.offset`.
  * caret 210: `3938*31+210 = 122288`. caret 215: `122293`. caret 231: `122309`. caret 143: `122221`. [Seen]
  * selection 140..148: `3938*31+ (122218*122226) = 14938339346` [Seen]. selection 40..48: `14913904946`.
* An edit with two snapshots (before/after) therefore adds 2 to `caretModelVersion` (1 to 3 for Enter, 3 to 5 for `i`, 35 to 37 for Backspace). A pure caret move adds 1 (45 to 46). Select-word then Shift+Right (Change + Snapshot) added 2 (48 to 50). Backend-sent caret snapshots also add 1 each, so always track "the version after the last caret item I sent or received for this editor". [Seen]
* Initial editor version: `RdEditorVersion(-1, -1 doc hash of initial -1/0, -1)` (`getInitialEditorVersion`); the first real value comes from the backend's initial `RdCaretStateSnapshot` (+ `sendInitialCaretState`). [Seen constants; the session's first value is Inferred from caretModelVersion=1 in the first Enter]

### 5.3 Command version `RdCmdItemVersion(RdCmdVersion(hash: Long), id = RdCmdId())`
`RdCmdModelSupport.getPatchCmdVersion(commandId)` returns `RdCmdVersion(hash = commandId.asLong())`. The expected value is the **id of the previously finished command** (`-P`), and no updated entry is sent for it. Trace chain: Enter `-14` expects `-12`; next TypeKey `-16` expects `-14`; its transparent marker `-17` expects `-16`; next `-18` expects `-17` ... Backspace `-50` expects `-48`; TypeKey `-56` expects `-54`. [Seen]

### 5.4 `expectedItemVersions` / `updatedItemVersions` (Seen: `PatchBuilder`, `PatchEngineDocumentSynchronizer.sendDocumentChange`, `PatchEngineEditorSynchronizer.sendChange`)
* `PatchBuilder.pushExpected` is `putIfAbsent` per item id, so `expectedItemVersions` holds the **first (before) version** of every item touched by the patch. `pushUpdated` is `put`, so `updatedItemVersions` holds the **last (after) version**. Maps are insertion-ordered; items: command, editor, document (trace order cmd, editor, document in expected; editor, document in updated).
* Document: expected = `RdPatchDocumentVersion(old, uniqueId, docId)`, updated = new. Only pushed when old != new (no doc entry in updated for caret-only patches).
* Editor: expected pushed for every editor change; updated only if the item is not an `RdTransparentEditorChange` and old != new.
* Command items (`RdCmdItem`) are `RdTransparentItem`: they are ignored when the speculative patch is compared with the backend patch.
* Meaning [Inferred from client conflict checks]: the patch applies only if the receiver's current item versions equal `expectedItemVersions`; afterwards they are `updatedItemVersions`. The client uses the same lists in `FrontendRebaseSession.validateAndUpdateVersions`.

### 5.5 `timestampSetModel` (Seen in trace; only `RdCallEditorActionRequest` has it)
`ActionTimestampSetModel(models=[...])` describes the state the action was invoked in; filtered by `filterOutTransientTimestamps`. Enter at 210 (doc version 0):
```
EditorTimestampModel(editorId, carets=[CaretTimestampModel(
    selection=RangeMarkerTimestampModel(range=RdTextRange(210,210), allowInnerModifications=true, document=DocumentTimestampModel(docId, RdDocumentVersion(0,-5446235163934729545))),
    offset=OffsetTimestampModel(offset=210, isStickingToRight=false, document=DocumentTimestampModel(docId, RdDocumentVersion(0,-5446235163934729545))))])
FileEditorTimestampModel(fileEditorId=RdFileEditorId(compositeId=RdFileEditorCompositeId(fileId=RdFileIdNew(backendId,frontendId,managingFsCreationTimestamp), clientId, index=1, projectId), providerId="text-editor", index=1))
ProjectTimestampModel(projectId)
VirtualFileTimestampModel(fileId)
ControlTimestampModel(controlId=EditorComponentId(textControlId=editorId))
VirtualFileArrayTimestampModel(fileIds=[fileId])
```
Backspace: same with caret at 231 (the caret **before** the action) and doc version 17. [Seen]

## 6. `RdApplyPatchRequest` and the "empty command marker" (Seen)

`RdApplyPatchRequest(patch: RdPatch)` is the generic carrier for changes the frontend made locally with no backend action (`FrontendApplyPatchRequest.createModel`), sent by the document/editor synchronizers when an accumulating session closes (`sendOnClose=true`).

After every TypeKey/Backspace/Enter command finishes, the client also sends a **transparent empty command** (seen 10+ times):
```
RdApplyPatchRequest(RdPatch(
 changes=[ RdCmdItem(RdPatchCmdStart (commandId=-17, commandProjectId=<null>, groupId=<null>, commandName=RdCommandName("#"), confirmationPolicy=DEFAULT, isTransparent=true, commandMeta=[...]))
           RdCmdItem(RdPatchCmdFinish(commandId=-17, commandProjectId=<null>, groupId=<null>, commandName=RdCommandName("#"), confirmationPolicy=DEFAULT, isTransparent=true, commandMeta=[...])) ],
 features=[], expectedItemVersions=[RdCmdItemVersion(RdCmdVersion(-16),RdCmdId())], updatedItemVersions=[]))
```
It advances the "last command id" chain (next real command expects `-17`). Not every command gets one (ids -22, -25, -39, ... were never sent: ids are consumed by IDE commands that produced no patch). [Seen; purpose Inferred]

### Command ids and undo metadata
* `commandId`: `CommandId.asLong()`. Frontend-created commands are **negative**, strictly increasing in magnitude per IDE command executed on the frontend (-14, -16, -17, -18, ...); backend-created commands (undo) are positive (`6` in the undo response). Numbering is not contiguous. [Seen values; counter source Inferred]
* `RdPatchCmdStart/Finish(commandId, commandProjectId: RdProjectId?, groupId: RdCmdGroupId?, commandName: RdCommandName?, confirmationPolicy: DEFAULT, isTransparent, commandMeta: List<RdUndoMeta>)` (types in the model are `RdPatchCmdStart`/`RdPatchCmdFinish` inside `RdCmdItem`; `RdPatchCommandDescriptor*`/`RdCommandItem` also exist but the trace uses the `Cmd` ones). Start and Finish carry the same id and project. `commandName` on Start is the command text and on Finish may be updated (`"#Enter"` then `"#Typing"`). `groupId`: `RdDocGroupId(fileId)` for TypeKey (merges typing into one undo step), `RdDelGroupId()` on Backspace's Finish, `<null>` for Enter, caret moves. The group lets consecutive commands merge into one undo step. [Seen; merge effect Inferred]
* `RdUndoMeta(undoProjectId: RdProjectId?, fileEditorId: RdFileEditorIdStrict, undoableActions: List<RdUndoableAction>, isForcedGlobal: Boolean)`. Always two entries in `commandMeta`: first `undoProjectId=<null>`, second `undoProjectId=<projectId>`; both `fileEditorId=RdFileEditorIdStrict(id=RdFileEditorId(compositeId=RdFileEditorCompositeId(fileId=RdFileIdNew(backendId,frontendId,managingFsCreationTimestamp), clientId, index=1, projectId), providerId="text-editor", index=1), isUnknown=false)`, `undoableActions=[]`, `isForcedGlobal=false`. [Seen, identical in all samples]
* Non-transparent commands carry `commandProjectId=<projectId>`; transparent ones `<null>`.

## 7. Undo / redo: `RdUndoRedoRequest` (Seen request/response; field meaning from code + trace)

```
RdUndoRedoRequest(
  fileEditorId   = RdFileEditorIdStrict(id=RdFileEditorId(compositeId=RdFileEditorCompositeId(fileId, clientId, index=1, projectId), providerId="text-editor", index=1), isUnknown=false),
  fileIds        = [ RdFileIdNew(backendId=5055, frontendId=5055, managingFsCreationTimestamp=1794743650059) ],
  undoStackSizes = [0, 3],     // trace: [global stack size, size for fileIds[0]] [pairing order Inferred from RdStackSizeItem list]
  redoStackSizes = [0, 1],
  isUndo         = true,       // false = redo
  undoProjectId  = RdProjectId("...:...")           // from RdAbstractUndoRequest
)
```
Sent with `retryTimestamp=(0,0)`, **no patch and no speculative change**: the client does not apply undo locally in this setup. The sizes are the client's last known undo/redo stack depths (they come from the `RdStackSizeItem`s the backend sent earlier); the backend presumably rejects/retries when they differ. [Seen request; reject rule Inferred]

Response (Seen, `requestId=90`): `RdSuccessResponse(patch=RdPatch(changes=[RdCmdItem(Start commandId=6, groupId=<null>, commandName="::Undo #Typing"), RdCaretStateSnapshot(wrap=true), RdDocumentChange(offset=229, oldText=";", newText="", oldDocumentLength=280, old (22,8653263034029471048) to new (23,8556325626606790667)), RdCaretStateSnapshot..., RdCaretStateSnapshot..., RdDocumentChange(offset=228, oldText="3", newText="", old (23,..) to new (24,6873874937852610823)), RdCaretStateSnapshot, RdCmdItem(Finish commandId=6), RdStackSizeItem(fileId=<null>, undoSize=0, redoSize=0), RdStackSizeItem(fileId=<file>, undoSize=3, redoSize=1)], features=[RdScrollingRequest(info=ScrollingInfo(kind=Relative, docOffset=228), editorId)], expectedItemVersions=[editor (56,8653263034029471048,122308), doc (22,8653263034029471048)], updatedItemVersions=[editor (60,6873874937852610823,122306), doc (24,6873874937852610823)]))`. The client must apply this patch as a backend change (it was not applied locally). Note two edits were undone by one Cmd+Z (the typed `3;` was one undo group). [Seen]

## 8. What the backend answers and what the client does (Seen)

* Response to TypeKey / CallEditorAction / ApplyPatch: `RdSuccessResponse(patch, requestId, data=[traceparent])`. The patch is the backend's re-execution: the same items as the speculative patch, with command names prefixed `"::"` (`"::#"`, `"::#Typing"`), and, for requests that contain a patch, a leading/trailing extra **pair of `::` cmd items** (ApplyPatch responses contain `Start("::")`, `Finish("::")`, then the original `Start`, items, `Finish`). `expectedItemVersions` / `updatedItemVersions` are the same as in the request. `features` may add `RdScrollingRequest(info=ScrollingInfo(kind=Relative, docOffset=<caret>), editorId)` (typing, undo). [Seen]
* Client logic per response (`SpeculativeQueueProcessor.tryProcessWithoutPhysicalRebase`, `SuccessResponseWrapper.compare`): compare non-transparent changes of its speculative patch with the response patch (`speculativelyEquals`, cmd items ignored):
  * Equals: complete the request; keep the local text; apply only `features` (e.g. scroll) and set status COMPLETED.
  * Equivalent (e.g. same net edit split or merged differently, carets with swapped selection direction): virtual rollback, apply backend response virtually, replay.
  * Different (e.g. auto-closed quote, auto-indent difference): physical rebase: roll back all pending requests in reverse, apply backend patch, complete the request, replay the other pending requests (`rebase`). An independent backend patch (`backendPatches`, `BackendOriginatedChange`) that conflicts with a pending request (`RdPatchUtilsKt.isIntersecting`) also triggers a rebase; if it does not conflict it is applied directly.
  * `RdRetryResponse`: set global `retryTimestamp`, resend all pending requests with it (`JustRetry`).
* Applying backend items (`FrontendRdPatchHandlerRegistry`, `PatchEngineDocumentSynchronizer.receiveDocumentChange/applyChange`, `PatchEngineEditorSynchronizer.receiveSelection`): for each `RdDocumentChange` check `oldVersion == current version` and `oldDocumentLength == document.length` (otherwise it throws "expected document length"), replace `[offset, offset+oldText.length)` with `newText` (if `isWholeTextReplaced == true` the text is `prefix + newText + suffix`), set version to `newVersion`. `RdResetDocumentChange(text, ...)`: if the text differs, `document.setText(text)`; then reset version to `newVersion`. Caret items: apply `state` (`RdCaretStateSnapshot.state` or `RdCaretStateChange.newState`), then bump the editor version as in 5.2; the client checks `computeHash(applied) == computeHash(received)` (logs an error on mismatch). `RdCmdItem`, `RdStackSizeItem` update undo bookkeeping only. [Seen]
* Version conflict handling [Inferred, no backend code here]: the backend validates `expectedItemVersions` against its own current versions; on mismatch the client protocol provides `RdRetryResponse` (retry later with a new `RdRetryTimestamp`) and the client then replays after applying whatever the backend sent first. `RdFailureResponse(message)` / `RdNoEditorFoundResponse` end the request (client throws/recovers: `recoverState`).
* In the capture-3 typing window there were **no** `backendPatches` records; every change came inside `responsesWithTime`. [Seen]

## 9. Minimal client for plain typing (no typing assists, no local speculation required)

Needed messages, in order:
1. Receive `backendPatches` / initial `RdResetDocumentChange` -> remember doc text, `RdDocumentVersion(value, hash)`, `uniqueId`, `documentId`, and the `KernelAwareTextControlId` of the editor; receive initial `RdCaretStateSnapshot` -> caret offset, `RdEditorVersion` (compute from sections 5.2/5.3 starting at the backend's `expectedItemVersions` / `updatedItemVersions`).
2. For each typed char send one `RdRequests([RdRequestHolder(id=++counter, RdTypeKeyRequest(char, editorId, patch), RdRetryTimestamp(0,0), null)], timestamp=nowNanos)` where `patch` = `[RdCmdItem(Start -N "#" groupId=RdDocGroupId(fileId)), RdCaretStateSnapshot(true, caret before), RdDocumentChange(...), RdCaretStateSnapshot(false, caret after), RdCmdItem(Finish -N "#Typing")]` with `expectedItemVersions=[RdCmdItemVersion(-P), editor, doc]`, `updatedItemVersions=[editor(C+2), doc(V+1)]`.
3. Send the transparent `RdApplyPatchRequest` marker (-N-1) after it (optional for the text, but it keeps the `RdCmdVersion` chain the client itself uses; whether the backend demands that chain is Inferred, the real client always follows it).
4. Read `responsesWithTime`. If you applied the edit locally and the response patch matches, nothing more. Otherwise apply the response patch (section 8) to your text and caret, then use its `updatedItemVersions` as the new current versions. Always trust versions in backend patches over your own computed ones.

Plain caret move = one `RdApplyPatchRequest` (section 4). Enter/Backspace = `RdCallEditorActionRequest` with `actionId` `"EditorEnter"` / `"EditorBackSpace"` and `timestampSetModel` (section 5.5); skipping local speculation and sending an empty patch is [Inferred] possible only if the backend does not require the patch (not verified): the client always sends a full speculative patch.

## 10. Not verified (honest gaps)
* Backend code not available: version-mismatch behaviour, whether `char` or `speculativePatch` drives the backend's typing, whether an empty `speculativePatch` is accepted, backend-side id counters.
* `ActionCallPolicy` per-action table (which actions are backend-involved) not read; `FrontendAsyncRawTypedHandlerPolicy` implementations not enumerated.
* `FrontendAsyncRequestExecutorNew` could not be decompiled (Fernflower failure); read through `javap -c`.

## 11. Checked with our client against the Mac backend (2026-11-15)

`client/` implements sections 2 to 7 (`session/EditSession.kt`, `session/VersionHashes.kt`). Every row below was checked with `CLIENT_EDIT_DEBUG=1` on the 260-character `Main.java`; "same" means the backend's answer matched our local patch, so nothing was rolled back.

| Action | What we sent | Backend's answer |
|---|---|---|
| Click at the end of line 7 | `RdApplyPatchRequest [caretChange(210)]` | same |
| Enter | `RdCallEditorActionRequest("EditorEnter") [change(210, +"\n    ")]`, v0→1 | same (also matches the real client's recording) |
| Type `i` | `RdTypeKeyRequest [change(215, +"i")]` | same |
| Backspace | `RdCallEditorActionRequest("EditorBackSpace") [change(215, -"i")]` | same |
| Type `(` | `change(+"(")` | different: the backend also inserts `)`. We roll back and apply the backend's patch |
| Shift+Left twice, then `x` | `RdTypeKeyRequest [change(208, -");"), change(208, +"x")]`, v0→1→2 | same, once we sent **two** changes (see below) |
| Backspace at column 0 | `change(176, -"\n")` | different: IntelliJ also removes the joined line's indent `"    "`. Rolled back, then followed |
| Cmd+Z, Cmd+Shift+Z | `RdUndoRedoRequest` | the answer carries the undo (3 changes undone as one group) and the redo |

Findings:

* **Typing over a selection is two document changes**: delete the selection, then insert, each with its own version. One change `change(208, -");", +"x")` with v0→1 is answered with `change(208, -");", +"")` v0→1 and `change(208, +"x")` v1→2, which counts as different.
* The starting document and editor versions come from the first backend patch (`reset(260 chars, v0)` and a caret snapshot). After that, trust the backend's `updatedItemVersions` over our own count.
* Smart typing (closing brackets, smart backspace, auto-indent beyond copying the indent) is not done locally. The rollback-and-follow path handles it, so the text is always right; the text and caret change again when the answer arrives.
* Highlighters are not resent when the text around them changes: the backend expects the client to move them the way IntelliJ moves a non-greedy range marker. Text inserted exactly at a highlighter's end stays outside it; text inserted exactly at its start moves it. Growing them instead (the first Android test, 2026-11-15) painted a whole new line in the colour of the `;` before it.
* The document hash depends on the edit history, not only the text; `VersionHashesTest` holds recorded numbers.
* cmux-cua's `type_text` sends select-all and delete before the text in our window, so drive typing tests with one `press_key` per character.
