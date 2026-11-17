# Pending items

The one list of open work for the backend (this repo) and the IDEate client
([safeuq/ideate-editor](https://github.com/safeuq/ideate-editor)). Whoever finishes, starts or finds an item
updates this file. Work in ideate-editor needs a second commit here: one line, with the ideate-editor commit.

Owners: **ui** is the `intellij-android-ui` session, **runtime** the `intellij-android` session, **user** needs
a decision or an action from the user. Docs named without a repo are in ideate-editor.

## Open

| # | Item | Owner | Status | Details |
|---|---|---|---|---|
| 11 | Backend dialogs, the rest: New ▸ File/Java Class/… (its name popup comes as `uiAutomationInteractionModel.activePopups` BeControl, not Lux), a dialog's own popups (Scope combo list, `LxWindow` popups untested), paste into a dialog field through the backend clipboard, and on Android the soft keyboard opening only for dialogs with a text field | — | open | `docs/lux-protocol.md` section 8 |

## Known limits (nothing to do now)

| Item | Why | Details |
|---|---|---|
| Structure of a file with several views (Spring's Logical/Physical) shows only the first | `CodeStructureTreeModel` carries one view; JetBrains Client doesn't switch either | `docs/structure-protocol.md` |

## Done (2026-11-17)

| Item | Commit |
|---|---|
| Known limit removed: the SSH host key dialog now has Gateway's title "Connecting To Remote Host" (from the user's Gateway screenshot) | ideate-editor 2cfc803 |
| No foreground service or connection notification: with the screen off Doze may drop the connection; nothing is tried in the background, and it reconnects as soon as the app is back (user: "the intent is to make the reconnection seamless subject to standard doze rules") | ideate-editor 0df165d |
| #12: reopen the editors open when the project was last left, as Gateway does (the client keeps the list per project, `OpenEditors.kt`; reconnects too). Before/After https://claude.ai/artifact/FEBAVGAHSP2MgniyYEh4fo | ideate-editor bdc23a8 |
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
