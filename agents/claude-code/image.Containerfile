# --- agent: claude-code ---
# Pinned in agents/claude-code/agent.json (pinnedCli); keep the two in sync.
USER root
RUN npm_config_ignore_scripts=false npm install -g @anthropic-ai/claude-code@2.1.261 \
 && mkdir -p /home/agent/.claude && chown -R agent: /home/agent/.claude
USER agent
