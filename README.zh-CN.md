# ruby-code
自己打造的 AI Agent

## 模块

- `agent-api`：Agent、模型客户端、工具、请求、结果和步骤的公共 SDK 契约。
- `agent-core`：一个最小化的 ReAct 风格工具循环，包含本地命令/文件工具和 echo 模型客户端。
- `agent-provider-openai-compatible`：兼容 OpenAI Chat Completions 的 provider，支持原生 tool calling。
- `agent-cli`：基于 picocli 的命令行入口，用于本地冒烟测试。

## 环境要求

- JDK 17
- Maven 3.9+

## 构建和测试

```bash
mvn test
```

## 路线图和决策

- [Agent 从 0 到 1 的关键路径](docs/agent-from-zero-to-one.md)：持续更新的路线图和决策日志。

## 运行 CLI

```bash
mvn -pl agent-cli -am package
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run "hello agent"
```

也可以省略任务参数，进入交互式输入：

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run
```

或者通过 stdin 管道传入任务：

```bash
echo "hello agent" | java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run
```

使用 OpenAI-compatible provider 运行：

```bash
export OPENAI_API_KEY="sk-..."
# 可选；也可以通过 --model 覆盖：
# export OPENAI_MODEL="gpt-4.1-mini"
# 第三方兼容端点可选：
# export OPENAI_BASE_URL="https://api.example.com/v1"
# 网络需要本地代理时可选：
# export HTTPS_PROXY="http://127.0.0.1:7890"

java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --provider openai-compatible \
  --model gpt-4.1-mini \
  "Use the echo tool for hello"
```

对于暴露 thinking-mode 字段的 OpenAI-compatible 端点，provider 会把 assistant 的 `reasoning_content` 保存在 step metadata 中；当端点要求后续 assistant tool-call 消息携带该字段时，也会再发送回去。

通过 OpenAI-compatible 端点运行 Qwen / 通义千问：

```bash
export OPENAI_API_KEY="${API_KEY}"
export OPENAI_BASE_URL="https://idealab.alibaba-inc.com/api/openai/v1"
export OPENAI_MODEL="qwen3-coder-plus"

java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --provider openai-compatible \
  "hello qwen"
```

需要时可以添加自定义用户指令。运行时仍会围绕它组装 agent 身份、工具、工作区、记忆、技能和规划等 section：

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run \
  --provider openai-compatible \
  --model gpt-4.1-mini \
  --system "You are a careful command-line agent." \
  "hello agent"
```

启动进程内多轮会话：

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar chat \
  --provider openai-compatible \
  --model gpt-4.1-mini
```

`agent chat` 会为成功的 user/assistant 对话保留 10 轮短期窗口。超过该窗口后，较早的轮次会折叠进压缩记忆，最近的轮次仍作为普通对话历史保留。它不会跨进程持久化 session，也不会把工具 trace 带入下一轮。使用 `/clear` 可以重置当前 session 历史和压缩 session 记忆，使用 `/exit` 或 `/quit` 离开 chat。`/clear` 不会取消后台任务。

## 后台任务

`agent chat` 可以在后台运行较慢的 `bash` 命令，让主循环继续工作。模型可以通过 `run_in_background: true` 显式请求后台运行；如果省略该标志，运行时只会把明显长期运行的命令放到后台，例如 dev server、watch 命令、`tail -f`、`sleep`、Spring Boot 启动或 `python -m http.server`。`mvn test`、`mvn package` 这类构建和测试命令默认仍在前台运行。

后台任务生命周期绑定到当前 CLI chat 进程。任务启动后会立即返回一个带有 `bg_id` 的占位 `TOOL_RESULT`；任务完成时，下一轮 chat 会收到一个独立的 `<task_notification>` 用户消息。完成通知不会复用原始 `tool_call_id`，因此原生 tool-call 配对仍然有效。

`agent run` 使用禁用状态的后台管理器。如果模型在 one-shot 模式中请求 `run_in_background: true`，命令会改为前台执行，避免进程退出后留下孤立任务。

## Cron 调度器

`agent chat` 会启动一个进程内 cron 调度器。调度器运行在 daemon 线程中，大约每秒检查一次任务，把到期工作放入 `cron_queue`，再由单个队列处理器把每次触发作为合成用户轮次交给同一个 `AgentSession`。`agent run` 不会启动调度器。

chat 运行时暴露三个调度器工具：

- `schedule_task`：创建仅当前 session 有效或持久化的定时任务。
- `list_scheduled_tasks`：查看任务、下次运行时间、持久化状态和队列状态。
- `cancel_scheduled_task`：通过 `jobId` 取消任务。

周期任务使用六字段、精确到秒的 cron 表达式：

```text
second minute hour day month weekday
```

v1 支持 `*`、`*/n`、单个值、逗号列表和 `a-b` 范围。不支持 `?`、`L`、`W` 或 `#`。day-of-month 和 weekday 都是过滤条件；如果两者都指定了具体值，则两者都必须匹配。一次性任务使用 `type=once` 和 ISO-8601 格式的 `runAt`；本地日期时间使用 `zoneId` 或系统时区。

