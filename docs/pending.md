# Pending items

The one list of open work for the backend (this repo) and the IDEate client
([safeuq/ideate-editor](https://github.com/safeuq/ideate-editor)). Whoever finishes, starts or finds an item
updates this file. Work in ideate-editor needs a second commit here: one line, with the ideate-editor commit.

Owners: **ui** is the `intellij-android-ui` session, **runtime** the `intellij-android` session, **user** needs
a decision or an action from the user. Docs named without a repo are in ideate-editor.

## Open

| # | Item | Owner | Status | Details |
|---|---|---|---|---|
| 16 | Title bar, still to check on macOS: dragging, double-click zoom and full screen (traffic lights hidden). Android 16 desktop windowing checked on the emulator | user | waiting | docs/intellij-look.md section 15 |

## Known limits (nothing to do now)

| Item | Why | Details |
|---|---|---|
| Structure of a file with several views (Spring's Logical/Physical) shows only the first | `CodeStructureTreeModel` carries one view; JetBrains Client doesn't switch either | `docs/structure-protocol.md` |

## Done (2026-11-17)

| Item | Commit |
|---|---|
| Phone upright (user request): a page at rest with one island draws it flush with the screen's sides and square; the gap and rounded corners come back, animated, while the pages move and while the page is split. APK installed on the phone and the tablet (not launched). Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor ece876e |
| Android system bars (user request): the app's backgrounds behind the status and navigation bars on every version with light icons, no 3-button scrim, cutout filled, `Theme.Ideate` (no startup flash); the project tint runs up behind the status bar. Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor 50f29cf, 4646585 |
| Pane layout part B (user spec, reviewed as https://claude.ai/artifact/ChLM2aSm57qt7EESoPh3zR): windows go in six places from side and split flag, Structure under Project; upright: split side pages, bottom windows in the editor's place, six-group bar, back chevron at the toolbar's right end; desktop and tablet sideways: bars on both sides, one side pane, bottom area under the editor; phone sideways: one left bar, editor plus one window; all splits through part A's `SplitPane`. APK installed on the phone and the tablet (not launched). Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor 65ada5f, 799e7a7 |
| Pane layout part A (shared with runtime): `SplitPane` borders drag as IntelliJ's `Splitter` (60 dp least, normalizeWeight, finger pill, mouse resize pointers); `PaneWeights` saved per project next to the open editors. Finger pill not yet checked on a device | ideate-editor 932b77f |
| Toolbar dropdowns (project widget, Current File) get IntelliJ's box: toolbar-button high, padding 10/6, hover fill; without the merged title bar the widget lines up with the panels (after the tool window bar in landscape); selection band 9 dp in, leaving Gateway's gap before the root's ▾ (user report with Gateway and IDEate screenshots). Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor 9164a2c |
| `desktop:run` runs the app on the JetBrains Client's own JBR, so the merged title bar works whatever JDK Gradle uses (user saw the native title bar) | ideate-editor 754d74e |
| #14, #15: the tool window bar shows the backend's tool windows (`solution.toolWindowModel`) in the new UI's default order, with ⋯ for the other available ones; plugin icons from the SVG the backend sends; the Project header is a "Project ▾" selector (Project, Packages, each scope), Packages and the scopes drawn from the backend's BeTreeGrid; files the backend opens get their tab name from `vfsModel.findFile`. Not yet: icons in Packages and the scopes (none sent), the user's own layout (`docs/tool-windows.md`). Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor 9fe1d06 |
| Main toolbar drawn in the title bar (user request): macOS through JBR's `CustomTitleBar` (traffic lights in the toolbar row, controls hit-tested), Android desktop windowing through a transparent caption bar (API 35+); IntelliJ's project gradient behind the toolbar. Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor 09ea620 |
| Tool window bar, tree indent, git colours and titles as Gateway shows them (user report with Gateway and IDEate screenshots): bar buttons 32 with the highlight inside (24, `#FFFFFF29`, radius 4), bar 35; Project and Structure trees 3 dp less indented, text 3 after the icon; ignored/unversioned names in the scheme's file status colours, excluded rows on their scope's file colour; bold tool window titles; avatar initials as IntelliJ's (`xtool` → `X`). Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor 9ebbcd4 |
| New ▸ Java Class: the kind list (Class, Interface, Record, Enum, Annotation, Exception; `BeTreeGrid`) under the name field; Up/Down through the backend's listeners, a tap selects | ideate-editor a9e8be8 |
| #13: reopened files' carets put back (saved per file, also 1 s after edits and caret moves; restored once the caret version is in); each tab keeps its own scroll. Exact scroll position not kept: the caret line shows 5 lines below the top | ideate-editor 8029cee |
| 11b: backend dialogs over Lux, the rest: a dialog's own popups open under their combo and take taps; paste goes in as one input method commit from the device's clipboard; the soft keyboard opens only for a focused text field (`inputLocation`). Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor db50292, cf48962 |
| New ▸ File, Directory and Java Class name popups (BeControl `activePopups`), with the backend's "already exists" hint (a Lux panel inside the popup, `BeneluxView`); New un-greyed in the Project tree menu. Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor 0df7356, c00fe77 |
| Known limit removed: the SSH host key dialog now has Gateway's title "Connecting To Remote Host" (from the user's Gateway screenshot) | ideate-editor 2cfc803 |
| No foreground service or connection notification: with the screen off Doze may drop the connection; nothing is tried in the background, and it reconnects as soon as the app is back (user: "the intent is to make the reconnection seamless subject to standard doze rules") | ideate-editor 0df165d |
| #12: reopen the editors open when the project was last left, as Gateway does (the client keeps the list per project, `OpenEditors.kt`; reconnects too). Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor bdc23a8 |
| Screen off: a foreground service keeps the connection (20 min locked on the phone, still connected); a lost connection reconnects under "No connection to IDE Backend" for 2 minutes; Back from the editor goes to the connections screen; "Stopping IDE Backend" popup (user report "Broken windows"). Before/After https://claude.ai/artifact/3pFKPSVPBhWN4BSsn7eycG | ideate-editor 6053d80 |
| Start popup: IntelliJ's indeterminate bar (DarculaProgressBarUI's moving gradient); on narrow screens 16 dp from the sides with Cancel under the text at the right end (user report with a phone screenshot) | ideate-editor ac452cd |
| Tree selection band: square where it meets the panel edge after a sideways scroll, as IntelliJ's viewport clips it (user report with a screenshot) | ideate-editor cab1b59 |
| SSH host key question matches Gateway's: title "Connecting To Remote Host", `ssh-ed25519`, MD5 colon-hex fingerprint, OK / Cancel with Cancel focused; `MessageDialog(focused = …)` (user report with Gateway's screenshot) | ideate-editor 2cfc803 |
| Backend dialogs over Lux: Rename, Delete (alert and Safe Delete), Reformat/Optimize options and other dialog actions now show and work on desktop and Android (painter by runtime, attach/window/input by ui) | ideate-editor b41edc0, a4ad924 |
| #10 (runtime): the completion popup closes when the editor loses focus, so it no longer floats over a tool window page on a phone held upright; also checked Gboard's Text Editing Paste (`performContextMenuAction`) | ideate-editor 65394f5 |
| Paste from the Android soft keyboard: input connection with text input, Gboard's clipboard clips pasted, resumed words handled (`docs/edit-protocol.md`) | ideate-editor 4bfde74 |
| Search field's history popup (5 recent searches, newest first; shared on the connect screen); `TextField` keeps the caret at the end of text set from outside | ideate-editor a50902f |
| SSH hosts ask "Take over?" when a running backend has another client; tested from the emulator (Cancel keeps the other client, Take over disconnects it) | ideate-editor 488ad12 |
| Shared components tried on Android (emulator: Link, SSH page, Connect to SSH, host key question, Choose IDE and Project) and the 6d52c3e build installed on the phone (app only, not launched) | ideate-editor 6d52c3e (APK at `JBR/ideate-debug.apk`) |
| SSH open-project end to end from Android (emulator → Mac): Keystore-held IDEate key, full BouncyCastle, host key question, launch, port forward, project open | ideate-editor 9d53776 |
| Status files of scratchpad test projects deleted from `~/Library/Caches/JetBrains/RemoteDev/recent/` (they named the user's real IntelliJ). Test backends write new ones each run: after every test, delete those whose pid has stopped | ideate-editor 9d53776 (test hygiene note) |
| Shared UI components, last pieces: `Link.kt` and its callers, `IslandsDark.link` = text-link `#71A1FE`, failure text `textError`, folder chooser border/separator from the theme, project avatar gradients, gallery focus samples | ideate-editor 6d52c3e |
| Stripe selected button stays `#393B40`: the user's IntelliJ draws an open, unfocused tool window PUSHED (transparent white over the stripe); the blue `selectedBackground` is only for the focused one (`docs/ui-components.md`) | ideate-editor 6d52c3e |
| Test SSH container `ideate-sshd` stopped (`SshSessionTest` needs it only with `IDEATE_SSH_TEST_*` set) | — |
| Build reads `jetbrains.client.lib` from local.properties, as the user's checkout does | ideate-editor b35ff33, 2b38c4e |
| "8 s before the host key question": 590 ms in the app; the delay was the test tool (cmux) | ideate-editor c54ac36 |
| SSH: open a project end to end on the Mac (Remote Login), host key question as IntelliJ's message dialog, SSHJ key exchange timeout | ideate-editor 32b2677 |
| Shared UI components, steps 1 to 9 (theme, Button, TextField, RadioButton/Checkbox, IconButton, PopupContainer/Menu, LinearProgressBar, SearchTextField, DialogWrapper) | ideate-editor 6d43a84 … 04f0664 |
| Context menus from the backend: Project, Structure, editor | ideate-editor 0a42d25, bb3bc92 |
