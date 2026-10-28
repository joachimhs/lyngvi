# --- toolchain: gradle (requires a java fragment) ---
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install gradle ${GRADLE_VERSION:-8.14.3}"
ENV PATH=/home/agent/.sdkman/candidates/gradle/current/bin:$PATH
