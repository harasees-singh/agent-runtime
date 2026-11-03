#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import urllib.request
from engines import ENGINES


def main():
    engine = os.environ["RUNTIME_ENGINE"]
    agent = os.environ["RUNTIME_AGENT"]
    prompt = os.environ["RUNTIME_PROMPT"]
    model = os.environ["RUNTIME_MODEL"]
    print(json.dumps({"event": "agent.started", "engine": engine, "agent": agent}), flush=True)

    task = f"You are the {agent} agent. Complete this task:\n\n{prompt}"
    adapter = ENGINES.get(engine)
    if adapter is None:
        raise ValueError(f"Unsupported agent engine: {engine}")
    command = adapter.command(model, task)

    completed = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    print(completed.stdout, flush=True)
    print(json.dumps({"event": "agent.finished", "exitCode": completed.returncode}), flush=True)
    callback = {
        "attemptId": os.environ["RUNTIME_ATTEMPT_ID"],
        "callbackToken": os.environ["RUNTIME_CALLBACK_TOKEN"],
        "result": completed.stdout[-90000:],
        "error": None if completed.returncode == 0 else f"Agent exited with status {completed.returncode}",
    }
    request = urllib.request.Request(
        f"{os.environ['RUNTIME_API_BASE_URL']}/v1/jobs/{os.environ['RUNTIME_JOB_ID']}/complete",
        data=json.dumps(callback).encode(), headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=10) as response:
        if response.status != 204:
            raise RuntimeError(f"Job completion callback returned HTTP {response.status}")
    return completed.returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(json.dumps({"event": "agent.error", "error": str(exc)}), flush=True)
        sys.exit(1)
