## Browser

Browser downloads are blocked here, so Playwright and Puppeteer cannot fetch their
own browsers. Debian's Chromium is installed instead, at `/usr/bin/chromium` and in
`$CHROME_BIN`, with `--no-sandbox` and `--disable-dev-shm-usage` already added by
its wrapper. Headless use needs no display.

- A screenshot of a page, no code:
  `chromium --headless --screenshot=/work/output/page.png --window-size=1280,800 http://localhost:5173/`.
  `--dump-dom` prints the rendered DOM and `--print-to-pdf=<file>` writes a PDF.
- Playwright: `chromium.launch({ executablePath: process.env.CHROME_BIN })`; the
  `playwright` package installs without browsers (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`).
  Puppeteer uses `$PUPPETEER_EXECUTABLE_PATH` on its own.
- Debian's Chromium is older than the one those libraries were built against. If a
  call fails on protocol grounds, fall back to the command line above.
