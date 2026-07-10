# ruby-code
自己打造的 AI Agent

## Modules

- `agent-api`: public SDK contracts for agents, model clients, tools, requests, results, and steps.
- `agent-core`: a minimal ReAct-style tool loop with local command/file tools and an echo model client.
- `agent-mcp`: a stdio MCP client that adapts external MCP tools into the local Tool API.
- `agent-provider-openai-compatible`: an OpenAI-compatible Chat Completions provider with native tool calling.
- `agent-runtime`: runtime profile resolution, tool filtering, and profile-aware permission decisions.
- `agent-cli`: a picocli-based command line entry point for local smoke tests.
- `agent-evals`: deterministic behavioral evals that replay scripted model steps against the runtime.

## Requirements

- JDK 17
- Maven 3.9+

## Build and Test

```bash
mvn test
mvn -pl agent-evals test
```

## Roadmap and Decisions

- [Agent 从 0 到 1 的关键路径](docs/agent-from-zero-to-one.md): living roadmap and decision log.
- [Agent 二期：从 CLI Runtime 到可配置 Agent Platform](docs/agent-phase-two-platform-roadmap.md): phase 2 living roadmap and decision log.

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

Use a runtime profile when you want a named capability boundary:

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --profile readonly \
  "inspect the project"

java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar profile show readonly
```

Initial project profiles are provided in `.agent/profiles/`:

- `dev.json`: explicit project copy of the default development runtime.
- `readonly.json`: read-oriented runtime that hides or denies high-risk tools.
- `openai-compatible.json`: real-provider profile that uses `OPENAI_MODEL`, `OPENAI_BASE_URL`, and `OPENAI_API_KEY` from the environment.

`dev` and `readonly` are also built in; a project file can override either one. CLI options such as `--provider`, `--model`, `--system`, `--no-mcp`, and budget flags override the selected profile. Secrets still come from environment variables.

Permission policy files live in `.agent/policies/{name}.json` and are referenced by profile `policy.name`. Policy rules decide `allow`, `ask`, or `deny`; hard safety checks such as workspace escape, symlink escape, `sudo`, and `rm -rf /` cannot be overridden.

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

For OpenAI-compatible endpoints that expose thinking-mode fields, the provider preserves assistant `reasoning_content` in step metadata and sends it back on later assistant tool-call messages when the endpoint requires it.

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

`agent chat` uses a 10-turn short-term window for successful user/assistant turns. Once that window is exceeded, older turns are folded into compressed memory and the most recent turns remain as plain conversation history. It does not persist sessions across processes and it does not carry tool traces into the next turn. Use `/clear` to reset the current session history and compressed session memory, and `/exit` or `/quit` to leave chat. `/clear` does not cancel background tasks.

## MCP Tools

`agent run` and `agent chat` check for a project-local `.mcp.json` by default. v1 supports stdio MCP servers only and adapts remote `tools/list` results into normal agent tools named:

```text
mcp__{serverName}__{toolName}
```

Claude-style config is supported:

```json
{
  "mcpServers": {
    "local_demo": {
      "command": "node",
      "args": ["server.js"],
      "env": {
        "TOKEN": "..."
      }
    }
  }
}
```

Use `--no-mcp` to disable MCP loading, or `--mcp-config <path>` to point at another config file inside the workspace. `agent run` starts configured MCP servers for that command and closes them before exit. `agent chat` starts them once at chat startup, reuses them through the session, and does not reload them on `/clear`.

MCP tools are exposed only to the main/Lead agent in v1. Subagents and teammates do not receive MCP tools. Every MCP call requires user approval, even if an MCP server advertises annotations that imply read-only or safe behavior.

## Background Tasks

`agent chat` can run slow `bash` commands in the background so the main loop can continue. The model can request this explicitly with `run_in_background: true`; if it omits the flag, the runtime only backgrounds obvious long-running commands such as dev servers, watch commands, `tail -f`, `sleep`, Spring Boot runs, or `python -m http.server`. Build and test commands such as `mvn test` and `mvn package` stay foreground by default.

Background task lifecycle is tied to the current CLI chat process. A started task immediately returns a placeholder `TOOL_RESULT` with a `bg_id`; when it finishes, the next chat turn receives a separate `<task_notification>` user message. The original `tool_call_id` is not reused for the completion notification, so native tool-call pairing stays valid.

`agent run` uses a disabled background manager. If a model asks for `run_in_background: true` in one-shot mode, the command is executed in the foreground instead, avoiding orphaned work after the process exits.

## Cron Scheduler

`agent chat` starts an in-process cron scheduler. The scheduler runs in a daemon thread, checks jobs roughly once per second, places due work into a `cron_queue`, and a single queue processor delivers each trigger as a synthetic user turn to the same `AgentSession`. `agent run` does not start the scheduler.

The chat runtime exposes three scheduler tools:

- `schedule_task`: create a session-only or durable scheduled task.
- `list_scheduled_tasks`: show jobs, next run times, durability, and queue status.
- `cancel_scheduled_task`: cancel a job by `jobId`.

Recurring jobs use a six-field second-level cron expression:

```text
second minute hour day month weekday
```

v1 supports `*`, `*/n`, single values, comma lists, and `a-b` ranges. It does not support `?`, `L`, `W`, or `#`. Day-of-month and weekday are both filters; if both are specific, both must match. One-shot jobs use `type=once` with an ISO-8601 `runAt`; local date-times use `zoneId` or the system timezone.

