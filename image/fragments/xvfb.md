## Graphical programs and screenshots

This container has a virtual display: `Xvfb` runs on `$DISPLAY` from the moment the
container starts, so desktop programs — JavaFX, Swing/AWT, GTK, Qt, Tk, Electron —
open their windows as on a workstation. There is no monitor and no GPU; everything
is drawn in software, and a screenshot is how you see it.

- Start the program the way a developer would (`mvn javafx:run`, `./gradlew run`,
  `python app.py`, `npx electron . --no-sandbox`) in the background, give it a few
  seconds, then `scrot /work/output/<name>.png` captures the whole display and
  `scrot -u` the focused window. Screenshots belong in `/work/output/`.
- `xwininfo -root -tree` lists the open windows. `xdotool` finds and drives them:
  `xdotool search --name "<title>"`, `xdotool windowactivate <id>`,
  `xdotool key ctrl+s`, `xdotool mousemove 100 200 click 1`.
- There is no window manager: windows have no title bar and open at (0,0). Give a
  window focus with `xdotool windowactivate` before sending keys to it.
- Prefer software rendering where a toolkit asks (`-Dprism.order=sw` for JavaFX);
  `LIBGL_ALWAYS_SOFTWARE=1` is already set for OpenGL.
- If the display is gone (`xdpyinfo` fails), start it again:
  `Xvfb "$DISPLAY" -screen 0 "$LYNGVI_SCREEN" -nolisten tcp -ac &`.
