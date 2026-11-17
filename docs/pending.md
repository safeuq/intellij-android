# Pending items

The one list of open work for the backend (this repo) and the IDEate client
([safeuq/ideate-editor](https://github.com/safeuq/ideate-editor)). Whoever finishes, starts or finds an item
updates this file. Work in ideate-editor needs a second commit here: one line, with the ideate-editor commit.

Owners: **ui** is the `intellij-android-ui` session, **runtime** the `intellij-android` session, **user** needs
a decision or an action from the user. Docs named without a repo are in ideate-editor.

## Open

| # | Item | Owner | Status | Details |
|---|---|---|---|---|
| 3 | Search field's history popup (`SearchTextField(history = true)` shows the icon only) | runtime | in progress: built, emulator check next | 04f0664 |
| 7 | Backend dialogs (Lux). Until then menu items that open a dialog stay greyed: Rename, New, Delete, Reformat Code, Find Usages, Paste from History, … Researched: Lux is a remote canvas (draw operations), the backend binds it only after the client sends `graphicsDevices`, which IDEate doesn't yet. Next: a recording spike (attach Lux, log what Rename sends), then a Canvas replay renderer and input | ui | researched | `docs/lux-protocol.md` (ideate-editor 1ce8a87) |
| 8 | Paste from the Android soft keyboard (an `InputConnection` that takes `commitText`) | runtime | in progress: built, emulator check next | `docs/edit-protocol.md`, "Pasting from the Android soft keyboard" |

## Known limits (nothing to do now)

| Item | Why | Details |
|---|---|---|
| Structure of a file with several views (Spring's Logical/Physical) shows only the first | `CodeStructureTreeModel` carries one view; JetBrains Client doesn't switch either | `docs/structure-protocol.md` |
| The SSH host key dialog's title is "SSH" | Gateway's own title isn't in the bundles found | `connect/SshPage.kt` |

## Done (2026-11-17)

| Item | Commit |
|---|---|
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