Durable jobs are written to `.scheduled_tasks.json`, which is ignored by Git. They are loaded when `agent chat` starts; `/clear` does not reload or clear scheduled jobs. If a recurring durable job would have fired while the agent process was closed, v1 does not backfill old runs. If a one-shot durable job is already due on startup, it fires once and is removed.

Cron turns reuse the same session history, memory, tools, trace, and prompt assembly as normal chat turns. They are marked with a `<cron_trigger>` wrapper. Cron turns use non-interactive approval: soft-risk tools such as `bash`, `write_file`, and `edit_file` are denied by default, while read-only tools keep the normal allow behavior. Cron results are printed to stdout with a prefix:

```text
[cron job_1 daily-check] ...
```

## Task System

The task system is a durable project work queue for multi-agent coordination. It is different from `todo_write`: `todo_write` is the current agent's short-lived execution checklist, while task files are recoverable project work items with dependencies and ownership.

Tasks are stored as JSON files under `.tasks/{id}.json`. IDs are generated as `task_000001`, `task_000002`, and so on; `.tasks/.highwatermark` prevents ID reuse after deletion. `.tasks/` is not ignored by default, so the task graph can be committed and shared. Only the runtime lock file `.tasks/.lock` is ignored.

The runtime exposes six task tools:

- `create_task`: create a durable task with optional `blockedBy` dependencies.
- `list_tasks`: list compact task summaries, with optional `status`, `owner`, and `canStart` filters.
- `get_task`: return full task JSON and dependency readiness details.
- `can_start`: check whether all dependencies are completed; missing dependencies are treated as blocked.
- `claim_task`: move a task from `pending` to `in_progress` and record the runtime `--agent-name` as owner.
- `complete_task`: complete an owned `in_progress` task and report newly unlocked downstream tasks.

Use `--agent-name` to distinguish multiple agent processes:

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar chat \
  --provider openai-compatible \
  --model gpt-4.1-mini \
  --agent-name builder-a
