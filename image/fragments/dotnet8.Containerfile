# --- toolchain: dotnet8 ---
USER root
RUN apt-get update && apt-get install -y --no-install-recommends libicu72 libssl3 \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL https://dot.net/v1/dotnet-install.sh -o /tmp/dotnet-install.sh \
 && bash /tmp/dotnet-install.sh --channel 8.0 --install-dir /usr/share/dotnet \
 && ln -s /usr/share/dotnet/dotnet /usr/local/bin/dotnet && rm -f /tmp/dotnet-install.sh
ENV DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
USER agent
