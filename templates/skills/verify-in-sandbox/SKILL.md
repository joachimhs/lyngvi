---
name: verify-in-sandbox
description: Use when a change has to be seen working, not just compiled — a UI or web page, a desktop window, a CLI that prints something, a server that must answer — and whenever you are about to report "this works" without having run it. Finds out what this container can actually run, then drives it and captures the result for the human.
---

# See the change work, from inside the container

There is no one at a screen here. "It should work" is not a verification; a command and its output is.
Everything below produces something the human can check: output they can read, or an image in `/work/output/`.

## 1. Find out what this container has — it differs per sandbox

The toolchains come from the sandbox's profile, so probe before you plan. One line, read all of it:

```bash
command -v node python3 java mvn npm chromium scrot xdotool Xvfb; echo "DISPLAY=$DISPLAY CHROME_BIN=$CHROME_BIN"
```

- `$CHROME_BIN` set → a headless browser is available.
- `$DISPLAY` set and `xdpyinfo` succeeds → a virtual display is running, so desktop windows can open and be
  photographed.
- Neither → you can still verify everything that is not visual: tests, a server's HTTP answers, rendered HTML.
  Say which of the two you did; do not call a rendered DOM a screenshot.

## 2. Run the thing the way a developer would, in the background

Start it, give it a few seconds, then check it is actually up before you conclude anything:

```bash
(cd /work/<repo> && npm run dev > /work/output/dev.log 2>&1 &)
sleep 5
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:5173/ || tail -20 /work/output/dev.log
```

A failed start is the answer in itself — read the log rather than retrying the same command.

## 3. Capture it

**A web page, no code:**

```bash
chromium --headless --screenshot=/work/output/page.png --window-size=1280,800 http://localhost:5173/
chromium --headless --dump-dom http://localhost:5173/ | head -40     # when there is no display to look at
```

**A desktop window** (JavaFX, Swing, GTK, Qt, Tk, Electron): start it in the background, wait, then

```bash
xwininfo -root -tree | grep -E '^ +0x'      # which windows exist, and their geometry
scrot -o /work/output/window.png            # the whole display
xdotool mousemove <x> <y> click 1           # there is no window manager: click, do not "activate"
```

Read the geometry out of `xwininfo` instead of guessing coordinates, and take the screenshot immediately after
the click — then look at the image you just made. A screenshot you have not looked at verifies nothing.

**Anything else:** the project's own tests, with the command in your answer and the real output.

## 4. Report what you saw

Name the file you wrote to `/work/output/`, say what is in it, and say what you checked in it. If the thing did
not come up, say that plainly and what the log said — a change that could not be run is not a change that works.