```

Task writes use a `.tasks/.lock` file and atomic file replacement. Invalid task files are skipped with warnings instead of breaking the whole task system. Subagents get only read-only task tools (`list_tasks`, `get_task`, `can_start`) in v1.

## Agent Team

`agent chat` starts an in-process team runtime for the current chat session. The Lead agent is named by `--agent-name` (default `main`) and may spawn up to four persistent teammate daemon threads. Each teammate has its own `AgentSession`, context, and file inbox. `agent run` does not enable team tools.

Team communication uses workspace-local JSONL inbox files:

```text
.teams/{teamId}/inboxes/{agent}.jsonl
```

`.teams/` is ignored by Git. It is runtime mailbox state, not a durable audit log. `/clear` does not stop teammates; leaving chat closes the runtime and asks active teammates to shut down.

The chat runtime exposes three Lead team tools:

- `spawn_teammate`: start a teammate with a role, initial task, and optional instructions.
- `send_message`: send structured messages through the team inbox; `to=lead` resolves to the Lead agent.
- `list_teammates`: show active, idle, waiting-permission, or terminated teammate threads.

Teammates can use `send_message`, local file/command tools, and shared task tools. They cannot use `spawn_teammate`, `list_teammates`, `subagent`, or scheduler tools. If a teammate attempts a soft-risk action such as `bash`, `write_file`, or `edit_file`, the runtime sends a `permission_request` to Lead and waits for a `permission_response`. A Lead-approved response still requires human CLI `y/N` approval before the teammate may proceed.

Messages from teammates are consumed by a Lead inbox poller and delivered as synthetic `<team_inbox>` turns to the same chat session. Team turn results print to stdout with a prefix:

```text
[team teammate_1] ...
```

## System Prompt Assembly

The final system prompt is assembled at runtime from sections instead of being hardcoded as one string. Core sections include identity, runtime model identity, user instructions, workspace, enabled tools, MCP tool guidance when MCP tools exist, task system guidance, team guidance, persistent memory index, project skill catalog, todo planning guidance, and compressed session memory.

Sections load from real runtime state: provider/model metadata comes from CLI configuration, enabled tools come from `ToolRegistry`, skills from the startup `skills/` scan, and long-term memory from the startup `.memory/` scan. The assembler caches identical system prompts inside the current agent runtime. Relevant long-term memory file contents are injected into the current user turn, not the system prompt, so the stable system sections remain cache-friendly.

The `model_identity` section is deliberately runtime-derived. If the model is asked what it is, it should answer as the local Java agent runtime using the configured provider/model, rather than guessing from the upstream model's self-description.

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

## Error Recovery

Model-call error recovery is enabled by default for the main agent loop. Provider errors are classified into typed categories:

- `PROMPT_TOO_LONG`: run `reactiveCompact`, then retry once.
- `OUTPUT_TRUNCATED`: raise the generation output budget and ask the model to continue once.
- `TRANSIENT`: retry with exponential backoff for rate limits, overloads, 5xx responses, timeouts, and connection resets.
- `NON_RETRYABLE`: return a clear failed `AgentResult`.

Recovery retries do not consume `--max-steps`. They are recorded in `AgentResult.recoveryEvents`. v1 does not switch to a backup model, and recovery only wraps the main loop model call; `autoCompact` and memory extraction keep their existing local fallback behavior.

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --provider openai-compatible \
  --model gpt-4.1-mini \
  --generation-max-output-tokens 8192 \
  --recovery-max-output-tokens 65536 \
  --model-retry-attempts 3 \
  "write a detailed plan"

java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --no-error-recovery \
  "debug without recovery retries"
```

`--max-output-tokens` remains the context-compression budget parameter. Use `--generation-max-output-tokens` for the `max_tokens` value sent to the model provider.

## Runtime Trace And Audit

The CLI can show a safe realtime trace of what the agent is doing without printing provider `reasoning_content` or raw long-form model thinking. Trace lines are written to stderr and final answers remain on stdout.

- `agent chat` enables trace by default; use `--no-trace` to hide it.
- `agent run` keeps trace off by default; use `--trace` to enable it.
- `AgentResult.traceEvents()` contains the same runtime trace events for SDK callers.
- Persistent safe audit is enabled by default and writes `.runs/{runId}.jsonl` plus `.runs/RUNS.jsonl`; use `--no-audit` to disable it for one run/chat process.
- `AgentResult.runId()` and `AgentResult.auditEvents()` expose the same safe audit timeline to SDK callers.

Trace output uses a single-line format:

```text
[trace] tool_call tool=bash args="command=pwd" truncated=false runId=run_...
```

The v1 event set is `MODEL_CALL`, `TOOL_CALL`, `TOOL_RESULT`, `TASK_NOTIFICATION`, `COMPRESSION`, `RECOVERY`, `PERMISSION_DENIED`, `ERROR`, and `FINAL_ANSWER`. Tool arguments and tool results are summarized to 500 characters by default, mark whether they were truncated, and mask obvious sensitive keys such as tokens, passwords, secrets, API keys, credentials, authorization values, and large write/edit content fields.

Audit files use JSONL and preserve the same safe-summary boundary. They record `runId`, sequence, timestamp, actor, phase, parent/correlation ids, event type, and event attributes. They do not store full provider reasoning, raw messages, API keys, or full tool payloads.

```bash
agent runs list
agent runs show run_...
agent runs show run_... --json
```

## Behavioral Evals

