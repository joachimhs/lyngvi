-- The Finder window of the disk image: the application on the left, an alias to /Applications on the right,
-- and the background that draws the arrow between them. jpackage picks this file up from the resource
-- directory because of its name, <app name>-dmg-setup.scpt, substitutes the DEPLOY_ placeholders, mounts the
-- image and runs it with osascript; the JDK's own DMGsetup.scpt is what it replaces.
--
-- Everything here is cosmetic except the alias, which is the thing the user drags onto. A machine with no
-- Finder session — a release built in CI — fails somewhere in the middle of this and gets a plain disk image
-- that installs just as well, so nothing below is allowed to be load-bearing.
tell application "Finder"
  tell disk "DEPLOY_ACTUAL_VOLUME_NAME"
    open

    -- The drop target, for the jpackage run that has nothing else to make one. build-dmg.sh writes a symlink
    -- into the image before it runs this, and then this line fails on the name already being taken: hence
    -- the 'try', and hence a drop target that does not depend on Finder being reachable at all.
    try
      make new alias file at container window to (path to applications folder) with properties {name:"Applications"}
    end try

    set current view of container window to icon view
    set toolbar visible of container window to false
    set statusbar visible of container window to false
    -- The background is 640 x 400 and the window is 28 points taller, for the title bar the bounds include.
    -- The lower band of the background is white, so the window being out by a few points shows more white.
    set the bounds of container window to {240, 140, 880, 568}

    set opts to the icon view options of container window
    set arrangement of opts to not arranged
    set icon size of opts to 128
    set text size of opts to 12
    set label position of opts to bottom
    set shows item info of opts to false
    set background picture of opts to file ".background:background.tiff"

    -- Where the arrow in the background points, and symmetric about the middle of the window: the positions
    -- are the centres of the icon cells. Found by name rather than by placeholder, so that a jpackage which
    -- substitutes something unexpected still leaves a window with the right background.
    repeat with theItem in (get every item of container window)
      set theName to name of theItem as text
      if theName ends with ".app" then
        set position of theItem to {160, 190}
      else if theName is "Applications" then
        set position of theItem to {480, 190}
      end if
    end repeat

    update without registering applications
    delay 3
    close
  end tell
end tell
