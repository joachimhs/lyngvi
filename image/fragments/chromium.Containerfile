# --- toolchain: chromium (Debian's Chromium for page screenshots and browser-driven tests) ---
# The proxy blocks the hosts Playwright, Puppeteer and friends download their browsers from; this is the
# browser they use instead, found through the environment variables below. The wrapper flags are what a
# container with no capabilities and a 64 MiB /dev/shm needs. Headless use needs no display; with the
# xvfb fragment the browser can also open a window on $DISPLAY.
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
      chromium fontconfig fonts-dejavu-core fonts-liberation fonts-noto-color-emoji \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /etc/chromium.d \
 && printf '%s\n' 'export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox --disable-dev-shm-usage --disable-gpu"' > /etc/chromium.d/lyngvi
ENV CHROME_BIN=/usr/bin/chromium \
    CHROME_PATH=/usr/bin/chromium \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    PUPPETEER_SKIP_DOWNLOAD=1 \
    PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=1 \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
USER agent
