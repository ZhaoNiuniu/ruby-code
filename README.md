# ruby-code
自己打造的 AI Agent

## Modules

- `agent-api`: public SDK contracts for agents, model clients, tools, requests, results, and steps.
- `agent-core`: a minimal ReAct-style tool loop with local command/file tools and an echo model client.
- `agent-provider-openai-compatible`: an OpenAI-compatible Chat Completions provider with native tool calling.
- `agent-cli`: a picocli-based command line entry point for local smoke tests.

## Requirements

- JDK 17
- Maven 3.9+

## Build and Test

```bash
mvn test
```

## Roadmap and Decisions

- [Agent 从 0 到 1 的关键路径](docs/agent-from-zero-to-one.md): living roadmap and decision log.

## Run the CLI

```bash
mvn -pl agent-cli -am package
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run "hello agent"
```

You can also omit the task argument and type it interactively:

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run
```

Or pipe a task through stdin:

```bash
echo "hello agent" | java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run
```

Run with an OpenAI-compatible provider:

```bash
export OPENAI_API_KEY="sk-..."
# Optional; can be overridden by --model:
# export OPENAI_MODEL="gpt-4.1-mini"
# Optional for third-party compatible endpoints:
# export OPENAI_BASE_URL="https://api.example.com/v1"
# Optional when your network needs a local proxy:
# export HTTPS_PROXY="http://127.0.0.1:7890"

java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --provider openai-compatible \
  --model gpt-4.1-mini \
  "Use the echo tool for hello"
```

Run with Qwen / 通义千问 through an OpenAI-compatible endpoint:

```bash
export OPENAI_API_KEY="${API_KEY}"
export OPENAI_BASE_URL="https://idealab.alibaba-inc.com/api/openai/v1"
export OPENAI_MODEL="qwen3-coder-plus"

java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --provider openai-compatible \
  "hello qwen"
```

Add custom user instructions when needed. The runtime still assembles the agent identity, tools, workspace, memory, skills, and planning sections around it:

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --provider openai-compatible \
  --model gpt-4.1-mini \
  --system "You are a careful command-line agent." \
  "hello agent"
```

Start an in-process multi-turn session:

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar chat \
  --provider openai-compatible \
  --model gpt-4.1-mini
```

`agent chat` uses a 10-turn short-term window for successful user/assistant turns. Once that window is exceeded, older turns are folded into compressed memory and the most recent turns remain as plain conversation history. It does not persist sessions across processes and it does not carry tool traces into the next turn. Use `/clear` to reset the current session, and `/exit` or `/quit` to leave chat.

## System Prompt Assembly

The final system prompt is assembled at runtime from sections instead of being hardcoded as one string. Core sections include identity, user instructions, workspace, enabled tools, persistent memory index, project skill catalog, todo planning guidance, and compressed session memory.

Sections load from real runtime state: enabled tools come from `ToolRegistry`, skills from the startup `skills/` scan, and long-term memory from the startup `.memory/` scan. The assembler caches identical system prompts inside the current agent runtime. Relevant long-term memory file contents are injected into the current user turn, not the system prompt, so the stable system sections remain cache-friendly.

## Context Compression

Context compression is enabled by default for both `agent run` and `agent chat`. The agent keeps original execution steps for debugging, but sends a compressed context view to the model when tool results, step count, or estimated prompt size grow too large.

The default budget uses a lightweight estimate of `chars / 4` tokens:

```text
context window: 128000
max output:     4000
reserved:       13000
```

You can tune or disable it from the CLI:

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar chat \
  --provider openai-compatible \
  --model gpt-4.1-mini \
  --context-window-tokens 128000 \
  --max-output-tokens 4000 \
  --reserved-tokens 13000

java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --no-context-compression \
  "debug without compression"
```

`agent chat` remains in-process only. When the successful conversation history exceeds the short-term window, older turns are folded into compressed memory and the most recent turns stay as plain conversation history. `/clear` removes both conversation history and compressed memory.

## Persistent Memory

Persistent memory is project-local and private by default. The agent stores long-term memories under `.memory/` and maintains a root `MEMORY.md` index; both are ignored by Git. This layer is separate from in-process `AgentMemory`: persistent memory survives compression and new CLI processes, while `AgentMemory` only keeps the current run/session coherent.

Each memory is a Markdown file with minimal frontmatter:

```markdown
---
name: tab-indentation
description: User prefers tabs instead of spaces for indentation.
type: user
---

Use tabs instead of spaces when editing project code.
```