持久化任务会写入 `.scheduled_tasks.json`，该文件被 Git 忽略。它们会在 `agent chat` 启动时加载；`/clear` 不会重新加载或清除定时任务。如果 agent 进程关闭期间错过了某个周期性持久化任务，v1 不会补跑历史触发。如果一次性持久化任务在启动时已经到期，它会触发一次并被移除。

Cron 轮次复用与普通 chat 轮次相同的 session history、memory、tools、trace 和 prompt assembly。它们会用 `<cron_trigger>` wrapper 标记。Cron 轮次使用非交互式审批：`bash`、`write_file`、`edit_file` 这类 soft-risk 工具默认拒绝，而只读工具保持正常允许行为。Cron 结果会带前缀打印到 stdout：

```text
[cron job_1 daily-check] ...
```

## 任务系统

任务系统是一个用于多 agent 协作的持久化项目工作队列。它不同于 `todo_write`：`todo_write` 是当前 agent 的短生命周期执行清单，而 task 文件是可恢复、带依赖和 ownership 的项目工作项。

任务以 JSON 文件存储在 `.tasks/{id}.json` 下。ID 按 `task_000001`、`task_000002` 依次生成；`.tasks/.highwatermark` 防止删除后复用 ID。`.tasks/` 默认不会被忽略，因此任务图可以提交和共享。只有运行时 lock 文件 `.tasks/.lock` 会被忽略。

运行时暴露六个任务工具：

- `create_task`：创建持久化任务，可选 `blockedBy` 依赖。
- `list_tasks`：列出简洁任务摘要，可选 `status`、`owner` 和 `canStart` 过滤。
- `get_task`：返回完整任务 JSON 和依赖就绪详情。
- `can_start`：检查所有依赖是否已完成；缺失依赖会被视为 blocked。
- `claim_task`：把任务从 `pending` 移到 `in_progress`，并记录运行时 `--agent-name` 为 owner。
- `complete_task`：完成一个由当前 owner 持有的 `in_progress` 任务，并报告新解锁的下游任务。

使用 `--agent-name` 区分多个 agent 进程：

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar chat \
  --provider openai-compatible \
  --model gpt-4.1-mini \
  --agent-name builder-a
