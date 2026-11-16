# Mobile layout: phone, tablet, portrait, landscape

How the main window (`client/src/main/kotlin/intellijandroid/client/ui/ClientScreen.kt`, `ToolWindows.kt`) arranges
the editor, the tool windows and the bars on each screen shape. From the user's brainstorm of 2026-11-16
(`optimize-for-portrait.md` next to the repo) and the answers to its questions.

## Which layout

The layout is picked from the window's size in dp, measured with `BoxWithConstraints`, so a desktop window or a
split-screen app gets the layout that fits it:

| Window | Layout | Example |
|---|---|---|
| wider than tall | landscape | phone sideways 891 × 411, desktop window, tablet sideways |
| taller than wide, width ≥ 600 dp | tablet portrait | emulator at `wm density 240`: 720 × 1440 |
| taller than wide, width < 600 dp | phone portrait | Pixel 3a 393 × 807, the user's phone 411 × 891 |

600 dp is Android's own tablet line (`sw600dp`, `Configuration.smallestScreenWidthDp`). There is no reliable device
identifier for "tablet"; Android itself uses this width.

The activity no longer pins `sensorLandscape`: it follows the sensor, and `configChanges` keeps the session alive
across a rotation.

## All layouts

- **Main toolbar on top** (40 high), a reduced copy of IntelliJ's: the project widget (blue square with the
  project's initials, `smoke` → `SM`, and its name), then on the right the run widget ("Current File"), Build, Run,
  Debug and More. Only the project name is live; the others show "… isn't supported in this client yet" in the
  status line for 3 s.
- **Tool windows that work**: Project, and Structure ([`structure-protocol.md`](structure-protocol.md)): the tree
  of the selected file, all expanded; a tap selects and moves the caret (IntelliJ's "Navigate with Single Click"),
  a double tap also slides back to the editor on a phone held upright.
- **Tool window bar**: every button from IntelliJ's two stripes in one bar, in three groups split by separators:
  left stripe top (Project, Commit, Bookmarks), left stripe bottom (Services, Run, Debug, Problems, Git), right
  stripe (Structure, Notifications). Icons are IntelliJ's `expui/toolwindows/<name>@20x20.svg`, buttons 32 in a
  40-thick bar, selected background `#393B40`. Only Project and Structure open; the rest say they aren't supported.
- **One tool window per side** at most. Tapping an open window's button closes it, as on IntelliJ's stripes.
- **Structure is on the right.** IntelliJ puts it on the left by default; on the right it can be open together with
  Project, which the split layouts below need.

## Phone portrait

- The editor fills the screen between the main toolbar (top) and the tool window bar (bottom).
- The left tool window, the editor and the right tool window sit side by side, each one screen wide. A tool window
  covers the whole editor area when open; its header has a back chevron (`expui/general/chevronLeft.svg`) that
  slides back to the editor.
- **Swipes** (the user's answer to question 1):

  | From | Finger moves right | Finger moves left |
  |---|---|---|
  | editor | left tool window slides in | right tool window slides in |
  | left tool window | nothing | back to the editor |
  | right tool window | back to the editor | nothing |

  The content follows the finger, like a pager: the left window is to the left of the editor, so dragging right
  pulls it in. The swipe opens the window last used on that side (Project and Structure at first).
- A drag counts as a swipe once it has moved the touch slop and is at least twice as far sideways as up or down.
  Past a third of the width, or a flick faster than 1000 px/s, it moves one page; otherwise it springs back.
- The swipe takes the touch events in Compose's `Initial` pass, before the editor sees them. So in this layout
  **selecting text by dragging sideways doesn't work**; vertical drags still reach the editor.
- The editor now moves the caret and takes focus on **tap**, not on press. On press, every swipe that started on
  the editor focused it and opened the keyboard. When a tool window covers the editor, focus is cleared, so the
  keyboard closes.

## Tablet portrait

Same two bars as the phone, no swipes. Open tool windows share one area under the editor (the editor gets 1 part of
the height, the area 0.8), side by side when both sides are open: left window on the left, right window on the
right.

## Landscape (phone and tablet)

The tool window bar moves to the left edge (40 wide, scrolls when the screen is too short for all ten buttons). Open
tool windows share one 300-wide column between the bar and the editor, one above the other when both sides are
open. Project is open at start, as before.

## Checked

On the Mac's Android emulator (Pixel 3a, API 34) against the isolated Mac backend, 2026-11-16 ~14:20 IST: landscape
with Project open; phone portrait editor, swipe right → Project, swipe left twice → editor → Structure; tablet
portrait (`adb shell wm density 240`) with Project and Structure side by side under the editor; Structure showed
`Main.java › Main › main(String[]): void`, a tap on `main` moved the caret to line 4, and typing `int count` added
`count: int`. Not yet on the user's phone.

## Testing notes

- The emulator rotates only when the app allows it. `adb shell wm user-rotation lock 0` holds portrait;
  `adb emu rotate` turns the emulator. While the activity pinned `sensorLandscape`, the window stayed landscape
  whatever the emulator's setting (`dumpsys window` showed `mCurrentAppOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE`).
- `adb shell wm density 240` on the 1080-px-wide emulator gives a 720-dp-wide window, enough for tablet portrait.
  `adb shell wm density reset` undoes it.
- `adb shell input swipe 200 1200 900 1200 200` is a 700-px swipe right in 200 ms.