Supported memory types are `user`, `feedback`, `project`, and `reference`. At startup, the CLI scans `.memory/` once: `agent run` scans once per command, and `agent chat` scans once for the whole chat process. `/clear` does not reload persistent memory.

The read path is deterministic: the `MEMORY.md` index is included as a runtime system section, and up to three relevant memory files are matched by the current task and recent conversation, then injected into the current user turn. The `echo` provider only reads memory. The `openai-compatible` provider can also write memory after a successful turn when the user explicitly asks the agent to remember something or states a stable preference, feedback pattern, project fact, or reference clue.

Manage local memory from the CLI:

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar memory list
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar memory show tab-indentation
```

Invalid memory files are skipped with warnings instead of failing startup. v1 does not compact, deduplicate, or sync memory; that remains a separate maintenance step.

## Default Tools

`ToolRegistry.defaultTools()` registers `todo_write` plus five local tools. The CLI uses the runtime default registry, which also includes `subagent` because it needs the active model client. When the project has a non-empty `skills/` catalog, the runtime registry also includes `load_skill`.

- `todo_write`: create or replace the agent's planning todo list. It does not read files, run commands, or perform work.
- `load_skill`: load the full `SKILL.md` content for a project skill by name. It is read-only and limited to the startup skill catalog.
- `subagent`: start an isolated investigation agent with a clean context. It returns only a final report and cannot spawn another subagent.
- `bash`: run a non-interactive shell command from the current workspace.
- `read_file`: read a UTF-8 text file.
- `write_file`: write UTF-8 text to a file, creating parent directories when needed.
- `edit_file`: replace text in a file exactly once.
- `glob`: find workspace files by glob pattern.

When `todo_write` is registered, the default `UserPromptSubmit` hook reminds the model to use it for complex, multi-step, command-running, or file-modifying tasks. Simple questions and one-step read-only tasks can skip it.

## Project Skills

Project skills are optional. If `skills/` is missing or empty, the CLI starts normally and does not register `load_skill` or inject a skill catalog. If one or more `SKILL.md` files exist, the CLI scans them once at startup: `agent run` scans once per command, and `agent chat` scans once for the whole chat process. `/clear` does not reload skills.

Each skill must live under this repository's `skills/` tree and begin with minimal frontmatter:

```markdown
---
name: java-agent-style
description: Guidance for implementing Java 17 agent features in this project.
---

Full skill instructions go here.
```

Only `name` and `description` are read for the startup catalog. The full file is loaded only when the model calls:

```json
{"name": "java-agent-style"}
```

Invalid skill frontmatter, duplicate names, or unsupported name characters fail fast during agent startup. `load_skill` does not accept file paths and cannot read outside the startup catalog.

All file paths are resolved inside the process working directory. When running from this repository, that workspace is:

```text
/Users/mengru/Projects/ruby-code
```

`bash` is a guarded local runner, not an OS-level sandbox. It fixes the command working directory to the workspace, applies a timeout, rejects interactive commands, and blocks obvious dangerous or workspace-escaping patterns.

`subagent` is investigation-only in v1. Its child tool set is `todo_write`, optional `load_skill`, `read_file`, `glob`, and `bash`; it does not receive `write_file`, `edit_file`, or `subagent`.

Before any tool executes, the agent applies a permission gate:

1. hard-deny rules block operations that are never allowed;
2. soft-risk rules request approval for `bash`, `write_file`, and `edit_file`;
3. CLI approval asks for `y/N` in an interactive terminal, and denies by default when non-interactive.

`todo_write`, `load_skill`, `subagent`, `read_file`, and `glob` are allowed by default because they do not modify the workspace. A child subagent's `bash` calls still pass through the same approval hook.

Example tool arguments:

```json
{"description": "Trace where UserService.save is called and report the shortest bug path", "expectedOutput": "Include relevant files and the next main-agent action", "maxSteps": 8}
```

```json
{"todos": [{"content": "Inspect the current code", "status": "in_progress"}, {"content": "Implement the change", "status": "pending"}]}
```

```json
{"command": "mvn test", "timeoutSeconds": 120}
```

```json
{"path": "README.md"}
```

```json
{"path": "notes/todo.txt", "content": "hello", "overwrite": true}
```

```json
{"path": "notes/todo.txt", "oldText": "hello", "newText": "hello agent"}
```

```json
{"pattern": "**/*.java"}
```
