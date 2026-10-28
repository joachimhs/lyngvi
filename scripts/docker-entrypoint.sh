#!/bin/sh
# Lyngvi container entrypoint: trust the organisation's CA and the gateway certificate,
# write package-manager mirror configuration, then run the command.
set -eu

bundle=/home/agent/.ca-bundle.pem
{
  cat /etc/ssl/certs/ca-certificates.crt 2>/dev/null || true
  for f in /certs/*.pem /certs/*.crt /etc/lyngvi/gateway.crt; do
    [ -f "$f" ] && cat "$f" && echo
  done
} > "$bundle" 2>/dev/null || true
if grep -q 'BEGIN CERTIFICATE' "$bundle" 2>/dev/null; then
  export CURL_CA_BUNDLE="$bundle" SSL_CERT_FILE="$bundle" REQUESTS_CA_BUNDLE="$bundle" NODE_EXTRA_CA_CERTS="$bundle"
  # Import into every JDK that is present (keytool exists only when a java fragment was built).
  for kt in /home/agent/.sdkman/candidates/java/*/bin/keytool; do
    [ -x "$kt" ] || continue
    cacerts="$(dirname "$kt")/../lib/security/cacerts"
    [ -f /etc/lyngvi/gateway.crt ] && "$kt" -importcert -noprompt -trustcacerts -alias lyngvi-gateway \
      -file /etc/lyngvi/gateway.crt -keystore "$cacerts" -storepass changeit >/dev/null 2>&1 || true
  done
fi

# Mirrors (empty variables mean "no mirror").
if [ -n "${LYNGVI_NPM_MIRROR:-}" ]; then
  printf 'registry=%s\n' "$LYNGVI_NPM_MIRROR" > /home/agent/.npmrc
fi
if [ -n "${LYNGVI_PYPI_MIRROR:-}" ]; then
  mkdir -p /home/agent/.config/pip
  printf '[global]\nindex-url = %s\n' "$LYNGVI_PYPI_MIRROR" > /home/agent/.config/pip/pip.conf
fi
if [ -n "${LYNGVI_MAVEN_MIRROR:-}" ] && [ ! -f /home/agent/.m2/settings.xml ]; then
  mkdir -p /home/agent/.m2
  cat > /home/agent/.m2/settings.xml <<XML
<settings><mirrors><mirror><id>lyngvi</id><mirrorOf>*</mirrorOf><url>${LYNGVI_MAVEN_MIRROR}</url></mirror></mirrors></settings>
XML
fi

# A virtual display, when the image has the xvfb toolchain fragment: GUI programs the agent starts open
# their windows on it and screenshots are taken from it (image/fragments/xvfb.md). Helper containers run
# with --entrypoint sh and never get here.
if [ -n "${DISPLAY:-}" ] && command -v Xvfb >/dev/null 2>&1; then
  Xvfb "$DISPLAY" -screen 0 "${LYNGVI_SCREEN:-1600x1000x24}" -nolisten tcp -ac >/dev/null 2>&1 &
fi

exec "$@"