```

任务写入使用 `.tasks/.lock` 文件和原子文件替换。无效任务文件会被跳过并给出 warning，而不是让整个任务系统失败。v1 中 subagent 只获得只读任务工具（`list_tasks`、`get_task`、`can_start`）。

## Agent 团队

`agent chat` 会为当前 chat session 启动一个进程内团队运行时。Lead agent 由 `--agent-name` 命名（默认为 `main`），最多可以 spawn 四个持久 teammate daemon 线程。每个 teammate 都有自己的 `AgentSession`、context 和文件 inbox。`agent run` 不启用团队工具。

团队通信使用工作区本地 JSONL inbox 文件：

```text
.teams/{teamId}/inboxes/{agent}.jsonl
```

`.teams/` 被 Git 忽略。它是运行时 mailbox 状态，不是持久化审计日志。`/clear` 不会停止 teammate；离开 chat 会关闭运行时，并请求活跃 teammate 停止。

chat 运行时为 Lead 暴露三个团队工具：

- `spawn_teammate`：用角色、初始任务和可选指令启动 teammate。
- `send_message`：通过团队 inbox 发送结构化消息；`to=lead` 会解析到 Lead agent。
- `list_teammates`：查看 active、idle、waiting-permission 或 terminated 的 teammate 线程。

Teammate 可以使用 `send_message`、本地文件/命令工具和共享任务工具。它们不能使用 `spawn_teammate`、`list_teammates`、`subagent` 或调度器工具。如果 teammate 尝试 `bash`、`write_file`、`edit_file` 这类 soft-risk 操作，运行时会向 Lead 发送 `permission_request` 并等待 `permission_response`。即使 Lead 批准，teammate 在继续执行前仍需要人类 CLI 的 `y/N` 审批。

来自 teammate 的消息由 Lead inbox poller 消费，并作为合成 `<team_inbox>` 轮次交付给同一个 chat session。团队轮次结果会带前缀打印到 stdout：

```text
[team teammate_1] ...
```

## System Prompt 组装

最终 system prompt 在运行时由多个 section 组装，而不是硬编码为一个字符串。核心 section 包括身份、运行时模型身份、用户指令、工作区、启用工具、任务系统指引、团队指引、持久记忆索引、项目技能目录、todo 规划指引和压缩 session 记忆。

Section 来自真实运行时状态：provider/model metadata 来自 CLI 配置，启用工具来自 `ToolRegistry`，skills 来自启动时的 `skills/` 扫描，长期记忆来自启动时的 `.memory/` 扫描。assembler 会在当前 agent runtime 内缓存相同的 system prompt。相关长期记忆文件内容会注入当前用户轮次，而不是注入 system prompt，因此稳定的 system section 仍然对缓存友好。

`model_identity` section 有意从运行时派生。如果模型被问到“你是什么”，它应该回答自己是使用已配置 provider/model 的本地 Java agent runtime，而不是根据上游模型的自我描述来猜测。

## 上下文压缩

`agent run` 和 `agent chat` 默认启用上下文压缩。Agent 会保留原始执行步骤用于调试，但当工具结果、step 数量或估算 prompt 大小过大时，会向模型发送压缩后的 context view。

默认预算使用轻量估算方式 `chars / 4` 计算 token：

```text
context window: 128000
max output:     4000
reserved:       13000
```

可以通过 CLI 调整或禁用：

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

`agent chat` 仍然只在进程内运行。当成功的对话历史超过短期窗口时，较早轮次会折叠进压缩记忆，最近轮次保留为普通对话历史。`/clear` 会移除对话历史和压缩记忆。

## 错误恢复

主 agent 循环默认启用模型调用错误恢复。Provider 错误会被分类为带类型的类别：

- `PROMPT_TOO_LONG`：运行 `reactiveCompact`，然后重试一次。
- `OUTPUT_TRUNCATED`：提高生成输出预算，并要求模型继续一次。
- `TRANSIENT`：对 rate limit、overload、5xx response、timeout 和 connection reset 使用指数退避重试。
- `NON_RETRYABLE`：返回清晰的失败 `AgentResult`。

恢复重试不会消耗 `--max-steps`。它们会记录在 `AgentResult.recoveryEvents` 中。v1 不会切换到备用模型，且恢复只包裹主循环模型调用；`autoCompact` 和 memory extraction 仍保留现有本地 fallback 行为。

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

`--max-output-tokens` 仍是上下文压缩预算参数。发送给模型 provider 的 `max_tokens` 值请使用 `--generation-max-output-tokens`。

## 运行时 Trace

CLI 可以展示安全的实时 trace，用于观察 agent 正在做什么，但不会打印 provider 的 `reasoning_content` 或原始长篇模型思考。Trace 行写入 stderr，最终回答仍保留在 stdout。

- `agent chat` 默认启用 trace；使用 `--no-trace` 隐藏。
- `agent run` 默认关闭 trace；使用 `--trace` 启用。
- `AgentResult.traceEvents()` 为 SDK 调用方提供相同的运行时 trace events。

Trace 输出使用单行格式：

```text
[trace] tool_call tool=bash args="command=pwd" truncated=false
```

v1 事件集合为 `MODEL_CALL`、`TOOL_CALL`、`TOOL_RESULT`、`TASK_NOTIFICATION`、`COMPRESSION`、`RECOVERY`、`PERMISSION_DENIED`、`ERROR` 和 `FINAL_ANSWER`。工具参数和工具结果默认摘要为 500 字符，标记是否被截断，并会遮蔽明显敏感的 key，例如 tokens、passwords、secrets、API keys、credentials、authorization values，以及较大的 write/edit content 字段。

## 持久记忆

持久记忆是项目本地且默认私有的。Agent 把长期记忆存储在 `.memory/` 下，并维护根目录 `MEMORY.md` 索引；两者都被 Git 忽略。该层独立于进程内 `AgentMemory`：持久记忆可以跨压缩和新 CLI 进程保留，而 `AgentMemory` 只负责保持当前 run/session 连贯。

每条记忆都是一个带最小 frontmatter 的 Markdown 文件：

```markdown
---
name: tab-indentation
description: User prefers tabs instead of spaces for indentation.
type: user
---

