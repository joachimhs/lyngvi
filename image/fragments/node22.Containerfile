# --- toolchain: node22 ---
USER root
RUN apt-get update && apt-get install -y --no-install-recommends xz-utils \
 && rm -rf /var/lib/apt/lists/* \
 && arch="$(dpkg --print-architecture)" \
 && case "$arch" in amd64) node_arch=x64 ;; arm64) node_arch=arm64 ;; *) echo "unsupported arch $arch" >&2; exit 1 ;; esac \
 && curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION:-22.20.0}/node-v${NODE_VERSION:-22.20.0}-linux-${node_arch}.tar.xz" -o /tmp/node.tar.xz \
 && curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION:-22.20.0}/SHASUMS256.txt" -o /tmp/SHASUMS256.txt \
 && grep " node-v${NODE_VERSION:-22.20.0}-linux-${node_arch}.tar.xz\$" /tmp/SHASUMS256.txt | sed 's#  .*# /tmp/node.tar.xz#' | sha256sum -c - \
 && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 \
 && rm -f /tmp/node.tar.xz /tmp/SHASUMS256.txt
ENV NPM_CONFIG_PREFIX=/home/agent/.npm-global \
    PATH=/home/agent/.npm-global/bin:$PATH
USER agent
