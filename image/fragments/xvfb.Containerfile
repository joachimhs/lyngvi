# --- toolchain: xvfb (virtual X display, desktop libraries, fonts and screenshot tools for GUI programs) ---
# A desktop without a monitor. The entrypoint starts Xvfb on $DISPLAY when this fragment is in the image, so
# JavaFX, Swing/AWT, GTK, Qt, Tk and Electron programs open their windows as on a workstation — drawn in
# software (Mesa llvmpipe) — and `scrot`/`xdotool` take screenshots of them and drive them. The library
# list is the union of what those toolkits dlopen at run time; nothing here is a build dependency.
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb xauth x11-utils xdotool scrot \
      fontconfig fonts-dejavu-core fonts-liberation fonts-noto-color-emoji \
      libgtk-3-0 libgl1 libgl1-mesa-dri libegl1 libgles2 libglu1-mesa \
      libxtst6 libxi6 libxrender1 libxrandr2 libxcursor1 libxcomposite1 libxdamage1 libxfixes3 libxss1 libxxf86vm1 \
      libxkbcommon0 libxkbcommon-x11-0 libxcb-cursor0 libxcb-icccm4 libxcb-image0 libxcb-keysyms1 \
      libxcb-randr0 libxcb-render-util0 libxcb-shape0 libxcb-xinerama0 libxcb-xkb1 \
      libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libgbm1 libasound2 libdbus-1-3 \
 && rm -rf /var/lib/apt/lists/*
# DISPLAY and LYNGVI_SCREEN are what scripts/docker-entrypoint.sh starts Xvfb with; there is no GPU, so
# every OpenGL user gets Mesa's software renderer.
ENV DISPLAY=:99 \
    LYNGVI_SCREEN=1600x1000x24 \
    LIBGL_ALWAYS_SOFTWARE=1
USER agent
