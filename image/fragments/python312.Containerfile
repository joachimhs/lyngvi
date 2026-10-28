# --- toolchain: python312 ---
USER root
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip python3-venv \
 && rm -rf /var/lib/apt/lists/*
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_INPUT=1
USER agent
