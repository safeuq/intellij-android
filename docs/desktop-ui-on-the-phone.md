# IntelliJ's desktop UI on the phone (XFCE over VNC)

Tried 2026-11-15: the full IntelliJ window, not the remote-dev backend, on an X display in Termux, viewed over VNC. It works up to a point and isn't the direction of this project (the native Android frontend is), but it's useful for checking things on the phone.

## Result

`idea.sh` on `jbr-25` drew IntelliJ's window and got as far as the user agreement dialog (not accepted; accepting JetBrains' agreement is the user's decision). The parts that need glibc don't work there either: terminal, Compose (skiko), JNA, the VCS log index, WebP, JCEF, and the OS-integration daemon. The list with file names is in [`remote-dev-server.md`](remote-dev-server.md), "Native parts that need glibc".

The desktop IntelliJ and the remote-dev backend share the same config and system folders (`~/.config/JetBrains/IntelliJIdea2026.3`, `~/.cache/JetBrains/IntelliJIdea2026.3`), so only one of them can run at a time. `intellij-backend start` refuses with exit `4` while a desktop IntelliJ runs.

How the test ran (display `:7`, no VNC password, reachable only from the phone itself):

```sh
Xvnc :7 -localhost -SecurityTypes None -geometry 1600x1000 &
cd ~/intellij/idea-IU-263.7307.200
DISPLAY=:7 IDEA_JDK=$PREFIX/lib/jvm/jbr-25 ./bin/idea.sh
```

A screenshot of the display without a VNC viewer, with a few lines of Java (`java.awt.Robot`) run as `DISPLAY=:7 $PREFIX/lib/jvm/jbr-25/bin/java -Dsun.java2d.vulkan=false Shot.java screen.png`.

## The XFCE desktop

Installed on the phone: `x11-repo`, `tigervnc` 1.16.2, `xfce4` 4.20. `~/.vnc/xstartup` holds `xfce4-session &`; `~/.vnc/config` only has commented-out examples.

| Task | Command |
|---|---|
| start the desktop | `vncserver` (prints the display it took, e.g. `:1`) |
| list running ones | `vncserver -list` |
| stop one | `vncserver -kill :1` |
| set the VNC password | `vncpasswd` (stored in `~/.vnc/passwd`) |
| connect from a computer | VNC to `<phone-address>:5901` for display `:1`; with `-localhost`, tunnel first: `ssh -p 8022 -N -L 5901:127.0.0.1:5901 <phone-address>`, then `vnc://127.0.0.1:5901` |

How it behaves (from Termux's `vncserver` script and the server log):

- **Display numbers.** `vncserver` takes the first free display from `:1` to `:99` (`foreach $n (1..99)`); it never picks `:0`, which on a normal Linux machine belongs to the physical screen's X server. A display counts as taken when something listens on its VNC port or X port.
- **Ports.** VNC port = 5900 + display (`:1` → `5901`); X port = 6000 + display.
- **`DISPLAY`.** X programs draw on the display named in `DISPLAY` (`DISPLAY=:1` → the X server for display 1 on this phone). Nothing in the phone's startup files sets it, so set it per command.
- **Password changes** take effect for the next connection; the server re-reads the password file each time, no restart needed.
- **Too many wrong passwords** make the server block the client's address for a while (log: `Connections: Blacklisted: 127.0.0.1`). macOS Screen Sharing then shows "The software on the remote computer appears to be incompatible with this version of Screen Sharing." Restarting the server (`vncserver -kill :1`, `vncserver`) clears the block.
