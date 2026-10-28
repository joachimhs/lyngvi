# --- toolchain: kotlin (requires a java fragment) ---
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install kotlin ${KOTLIN_VERSION:-2.2.20}"
ENV PATH=/home/agent/.sdkman/candidates/kotlin/current/bin:$PATH