Deterministic behavioral evals live under `evals/` and run through the `agent-evals` Maven module. These evals use JSON cases plus a scripted model to verify runtime behavior such as tool calling, permission denial, MCP-shaped tools, teammate permission boundaries, and model-call recovery. They do not call live LLM providers by default and do not evaluate model quality.

Each case declares a task, profile, scripted `modelSteps`, and expected safe audit patterns. The runner asserts against `AgentResult.auditEvents()` rather than CLI trace formatting, then writes a JSON report under `agent-evals/target/eval-reports/`.

```bash
mvn -pl agent-evals test
```

Minimal case shape:

```json
{
  "id": "tool_calling_read_file_loop",
  "category": "tool-calling",
  "profile": "dev",
  "task": "Read a project file and summarize it.",
  "modelSteps": [
    {
      "type": "tool_call",
      "toolCallId": "call_read_1",
      "toolName": "read_file",
      "arguments": { "path": "README.md" }
    },
    {
      "type": "final_answer",
      "content": "Read file completed."
    }
  ],
  "expect": {
    "completed": true,
    "finalAnswerContains": "completed",
    "auditSequence": [
      { "type": "TOOL_CALL", "attributes": { "tool": "read_file" } },
      { "type": "TOOL_RESULT", "attributes": { "tool": "read_file", "success": "true" } }
    ]
  }
}
```

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

`ToolRegistry.defaultTools()` registers `todo_write`, task tools, and five local tools. The CLI uses the runtime default registry, which also includes `subagent` because it needs the active model client. In `agent chat`, the runtime registry also adds scheduler tools and team tools. When the project has a non-empty `skills/` catalog, the runtime registry also includes `load_skill`. When MCP is enabled and `.mcp.json` has configured stdio servers, the main/Lead registry appends `mcp__...` tools after local tools.

- `todo_write`: create or replace the agent's planning todo list. It does not read files, run commands, or perform work.
- `load_skill`: load the full `SKILL.md` content for a project skill by name. It is read-only and limited to the startup skill catalog.
- `subagent`: start an isolated investigation agent with a clean context. It returns only a final report and cannot spawn another subagent.
- `create_task`: create a durable project task in `.tasks/{id}.json`.
- `list_tasks`: list durable project tasks as compact summaries.
- `get_task`: return one task's full JSON and dependency details.
- `can_start`: check dependency readiness for a task.
- `claim_task`: claim a pending task for the current `--agent-name`.
- `complete_task`: complete an owned in-progress task and report newly unlocked downstream tasks.
- `schedule_task`: in `agent chat`, create session-only or durable cron/once scheduled tasks.
- `list_scheduled_tasks`: in `agent chat`, inspect current scheduled jobs.
- `cancel_scheduled_task`: in `agent chat`, cancel a scheduled job by `jobId`.
- `spawn_teammate`: in `agent chat`, start a persistent teammate thread.
- `send_message`: in `agent chat`, send a structured team inbox message.
- `list_teammates`: in `agent chat`, inspect teammate thread status.
- `bash`: run a non-interactive shell command from the current workspace; in `agent chat`, it can use `run_in_background`.
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

`bash` is a guarded local runner, not an OS-level sandbox. It fixes the command working directory to the workspace, applies a timeout, rejects interactive commands, and blocks obvious dangerous or workspace-escaping patterns. Foreground bash defaults to 30 seconds with a 120 second max; background bash uses a 30 minute max and still requires the same `PreToolUse` approval.

`subagent` is investigation-only in v1. Its child tool set is `todo_write`, optional `load_skill`, read-only task tools (`list_tasks`, `get_task`, `can_start`), `read_file`, `glob`, and `bash`; it does not receive `create_task`, `claim_task`, `complete_task`, `write_file`, `edit_file`, or `subagent`.

Before any tool executes, the agent applies a permission gate:

1. hard-deny rules block operations that are never allowed;
2. soft-risk rules request approval for `bash`, `write_file`, and `edit_file`;
3. CLI approval asks for `y/N` in an interactive terminal, and denies by default when non-interactive.

`todo_write`, `load_skill`, `subagent`, task tools, scheduler tools, team tools, `read_file`, and `glob` are allowed by default. Task tools only write controlled `.tasks` JSON, not arbitrary files. Tools whose names start with `mcp__` always require user approval because they execute in external server processes. A child subagent's or teammate's `bash` calls still pass through the same approval hook.

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
{"command": "npm run dev", "run_in_background": true}
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
