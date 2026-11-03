"""Provider-specific CLI adapters used by the runtime runner."""

from typing import Protocol


class Engine(Protocol):
    def command(self, model: str, prompt: str) -> list[str]: ...


class ClaudeCode:
    def command(self, model: str, prompt: str) -> list[str]:
        return ["claude", "-p", "--model", model, "--output-format", "text",
                "--permission-mode", "bypassPermissions", prompt]


class OpenCode:
    def command(self, model: str, prompt: str) -> list[str]:
        return ["opencode", "run", "--standalone", "--model", model, "--format", "json", prompt]


class Codex:
    def command(self, model: str, prompt: str) -> list[str]:
        return ["codex", "exec", "--ephemeral", "--json", "--sandbox", "danger-full-access",
                "--skip-git-repo-check", "--model", model, prompt]


ENGINES: dict[str, Engine] = {
    "claude-code": ClaudeCode(),
    "opencode": OpenCode(),
    "codex": Codex(),
}
