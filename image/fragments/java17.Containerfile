# --- toolchain: java17 (Temurin via SDKMAN, additional JDK) ---
USER root
RUN apt-get update && apt-get install -y --no-install-recommends bash \
 && rm -rf /var/lib/apt/lists/*
USER agent
ENV SDKMAN_DIR=/home/agent/.sdkman
RUN curl -fsSL "https://get.sdkman.io?rcupdate=false" -o /tmp/sdkman.sh \
 && bash /tmp/sdkman.sh && rm -f /tmp/sdkman.sh \
 && printf 'sdkman_auto_answer=true\nsdkman_selfupdate_feature=false\nsdkman_curl_connect_timeout=20\nsdkman_curl_max_time=0\n' > "$SDKMAN_DIR/etc/config" \
 && bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install java ${JDK_17_VERSION:-17-tem}"
ENV JAVA_17_HOME=/home/agent/.sdkman/candidates/java/${JDK_17_VERSION:-17-tem}
