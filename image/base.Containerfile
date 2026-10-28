# Lyngvi base image: a minimal Debian with git and tini, one unprivileged user,
# and hardening applied through environment variables. Toolchains come from
# image/fragments/*.Containerfile, agents from agents/<id>/image.Containerfile.
# Rendered into ./Containerfile by 'setup.sh rebuild-config'; do not edit the result.
#
# Pinned multi-arch digest (amd64 + arm64); CI verifies the pin. Update with: setup.sh doctor --images (M12).
FROM docker.io/library/debian:bookworm-slim@sha256:88200866dfff7ea7f5cbcb6ec7c8a701889efe6fe859fe64d6990e4b07ea4171

ARG USER_UID={{UID}}
ARG USER_GID={{GID}}

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      git ca-certificates curl tini openssh-client zip unzip ripgrep jq nano less procps \
 && rm -rf /var/lib/apt/lists/*

# One unprivileged user whose UID/GID match the host user, so bind-mounted files keep their owner.
RUN (getent group "${USER_GID}" >/dev/null || groupadd --gid "${USER_GID}" agent) \
 && useradd --uid "${USER_UID}" --gid "${USER_GID}" --create-home --home-dir /home/agent --shell /bin/bash agent \
 && mkdir -p /work /etc/lyngvi \
 && chown -R agent: /work /home/agent

COPY scripts/docker-entrypoint.sh /home/agent/docker-entrypoint.sh
RUN chmod 755 /home/agent/docker-entrypoint.sh && chown agent: /home/agent/docker-entrypoint.sh

# Hardening (spec §7.1): no package lifecycle scripts, no git prompts or system config, no signing.
ENV npm_config_ignore_scripts=true \
    YARN_ENABLE_SCRIPTS=false \
    GIT_TERMINAL_PROMPT=0 \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_COUNT=4 \
    GIT_CONFIG_KEY_0=commit.gpgsign GIT_CONFIG_VALUE_0=false \
    GIT_CONFIG_KEY_1=tag.gpgsign    GIT_CONFIG_VALUE_1=false \
    GIT_CONFIG_KEY_2=user.name      GIT_CONFIG_VALUE_2={{GIT_NAME}} \
    GIT_CONFIG_KEY_3=user.email     GIT_CONFIG_VALUE_3={{GIT_EMAIL}} \
    JAVA_TOOL_OPTIONS="-Dcom.sun.jndi.dns.timeout=1000 -Dcom.sun.jndi.dns.connect.timeout=1000 -Dsun.net.inetaddr.ttl=10 -Dnetworkaddress.cache.ttl=10 -Dnetworkaddress.cache.negative.ttl=1" \
    LANG=C.UTF-8 \
    TERM=xterm-256color

USER agent
WORKDIR /work
ENTRYPOINT ["/usr/bin/tini", "--", "/home/agent/docker-entrypoint.sh"]
CMD ["sleep", "infinity"]
