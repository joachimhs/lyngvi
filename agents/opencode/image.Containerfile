# --- agent: opencode ---
# Pinned in agents/opencode/agent.json (pinnedCli); keep the two in sync.
USER root
RUN npm_config_ignore_scripts=false npm install -g opencode-ai@1.18.29 \
 && mkdir -p /home/agent/.opencode-state && chown -R agent: /home/agent/.opencode-state
USER agent
