FROM node:22-bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git python3 \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

RUN npm install --global @openai/codex

WORKDIR /workspace
RUN chown node:node /workspace
COPY --chown=node:node runner/run-agent.py runner/engines.py /usr/local/bin/
USER node
ENTRYPOINT ["python3", "/usr/local/bin/run-agent.py"]
