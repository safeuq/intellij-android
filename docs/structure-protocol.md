# Structure tool window

How the Structure tool window gets the structure of the open file in split mode (IntelliJ 263.7307.200), and what
our client does with it (`ClientSession.bindStructure`, `ui/StructureView.kt`). Research 2026-11-16 from bytecode
(`javap -c` of the client and backend jars), then checked live against the Mac backend. Tags: **[seen]** on a live
run, **[bytecode]** read from bytecode, **[inferred]**, **[unknown]**.

## Two paths

| Surface | Path | Evidence |
|---|---|---|
| Structure tool window | rd model `CodeStructureTreeModel`, an extension of `Solution` | [bytecode] the client registers `com.jetbrains.rdclient.fileStructure.ProtocolStructureViewBuilder` as the last `structureViewBuilder` (`intellij.rd.client.xml`); the backend side is `com.jetbrains.rdserver.BackendFileStructureHost` in `intellij.platform.backend.split.jar`. `StructureViewWrapperImpl` never references `StructureTreeApi`. |
| File structure popup (Ctrl+F12), backend-started popups | Fleet RPC `com.intellij.platform.structureView.impl.StructureTreeApi` | [bytecode] only `FileStructurePopup`, `ShowStructurePopupFlowService`, `StructureUiModelSession`, `StructureTreeActionWrapper`, `StructurePopupProviderImpl` (`intellij.platform.structureView.frontend.jar`) use it; registry keys `frontend.structure.popup` and `remoteDev.frontend.structure.popup` default to true |

[inferred] The client takes the rd path for Java because `JavaStructureViewBuilderFactory` is only in the backend's
`plugins/java/lib/modules/intellij.java.structureView.jar`, not in the client distribution.

Our client uses the rd path, like the real tool window.

## The rd model [bytecode]

`CodeStructureTreeModel` (`intellij.rd.ide.model.generated.jar`), getter `Solution.codeStructureTreeModel`:

| Member | Type | Written by | Meaning |
|---|---|---|---|
| `toolWindowVisible` | `IOptProperty<Boolean>` | client | the Structure tool window is showing; the backend builds trees only while it is |
| `popupVisible`, `navbarVisible` | `IOptProperty<Boolean>` | client | declared, unused in this build |
| `pingLoadingToBackend` / `pingLoadingToFrontend` | `ISignal<Boolean>` | both | declared, unused |
| `buildForDocument` | `ISignal<RdDocumentId>` | client | declared, unused: the frontend never fires it, the backend doesn't listen |
| `treeNodes` | `IOptProperty<CodeStructureTreeNodesList>` | backend | the tree; set again after every change |
| `navigateTo` | `ISignal<CodeStructureNavigateTo>` | client | `{node: <id>, requestFocus, documentId}`; the backend navigates to that element |

```
CodeStructureTreeNodesList { structureType: Local | Full, documentTimestamp: StructureTimestamp? { documentId, version }, treeNodes }
CodeStructureTreeNode { id, children: List<Int>, filters, searchableTexts, navigable, offset, endOffset,
                        name, shortName, icon: IconModel?, declarationRange: RdTextRange?, isFromRelatedFile }
```

- `children` are **indexes into `treeNodes`**, not ids. `treeNodes[0]` is the root, the list is in pre-order.
- `id` comes from one counter per client session that never resets, so ids change on every rebuild. Our view
  remembers collapsed and selected rows by their name path (`/Main.java/Main/main(String[]): void`) instead.
- `name` is the presentable text, `shortName` the short text (falls back to `name`); the real client shows `shortName`.
- `offset` is the element's start; **`endOffset` is written from the start offset too** (both are the start). Use
  `declarationRange` for a range. `offset` is -1 when the element isn't a PSI element.
- `filters` is always empty in this build; `isFromRelatedFile` isn't set by this host (the frontend drops such nodes).
- The real client's tool window does **not** follow the caret (`getCurrentEditorElement()` returns null).

