# --- toolchain: maven (requires a java fragment) ---
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install maven ${MAVEN_VERSION:-3.9.11}"
ENV PATH=/home/agent/.sdkman/candidates/maven/current/bin:$PATH