Use tabs instead of spaces when editing project code.
```

支持的 memory type 有 `user`、`feedback`、`project` 和 `reference`。启动时 CLI 会扫描 `.memory/` 一次：`agent run` 每个命令扫描一次，`agent chat` 在整个 chat 进程中扫描一次。`/clear` 不会重新加载持久记忆。

读取路径是确定性的：`MEMORY.md` 索引会作为运行时 system section 注入；然后根据当前任务和最近对话匹配最多三条相关 memory 文件，并注入当前用户轮次。`echo` provider 只读取 memory。`openai-compatible` provider 也可以在成功轮次后写入 memory，前提是用户明确要求 agent 记住某事，或陈述了稳定偏好、反馈模式、项目事实或参考线索。

通过 CLI 管理本地记忆：

```bash
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar memory list
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar memory show tab-indentation
```

无效 memory 文件会被跳过并给出 warning，而不是导致启动失败。v1 不会压缩、去重或同步 memory；这些属于独立维护步骤。

## 默认工具

`ToolRegistry.defaultTools()` 会注册 `todo_write`、任务工具和五个本地工具。CLI 使用运行时默认 registry；其中也包含 `subagent`，因为它需要当前 active model client。在 `agent chat` 中，运行时 registry 还会添加调度器工具和团队工具。当项目存在非空 `skills/` 目录时，运行时 registry 还会包含 `load_skill`。

- `todo_write`：创建或替换 agent 的规划 todo list。它不会读取文件、运行命令或执行工作。
- `load_skill`：按名称加载某个项目 skill 的完整 `SKILL.md` 内容。它是只读的，并且限制在启动时的 skill catalog 内。
- `subagent`：启动一个带干净上下文的隔离调查 agent。它只返回最终报告，且不能再 spawn 另一个 subagent。
- `create_task`：在 `.tasks/{id}.json` 中创建持久化项目任务。
- `list_tasks`：以简洁摘要列出持久化项目任务。
- `get_task`：返回一个任务的完整 JSON 和依赖详情。
- `can_start`：检查任务依赖是否就绪。
- `claim_task`：为当前 `--agent-name` claim 一个 pending 任务。
- `complete_task`：完成一个由当前 owner 持有的 in-progress 任务，并报告新解锁的下游任务。
- `schedule_task`：在 `agent chat` 中创建 session-only 或持久化 cron/once 定时任务。
- `list_scheduled_tasks`：在 `agent chat` 中查看当前定时任务。
- `cancel_scheduled_task`：在 `agent chat` 中通过 `jobId` 取消定时任务。
- `spawn_teammate`：在 `agent chat` 中启动一个持久 teammate 线程。
- `send_message`：在 `agent chat` 中发送结构化团队 inbox 消息。
- `list_teammates`：在 `agent chat` 中查看 teammate 线程状态。
- `bash`：从当前工作区运行非交互式 shell 命令；在 `agent chat` 中可使用 `run_in_background`。
- `read_file`：读取 UTF-8 文本文件。
- `write_file`：写入 UTF-8 文本到文件，必要时创建父目录。
- `edit_file`：精确替换文件中的一处文本。
- `glob`：按 glob pattern 查找工作区文件。

注册 `todo_write` 后，默认 `UserPromptSubmit` hook 会提醒模型在复杂、多步骤、需要运行命令或修改文件的任务中使用它。简单问题和一步只读任务可以跳过。

## 项目技能

项目技能是可选的。如果 `skills/` 缺失或为空，CLI 会正常启动，并且不会注册 `load_skill` 或注入 skill catalog。如果存在一个或多个 `SKILL.md` 文件，CLI 会在启动时扫描它们一次：`agent run` 每个命令扫描一次，`agent chat` 在整个 chat 进程中扫描一次。`/clear` 不会重新加载 skills。

每个 skill 必须位于本仓库的 `skills/` 树下，并以最小 frontmatter 开头：

```markdown
---
name: java-agent-style
description: Guidance for implementing Java 17 agent features in this project.
---