## What happens on a live run [seen, 2026-11-16 ~14:32 IST, Mac backend, `smoke/src/demo/Main.java`]

1. The client calls `solution.codeStructureTreeModel` (this binds the extension), advises `treeNodes`, and sets
   `toolWindowVisible` when the tool window shows.
2. **The first answer is empty**: `treeNodes = []`, `documentTimestamp = null`. The backend log says
   `BackendFileStructureHost - Sending file structure to frontend`. In the bytecode, `BackendFileStructureHost$2$1$1`
   sends `emptyStructureModel()` when its `selectedEditorFlow` (a `MutableStateFlow<SelectedEditor?>`) is null. That
   flow is set only by `MyListener.selectionChanged`, a `FileEditorManagerListener` for non-local client sessions.
   Our client fired its one `fileEditorsModel.selectionChanged` when the file opened, and the backend refused it.
   The backend log said why (seen 15:46–15:51 IST):

   ```
   WARN - #c.j.r.f.BackendFileEditorHost - Attempt to select already gone editor: RdFileEditorId(compositeId=… index = 2 …, providerId=text-editor, index=1)
   INFO - #c.j.r.f.GuestFileEditorManager - Changing selected editor ClientId(…): old Editor: …/Main.java, new null
   ```

   Two causes. **Timing**: the client selects the editor as soon as `requestComposite` answers, ~0.2 s before the
   backend has made the editor; the same select sent once `backendDeferredComposites[id].model` arrives works.
   **Wrong index**: the client built the text editor's id with index 1, but the index is the composite's: the
   first file opened gets composite index 1 and `text-editor` index 1, the second gets 2 and 2
   (`FileEditorCompositeModel.editors`). So for every file after the first, the selection never worked, and the
   Structure tool window showed "Loading…" for it (the user's report, 2026-11-16, on `ConfigurationManager.java`).
   The same index went into `EditorIdentity.fileEditorId`, which the client sends with editor actions.
3. **Fix**: the text editor id uses the composite's index (`EditorIdentity.textEditorId`), and the client sends
   `selectionChanged(Frontend, old = null, new = <editor id>)` again when the composite's model arrives. The backend
   then logs `Changing selected editor: old null, new Editor: …/Other.java` and sends the tree within ~0.2 s.
   (A first version sent the selection again whenever the tool window opened; that only hid the timing problem for
   the first file, and is gone.) For `Main.java`:

   | index | shortName | children | icon paths |
   |---|---|---|---|
   | 0 | `Main.java` | `[1]` | Java file |
   | 1 | `Main` | `[2]` | class |
   | 2 | `main(String[]): void` | `[]` | method, with an `EmptyIconModel` (16 × 16) slot in its `RowIconModel` |

   So for Java the root is the file node.
4. Typing `int count` into the class body: a new `treeNodes` with 4 nodes, `count: int` before `main`.
   Opening `Other.java` (`count: int`, `next(): int`) and `Util.kt` (`twice(Int): Int`, `Holder` with
   `constructor Holder(String)` and `name: String`): each gets its tree when it becomes the selected editor.
5. A tap on a node: our client moves the caret itself to `offset` with the editing protocol (it doesn't fire
   `navigateTo`), so it works without a round trip and keeps our caret model in charge.

## The Fleet RPC path (the Ctrl+F12 popup) [bytecode, not used yet]

Kept for later: it adds caret following, sorters and filters. Nothing below has been seen on the wire.

### 2.1 Service name and methods [bytecode: `StructureTreeApi$_generated_RemoteApiDescriptor`]

Service string = `getApiFqn()` = the interface's full name. Same rule as completion (`RpcCompletionService` is written as its FQN in `Completion.kt`'s `SERVICE`):

```kotlin
private const val SERVICE = "com.intellij.platform.structureView.impl.StructureTreeApi"
```

