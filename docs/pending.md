# Pending items

The one list of open work for the backend (this repo) and the IDEate client
([safeuq/ideate-editor](https://github.com/safeuq/ideate-editor)). Whoever finishes, starts or finds an item
updates this file. Work in ideate-editor needs a second commit here: one line, with the ideate-editor commit.

Owners: **ui** is the `intellij-android-ui` session, **runtime** the `intellij-android` session, **user** needs
a decision or an action from the user. Docs named without a repo are in ideate-editor.

## Open

| # | Item | Owner | Status | Details |
|---|---|---|---|---|
| 1 | Shared UI components, last pieces: `component/Link.kt` and its callers (project paths, status links, the folder chooser's Show/Hide path); `IslandsDark.link` to the theme's text-link `#71A1FE` (now the measured `#538AF9`); ClientScreen's failure text to `textError`; folder chooser tree border `#2B2D30` → `#26282C` and separator → `ToolBar.separatorColor`; project badge colors; the gallery's focus samples clash | runtime | in progress | `docs/ui-components.md`, "What is still hand-built" (e830a15) |
| 2 | Tool window stripe: the selected button's background. The theme key gives the accent `#3871E1`, the real client measured gray `#393B40`. Compare with a screenshot of IntelliJ before changing; the phone's bottom tabs follow it | runtime | open | `docs/ui-components.md` |
| 3 | Search field's history popup (`SearchTextField(history = true)` shows the icon only) | — | not started | 04f0664 |
| 4 | Try the shared components on Android: APK from current main, emulator check, install on the phone (`adb -s 100.66.92.44:5555 install -r …`, app only, not launched). The phone has the 32b2677 build | ui | not started | |
| 5 | SSH from Android (emulator or phone) end to end: the Keystore secret store and the full BouncyCastle swap haven't run a real open-project yet | ui | not started | `docs/ssh-connections.md`, step 5 (desktop only) |
| 6 | Recent-project rows of the scratchpad test projects in `~/Library/Caches/JetBrains/RemoteDev/recent/` name the user's real IntelliJ, so opening one in IDEate or Gateway would start it with the user's settings. Deleting those status files needs the user's OK | user | open | `docs/ssh-connections.md`, step 5 |
| 7 | Backend dialogs (Lux: `luxModel.components`). Until then menu items that open a dialog stay greyed: Rename, New, Delete, Reformat Code, Find Usages, Paste from History, … | — | not started | `docs/action-protocol.md`, sections 3 and "Not done" |
| 8 | Paste from the Android soft keyboard (an `InputConnection` that takes `commitText`) | — | not started | `docs/edit-protocol.md`, "Pasting from the Android soft keyboard" |
| 9 | SSH hosts: offer "Take over?" for a running backend, as Termux entries do | — | open question | `docs/ssh-connections.md`, "Open questions" |

## Known limits (nothing to do now)

| Item | Why | Details |
|---|---|---|
| Structure of a file with several views (Spring's Logical/Physical) shows only the first | `CodeStructureTreeModel` carries one view; JetBrains Client doesn't switch either | `docs/structure-protocol.md` |
| The SSH host key dialog's title is "SSH" | Gateway's own title isn't in the bundles found | `connect/SshPage.kt` |

## Done (2026-11-17)

| Item | Commit |
|---|---|
| Test SSH container `ideate-sshd` stopped (`SshSessionTest` needs it only with `IDEATE_SSH_TEST_*` set) | — |
| Build reads `jetbrains.client.lib` from local.properties, as the user's checkout does | ideate-editor b35ff33, 2b38c4e |
| "8 s before the host key question": 590 ms in the app; the delay was the test tool (cmux) | ideate-editor c54ac36 |
| SSH: open a project end to end on the Mac (Remote Login), host key question as IntelliJ's message dialog, SSHJ key exchange timeout | ideate-editor 32b2677 |
| Shared UI components, steps 1 to 9 (theme, Button, TextField, RadioButton/Checkbox, IconButton, PopupContainer/Menu, LinearProgressBar, SearchTextField, DialogWrapper) | ideate-editor 6d43a84 … 04f0664 |
| Context menus from the backend: Project, Structure, editor | ideate-editor 0a42d25, bb3bc92 |