Full skill instructions go here.
```

启动 catalog 只读取 `name` 和 `description`。完整文件只会在模型调用时加载：

```json
{"name": "java-agent-style"}
```

无效 skill frontmatter、重复名称或不支持的名称字符都会让 agent 启动快速失败。`load_skill` 不接受文件路径，也不能读取启动 catalog 之外的内容。

所有文件路径都会在进程工作目录内解析。从本仓库运行时，该工作区为：

```text
/Users/mengru/Projects/ruby-code
```

`bash` 是一个受保护的本地 runner，而不是 OS 级 sandbox。它会把命令工作目录固定到 workspace，应用 timeout，拒绝交互式命令，并阻止明显危险或逃逸 workspace 的模式。前台 bash 默认 30 秒超时，最长 120 秒；后台 bash 最长 30 分钟，并且仍需要相同的 `PreToolUse` 审批。

`subagent` 在 v1 中仅用于调查。它的 child tool set 包括 `todo_write`、可选 `load_skill`、只读任务工具（`list_tasks`、`get_task`、`can_start`）、`read_file`、`glob` 和 `bash`；它不会收到 `create_task`、`claim_task`、`complete_task`、`write_file`、`edit_file` 或 `subagent`。

在任何工具执行前，agent 都会应用权限门禁：

1. hard-deny 规则阻止永远不允许的操作；
2. soft-risk 规则会为 `bash`、`write_file` 和 `edit_file` 请求审批；
3. CLI approval 会在交互式终端中询问 `y/N`，非交互模式默认拒绝。

`todo_write`、`load_skill`、`subagent`、任务工具、调度器工具、团队工具、`read_file` 和 `glob` 默认允许。任务工具只写受控的 `.tasks` JSON，不写任意文件。child subagent 或 teammate 的 `bash` 调用仍然会经过相同 approval hook。

工具参数示例：

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