The `@Rpc` interface `StructureTreeApi : RemoteApi<Unit>` (in `intellij.platform.structureView.jar`). All methods are `suspend`. Argument names are the Kotlin parameter names (the descriptor's `ParameterDescriptor` strings):

| method | args (name: type) | result | kind |
|---|---|---|---|
| `createStructureViewModel` | `projectId: ProjectId`, `id: StructureViewDtoId`, `fileEditorId: FileEditorId`, `fileId: VirtualFileId` | `StructureViewModelDto` | plain call; the DTO holds a nested stream (`nodes`) |
| `structureViewModelDisposed` | `projectId`, `id` | `Unit` (`{}`) | plain call |
| `setTreeActionState` | `projectId`, `id`, `actionName: String`, `isEnabled: Boolean`, `autoClicked: Boolean` | `Unit` | plain call |
| `getNewSelection` | `projectId`, `id` | `Int?` (node id of the element at the backend caret; `-1`/null meaning unknown **[unknown]**) | plain call |
| `navigateToElement` | `projectId`, `id`, `elementId: Int` | `Boolean` | plain call |
| `getShowPopupRequestFlow` | none | `Flow<ShowStructurePopupRequest>` | stream result, like `responseFlow` in `Completion.kt` (`fleet.subscribe`) |

The frontend wraps `createStructureViewModel` in `durable { }` (retries on connection loss) [bytecode `initializeWithRpcModel$1`]. Plain `call` is enough for us.

### 2.2 Order the frontend uses [bytecode: `StructureUiModelSession`]

1. Pick a model id: `StructureViewDtoId(nextId.incrementAndGet())`, a static `AtomicInteger` on the client. We choose any int that is unique per open model, e.g. `1`.
2. `createStructureViewModel(projectId, id, fileEditorId, fileId)` -> `StructureViewModelDto`. Build `fileEditorId`/`fileId` from the selected file (2.3).
3. `initActions(dto)`, then collect `dto.nodes` (an `RpcFlow<TreeNodesDto>`) for as long as the model is open. Each emission is applied with `applyNodesModel(rootNode, nodes, nodeProviders, deferredNodes, editorSelectionId)` [bytecode `initializeWithModel$2`].
4. Optional: `handleDeferredNodeProviders` awaits `TreeNodesDto.deferredProviderNodes` (extra nodes from node providers such as "inherited members"). A minimal client can ignore this (see 6).
5. Caret follow: `getNewSelection(projectId, id)` when the editor caret moves; initial value arrives in `TreeNodesDto.editorSelectionId`.
6. Click on a node: `navigateToElement(projectId, id, elementId)`.
7. Filter/sorter checkbox: `setTreeActionState(projectId, id, actionName, isEnabled, autoClicked)` (the backend re-runs the tree and `nodes` emits again).
8. Close: `structureViewModelDisposed(projectId, id)` (frontend calls it from `registerModelDisposal`, i.e. when the popup/model is disposed).

### 2.3 Identifiers: what to pass and what we already have

| Param | JSON | Source |
|---|---|---|
| `projectId` | `{"id":"m3nniruk9k7j4qsibjui"}` | Same value as `Completion.kt`'s `kernelProjectId` (`RdCodeWithMeProject.kernelProjectId`), same shape as the `id(...)` helper there. [bytecode: `ProjectId{ id: fleet.util.UID }`; docs/completion-protocol.md 2.1 says the UID is a string] |
| `id` (`StructureViewDtoId`) | `{"id":7}` | client chosen int [bytecode: field `id: Int`] |
| `fileEditorId` (`FileEditorId`) | `{}` | **No serialized fields**: its only field `localKey: FileEditor` is `@kotlinx.serialization.Transient` [bytecode: `$$serializer` descriptor has 0 elements]. On the wire it is an empty object. The backend does `fileEditorId.fileEditor()` (null on the wire) and falls back to `FileEditorManager.getSelectedEditor(virtualFile)` [bytecode `StructureTreeApiImpl.createStructureViewModel`]. So we do **not** need an editor id; the file must be the selected one on the backend. |
| `fileId` (`VirtualFileId`) | `{"serializedValue": <SerializedValue>}` | See below. This is the hard part. |

`VirtualFileId` [bytecode: `VirtualFileIdKt.rpcId` -> `CustomTypeRpcSerializer` -> `FrontendVirtualFileRpcSerializer` (frontend split jar)]: `serializedValue` is a `fleet.openmap.SerializedValue` holding a `fleet.rpc.core.Blob` (bytes) written with `BlobSerializer`. The bytes are the rd `RdFileId` written polymorphically: `Serializers(IdeRootMarshallersProvider).writePolymorphic(SerializationCtx(..., StableIdentities), UnsafeBuffer, rdFileId)` (class `FrontendRdFileIdSerializer`). In JSON that is a base64 string, like `IconId.serializedValue` (same blob kind; `Completion.kt iconPath()` decodes one) [inferred for the exact JSON text; not seen on the wire for this type].

* The `RdFileId` is the one the host gave us in the project tree / editor models (`ThinClientVirtualFile.getFileId()`). Our client already holds it as an rd object for each opened file, so it can serialize it with the same rd `Serializers` it already has.
* For frontend-owned files the real client sends `RdFileIdOld("frontend-owned-backend-mirror", path)`. Not relevant for project files.
* Backend decoding is `BackendRdFileIdSerializer` / `BackendVirtualFileRpcSerializer` (backend split jar), the mirror of the above.

Example call as our `FleetRpc.call` would send it (args object; `fileId` bytes are a placeholder):

```json
{
  "projectId": {"id": "m3nniruk9k7j4qsibjui"},
  "id": {"id": 1},
  "fileEditorId": {},
  "fileId": {"serializedValue": "AAAAAQ...base64 of rd RdFileId..."}
}
```

---

### 3. Result DTOs (path B) [bytecode: `$$serializer` descriptors in `intellij.platform.structureView.jar`]

Json settings are the Fleet RPC ones in docs/completion-protocol.md 4.4: `classDiscriminator="type"`, `encodeDefaults=true`, `ignoreUnknownKeys=false`. Field names below are exactly the kotlinx serial names.

```
StructureViewModelDto {
  rootNode: StructureViewTreeElementDto,
  nodes: RpcFlow<TreeNodesDto>,          // on the wire: a stream id string (UID); see 4.1
  smartExpand: Boolean,
  minimumAutoExpandDepth: Int,
  rootNodeShown: Boolean,
  actions: List<StructureTreeActionDto>  // sorters, filters, node providers
}

TreeNodesDto {
  editorSelectionId: Int?,               // node id for the editor caret
  nodes: List<StructureViewTreeElementDto>,
  nodeProviders: List<NodeProviderNodesDto>,
  deferredProviderNodes: Deferred<DeferredNodesDto>,   // wire form [unknown]
  rootNode: StructureViewTreeElementDto
}

StructureViewTreeElementDto {
  id: Int, parentId: Int, index: Int,    // index = position among the parent's children
  speedSearchText: String,
  presentation: PresentationDataDto,
  autoExpand: Boolean, alwaysShowsPlus: Boolean, alwaysLeaf: Boolean,
  filterResults: List<Boolean>           // one per filter action, same order as `actions`
}

PresentationDataDto {
  icon: IconId?,                         // {"serializedValue": <base64 blob>}; path via Completion.kt iconPath()
  presentableText: String?, locationString: String?,
  textAttributesKey: TextAttributeKeyId?,  // {"serializedValue": ...} opaque
  locationPrefix: String?, locationSuffix: String?,
  coloredText: List<ColoredFragmentDto>
}
ColoredFragmentDto { text: String, tooltip: String?, attributes: SerializableSimpleTextAttributes }
SerializableSimpleTextAttributes { bgColor: ColorId?, fgColor: ColorId?, waveColor: ColorId?, style: Int }   // ColorId = {"serializedValue": ...}

NodeProviderNodesDto { providerName: String, nodes: List<StructureViewTreeElementDto> }
DeferredNodesDto     { nodeProviders: List<NodeProviderNodesDto>, nodes: List<StructureViewTreeElementDto> }

StructureTreeActionDto  (sealed interface, discriminator "type" = subclass serial name, [inferred] the full class name)
  FilterTreeActionDto            { order: Int, actionType, name, presentationDto: TreeActionPresentationDto, isReverted, isEnabledByDefault, shortcutsIds: [ShortcutId], actionIdForShortcut, checkboxText }
  SorterTreeActionDto            { actionType, name, isReverted, presentationDto, isEnabledByDefault }
  NodeProviderTreeActionDto      { actionType, name, presentationDto, isReverted, isEnabledByDefault, shortcutsIds, actionIdForShortcut, checkboxText }
  DelegatingProviderTreeActionDto{ actionType, name, isReverted, presentationDto, shortcutsIds, actionIdForShortcut, checkboxText, isEnabledByDefault }
  actionType: enum "GROUP" | "SORTER" | "FILTER"
TreeActionPresentationDto { icon: IconId?, text: String, description: String? }
```

Notes:

* Nullability is [inferred] from the Kotlin types shown by `javap` as `Integer` / `String`; a non-null `String` field in the class could still be nullable in Kotlin (javap does not show it). Always read defensively; the host rejects unknown keys only on input, so for results we can ignore extras.
* Ordering: children of a parent are ordered by `index` ascending [inferred]. `parentId` of the root element is not seen **[unknown]** (probably `-1`). The tree is flat; rebuild by `parentId`.
* "Always leaf" = `alwaysLeaf`; "has children but not loaded" = `alwaysShowsPlus`.
* Icons: `PresentationDataDto.icon` and the action icons are `IconId`. `Completion.kt iconPath()` already turns one into a path like `nodes/class.svg` (first string ending in `.svg`).

#### 3.1 Worked example (path B), `class Greeting { String name; void hello() {} }`  [illustrative: shape from bytecode, texts as IntelliJ's Java structure shows them, ids made up, blobs shortened]

Result of `createStructureViewModel` (`call_result`):

```json
{
  "rootNode": {"id":1,"parentId":-1,"index":0,"speedSearchText":"Greeting.java",
               "presentation":{"icon":{"serializedValue":"...java file..."},"presentableText":"Greeting.java","locationString":null,"textAttributesKey":null,"locationPrefix":null,"locationSuffix":null,"coloredText":[]},
               "autoExpand":true,"alwaysShowsPlus":false,"alwaysLeaf":false,"filterResults":[true,true]},
  "nodes": "b4a6dh7tbi3ojtkuu02q",
  "smartExpand": true,
  "minimumAutoExpandDepth": 1,
  "rootNodeShown": true,
  "actions": [
    {"type":"com.intellij.platform.structureView.impl.uiModel.SorterTreeActionDto","actionType":"SORTER","name":"ALPHA_SORTER","isReverted":false,"isEnabledByDefault":false,"presentationDto":{"icon":{"serializedValue":"..."},"text":"Sort Alphabetically","description":null}},
    {"type":"com.intellij.platform.structureView.impl.uiModel.FilterTreeActionDto","order":0,"actionType":"FILTER","name":"SHOW_FIELDS","isReverted":true,"isEnabledByDefault":true,"presentationDto":{"icon":null,"text":"Fields","description":null},"shortcutsIds":[],"actionIdForShortcut":"","checkboxText":"Show Fields"}
  ]
}
```

First element on the `nodes` stream (`stream_data`, `data`):

```json
{"editorSelectionId":3,
 "rootNode":{"id":1,"parentId":-1,"index":0,"speedSearchText":"Greeting.java","presentation":{"presentableText":"Greeting.java","coloredText":[]},"autoExpand":true,"alwaysShowsPlus":false,"alwaysLeaf":false,"filterResults":[true,true]},
 "nodes":[
  {"id":2,"parentId":1,"index":0,"speedSearchText":"Greeting","presentation":{"presentableText":"Greeting","coloredText":[]},"autoExpand":true,"alwaysShowsPlus":false,"alwaysLeaf":false,"filterResults":[true,true]},
  {"id":3,"parentId":2,"index":0,"speedSearchText":"name: String","presentation":{"presentableText":"name: String","coloredText":[]},"autoExpand":false,"alwaysShowsPlus":false,"alwaysLeaf":true,"filterResults":[true,true]},
  {"id":4,"parentId":2,"index":1,"speedSearchText":"hello(): void","presentation":{"presentableText":"hello(): void","coloredText":[]},"autoExpand":false,"alwaysShowsPlus":false,"alwaysLeaf":true,"filterResults":[true,true]}
 ],
 "nodeProviders":[],
 "deferredProviderNodes": <[unknown] wire form>}
```

Whether `nodes` contains the root too, and whether `nodes` always lists the whole tree or only expanded levels, is **[unknown]**; `applyNodesModel` takes the root separately plus a flat list, and there is no "removed nodes" field, so each emission is a full replacement [inferred].

---

### 4. Updates, streams, caret, close (path B)

#### 4.1 Updates
`StructureViewModelDto.nodes` is a `Flow` that re-emits a full `TreeNodesDto` whenever the backend recomputes the tree (file edit, action state change). It sits **inside** the result object, so the stream id is `result.nodes`, a string. Our `FleetRpc.subscribe` only handles a result that is itself the stream id. Needed change (small, mirrors the existing code): after `call(...)`, read `result["nodes"]`, register an `IncomingStream` under that id, then send `stream_init` for it; stream budget and `stream_next` handling are unchanged. [inferred: matches `Completion.kt`/doc 4.5 where a `Flow` at any position is a stream id string]

#### 4.2 Selection follows caret
`getNewSelection(projectId, id)` returns the id of the node at the backend's caret. The popup calls it after updates and caret moves. The backend's caret is the one our client sends over rd; call it after a caret move and after each `nodes` emission. Also `editorSelectionId` in each `TreeNodesDto`. [bytecode for signature and use; the debounce the frontend uses is not checked]

#### 4.3 Close
`structureViewModelDisposed(projectId, id)`; then drop the stream (close handling is the existing `stream_closed`). If we forget, the backend entry (`BackendStructureTreeService.disposeStructureViewModel`) stays until the project closes [inferred].

#### 4.4 Backend started popups
`getShowPopupRequestFlow()` is a stream of `ShowStructurePopupRequest { projectId, modelId: StructureViewDtoId, model: StructureViewModelDto, fileId: VirtualFileId, title: String, received: SendChannel<Unit> }` for actions started on the backend. Not needed for a tool window.

---

### 7. Path A vs path B

| | A rd model | B Fleet RPC |
|---|---|---|
| Used by real client for | Structure tool window | Ctrl+F12 popup, backend-started popups |
| Wire | rd binary (already in our client) | JSON (existing `FleetRpc`) |
| File identity | backend's selected editor | `VirtualFileId` blob + selected editor fallback |
| Caret follow | no | `getNewSelection` |
| Filters / sorters | no (`filters` always empty) | yes (`actions`, `setTreeActionState`) |
| Presentation | text + icon | text, location, colored fragments, attributes |
| Updates | `treeNodes` re-set on PSI change | `nodes` stream re-emits |
| Dispose | stop by `toolWindowVisible = false` | `structureViewModelDisposed` |

### 8. Items to confirm on the first live run
1. ~~Path A fills `treeNodes`~~: answered above (yes, after the selection is sent again).
2. ~~Root node for Java~~: answered above (the file node).
3. Path B: real JSON of `VirtualFileId`, root `parentId`, whether `nodes` repeats the root, wire form of `deferredProviderNodes`.
4. Record the real JetBrains Client doing Ctrl+F12 with the `protocol.*` trace, as was done for completion, to compare.
