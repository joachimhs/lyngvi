# --- toolchain: go123 ---
USER root
RUN arch="$(dpkg --print-architecture)" \
 && curl -fsSL "https://go.dev/dl/go${GO_VERSION:-1.23.12}.linux-${arch}.tar.gz" -o /tmp/go.tgz \
 && curl -fsSL "https://go.dev/dl/go${GO_VERSION:-1.23.12}.linux-${arch}.tar.gz.sha256" -o /tmp/go.sha256 \
 && echo "$(cat /tmp/go.sha256)  /tmp/go.tgz" | sha256sum -c - \
 && tar -C /usr/local -xzf /tmp/go.tgz && rm -f /tmp/go.tgz /tmp/go.sha256
ENV PATH=/usr/local/go/bin:/home/agent/go/bin:$PATH
USER agent
