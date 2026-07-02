# Agent 从 0 到 1 的关键路径

这不是一次性总结，而是这个 Java 17 agent 项目的 **Living Roadmap + Decision Log**。它要跟着项目持续更新：每次新增关键能力、调整架构边界、改变安全策略或引入新 provider，都应该同步更新这里。

文档保留能力递进主线，但每个阶段都按固定结构维护：

```text
Status / Why / Current implementation / Next
```

## Current Focus

- 当前阶段：命令行可用的 OpenAI-compatible agent，具备 chat 进程内 agent team、多 Agent task system、运行时 system prompt assembly、error recovery、安全 realtime trace、chat 进程内 cron scheduler、chat 进程内 background tasks、长期 persistent memory、进程内 conversation session、上下文压缩、原生 tool calling、todo planning、subagent 上下文隔离、按需 skill 加载、本地五工具、hook registry 和执行前权限闸门。
- 当前重点：把 agent team 通信、task graph、prompt 生成、错误恢复、运行态可观测、定时自动交付、慢操作后台化、长期记忆、干净会话续接、任务规划、上下文隔离、按需上下文加载、工具执行、安全审批、hook 扩展和 provider 扩展沉淀成稳定工程边界。
- Last updated: 2026-07-02

## Decision Log

| Date | Decision | Why |
| --- | --- | --- |
| 2026-06-30 | 使用 Maven 多模块：`agent-api`、`agent-core`、`agent-provider-openai-compatible`、`agent-cli` | 让公共契约、loop、provider、CLI 分层演进 |
| 2026-06-30 | 先保留 `EchoModelClient`，真实模型走独立 provider | 本地测试不依赖 API key 或网络 |
| 2026-06-30 | Provider 采用 OpenAI-compatible Chat Completions，而不是绑定单一厂商 | OpenAI 和 Qwen 可复用同一路径 |
| 2026-06-30 | Tool API 使用 JSON Schema 和结构化 `JsonNode` arguments | 支撑原生 function/tool calling |
| 2026-06-30 | 引入 `ToolRegistry`，CLI 不直接维护工具列表 | 新增工具只写 handler 并注册，agent loop 不变 |
| 2026-06-30 | 默认本地工具为 `bash/read_file/write_file/edit_file/glob` | 让命令行 agent 能真正操作项目 |
| 2026-06-30 | 第一版工具权限保持 workspace-only | 避免从项目 agent 过早升级成整机执行器 |
| 2026-06-30 | 工具执行前统一进入权限闸门：hard deny -> soft ask -> user approval | 安全策略集中在 agent loop 前，不分散到各工具 |
| 2026-06-30 | 引入 hook registry，并把权限闸门迁移为 `PreToolUse` hook | loop 只触发扩展点，不直接调用权限检查函数 |
| 2026-06-30 | `todo_write` 先作为普通工具，加 `UserPromptSubmit` reminder hook，不做硬门 | 先提升模型规划质量，避免过早引入跨 step 状态和强制流程控制 |
| 2026-06-30 | `subagent` 先做调查型上下文隔离工具，不给写文件和递归能力 | 隔离调用链追踪等中间过程，避免主 agent messages 膨胀，同时保留主 agent 对修改的控制权 |
| 2026-06-30 | conversation session 先做进程内最近 10 轮成功问答，不落盘、不带工具轨迹 | 让 agent 支持连续对话，同时避免把中间工具过程滚进长期上下文 |
| 2026-07-01 | `load_skill` 只读取项目内 `skills/` 启动快照，并通过 prompt assembly 的 `skill_catalog` section 注入短目录 | 用按需加载降低 system prompt token，保持 workspace-only 边界，避免把个人/插件技能混入项目 agent |
| 2026-07-01 | 上下文压缩采用 run 内压缩视图 + session compressed memory，原始 `steps` 不改写 | 让模型获得干净上下文，同时保留调试和审计所需的完整执行轨迹 |
| 2026-07-01 | 长期 memory 采用项目本地 `.memory/` + `MEMORY.md`，索引常驻 system，内容按需注入当前 turn | 跨压缩、跨会话保留稳定知识，同时避免把所有记忆全文塞进每轮上下文 |
| 2026-07-01 | system prompt 改为运行时 section assembly，并通过 `PromptAssemblyHook` 统一生成 | 避免 identity、tools、memory、skills、todo、session memory 分散硬拼，按真实状态加载并缓存稳定结果 |
| 2026-07-01 | 模型调用错误统一成 provider-neutral typed errors，并在主 loop 中恢复 | 输出截断、上下文超限和临时故障是生产常态；恢复应回到 loop，而不是让 provider 异常炸出 CLI |
| 2026-07-01 | 增加 runtime `model_identity` prompt section | 模型自述不可靠，回答“我是什么模型”应来自 CLI/provider/model 配置，而不是上游模型猜测 |
| 2026-07-01 | `agent chat` 支持进程内后台 `bash` 任务，完成后用独立 `TASK_NOTIFICATION` 注入 | 慢操作不阻塞主循环，同时保持 OpenAI tool_call/tool_result 一一配对合法 |
| 2026-07-01 | `AgentStep` 增加 provider metadata，OpenAI-compatible provider 保存并回传 `reasoning_content` | 兼容 DeepSeek/Qwen 等 thinking 模式要求，把 provider 特有协议留在 provider 层 |
| 2026-07-01 | 新增安全 runtime trace：chat 默认开、run 需 `--trace`，stderr 单行输出，最终答案留在 stdout | 提供类似 Codex 的运行态可观测性，但不默认暴露 provider reasoning_content 或模型原始长思考 |
| 2026-07-01 | `agent chat` 增加进程内 cron scheduler，调度管理先作为普通工具，durable 定义写 `.scheduled_tasks.json` | 让 agent 可以在空闲时自动处理定时任务，同时保持无人值守权限默认拒绝和本机私有持久化 |
| 2026-07-02 | 新增 `.tasks/{id}.json` task system，默认可提交，owner 来自 `--agent-name`，subagent 只读 | 为多 Agent 分工提供可恢复任务图，同时避免调查型 subagent 直接改写项目队列 |
| 2026-07-02 | `agent chat` 增加进程内 agent team，队友通过 `.teams/{teamId}/inboxes/*.jsonl` 通信，Lead 自动消费 inbox | 用持久队友上下文承担并行协作，同时保持当前进程生命周期、文件邮箱和人工审批边界清晰 |

## Stage 1: Project Shape

**Status:** Done, still maintained.

**Why:**  
先搭工程边界，再写 agent 能力。agent 项目很容易把 API、loop、provider、CLI、工具全部揉在一起；早期拆模块可以避免后续接入新模型或新入口时重写核心。

**Current implementation:**

- `agent-api`: 公共 SDK 契约。
- `agent-core`: agent loop、mock model、工具注册、本地工具、权限闸门。
- `agent-provider-openai-compatible`: OpenAI-compatible Chat Completions provider。
- `agent-cli`: picocli 命令行入口。
- 根 `pom.xml` 统一 Java 17、JUnit、AssertJ、Jackson、SLF4J、picocli、logback 和 Maven 插件版本。

**Next:**

- 当工具生态继续膨胀时，评估是否拆出 `agent-tools-local`。
- 当 provider 增多时，评估是否引入 provider SPI 或 provider factory。

## Stage 2: Public API Contract

**Status:** Done, expected to evolve carefully.

**Why:**  
公共 API 是后续所有模块的稳定连接点。核心目标是不绑定任何真实模型厂商，也不把工具参数压成字符串协议。

**Current implementation:**

- `Agent`: 对外执行入口。
- `AgentRequest`: task、maxSteps、metadata、systemPrompt、conversationHistory、memory、modelOptions、notifications。
- `AgentResult`: output、steps、completed、compressionEvents、recoveryEvents、traceEvents。
- `AgentStep`: `THOUGHT`、`TOOL_CALL`、`TOOL_RESULT`、`TASK_NOTIFICATION`、`FINAL_ANSWER`、`ERROR`，并带 provider metadata 扩展槽。
- `BackgroundTaskNotification`: 后台任务完成通知，不复用原始 `tool_call_id`。
- `ConversationMessage`: 跨轮对话历史，只支持 `USER` 和 `ASSISTANT`。
- `ModelClient`: 根据 request、history、tools 产出下一步。
- `ModelException` / `ModelErrorCode`: provider-neutral typed model errors。
- `ModelOptions`: 生成参数，v1 先包含 `maxOutputTokens`。
- `TraceEvent`: 安全运行态事件，用于 CLI realtime trace 和 SDK 观测。
- `Tool`: name、description、parametersSchema、execute。
- `ToolRequest`: 结构化 `JsonNode arguments`。
- `ToolResult`: success/output/error。

**Next:**

- `systemPrompt` 字段保持兼容，但语义是用户附加指令；默认 identity 由 prompt assembly 生成。
- 如果引入持久会话，需要评估 `AgentRequest` 是否增加 session id 或 conversation metadata。
- 如果工具结果要支持结构化 observation，需要评估 `ToolResult` 是否增加 machine-readable payload。

## Stage 3: Agent Loop

**Status:** Done, central abstraction.

**Why:**  
agent loop 应该只负责模型步骤、工具分发、轨迹记录和终止条件，不应该知道具体 provider 或具体工具。

**Current implementation:**

```java
for (int i = 0; i < request.maxSteps(); i++) {
    AgentStep nextStep = modelClient.nextStep(request, steps, toolRegistry.tools());
    steps.add(nextStep);

    if (nextStep.type() == FINAL_ANSWER) return completedResult;
    if (nextStep.type() == ERROR) return failedResult;
    if (nextStep.type() == TOOL_CALL) steps.add(executeTool(nextStep, request));
}
return incompleteResult;
```

- `DefaultAgent` 持有 `ModelClient` 和 `ToolRegistry`。
- `DefaultAgent` 只通过 `HookRegistry` 触发扩展点，不直接调用权限检查函数。
- `ModelCallRecovery` 包裹每次主 loop 的模型调用；恢复重试不追加 `steps`，也不消耗 `maxSteps`。
- `DefaultAgent` 记录安全 `TraceEvent`，并可通过 `TraceSink` 实时输出。
- `TOOL_CALL` 通过 tool name 查 registry。
- 工具成功、工具失败、权限拒绝都以 `TOOL_RESULT` 回传模型。
- 权限系统自身异常或工具 handler 崩溃才进入 `ERROR`。
- `maxSteps` 防止无限循环。

**Next:**

- 支持多 tool call / parallel tool calls 前，需要重新设计一次 step 执行批处理。
- 如果引入 streaming，需要明确 loop 的同步/异步边界。

## Stage 4: Mock/Echo Local Closure

**Status:** Done, kept for tests and smoke checks.

**Why:**  
真实模型会引入网络、鉴权、额度、代理和响应格式问题。Echo 路径保证 agent loop、CLI 和测试可以在无 API key 的环境下运行。

**Current implementation:**

- `EchoModelClient`: 默认直接返回 echo final answer；当显式注册 `EchoTool` 时可走简单 tool loop。
- `EchoTool`: 保留为测试/手动注册工具。
- CLI 默认 provider 为 `echo`，用于本地 smoke test。

**Next:**

- 如果后续有更复杂的本地 mock，可引入 scripted model client，用来复现多轮 tool calling 场景。

## Stage 5: CLI Entry

**Status:** Done, primary user interface for v1.

**Why:**  
最早的可用界面应该简单、可测试、可打包。CLI 可以快速验证每次能力新增：编译、测试、打包、运行。

**Current implementation:**

- `agent run "task"` 支持命令行 task。
- 省略 task 时从 stdin/console 读取。
- 支持 `--provider echo|openai-compatible`。
- 支持 `--model`，并 fallback 到 `OPENAI_MODEL`。
- 支持 `--system`。
- 新增 `agent chat`，支持进程内多轮会话。
- `agent chat` 复用进程内 `BackgroundTaskManager`，可让慢 `bash` 后台运行；`/clear` 不取消后台任务。
- `agent chat` 启动进程内 cron scheduler 和 queue processor；`/clear` 不清 scheduled jobs，不重载 durable 文件。
- `agent chat` 启动进程内 team runtime 和 Lead inbox poller；`/clear` 不停止 teammates。
- 使用 Maven shade 生成可执行 jar。
- CLI 为权限闸门提供交互式 `UserApprover`：提示 `y/N`，非交互默认拒绝。
- `agent chat` 默认向 stderr 打印安全 realtime trace；`agent run` 默认关闭，可用 `--trace` 打开。

**Next:**

- 增加 `--workspace` 明确指定工具工作区。
- 增加 `--yes` 或 policy 配置前，需要先定义审批风险模型，避免一键绕过安全边界。

## Stage 6: OpenAI-Compatible Provider

**Status:** Done, first real LLM provider.

**Why:**  
OpenAI-compatible Chat Completions 是当前最小可用的真实模型协议层。它同时覆盖 OpenAI 官方接口和 Qwen/通义千问这类兼容 endpoint。

**Current implementation:**

- 模块：`agent-provider-openai-compatible`。
- API：同步 Chat Completions `/v1/chat/completions`。
- message 顺序：system -> conversation history -> current user -> current run steps；`TASK_NOTIFICATION` 渲染为独立 user message。
- 环境变量：
  - `OPENAI_API_KEY`
  - `OPENAI_BASE_URL`
  - `OPENAI_MODEL`
  - `HTTPS_PROXY` / `HTTP_PROXY`
- 默认 base URL：`https://api.openai.com/v1`。
- Qwen 通过 `OPENAI_BASE_URL` 和 `OPENAI_MODEL` 复用同一路径。
- 第一版无 streaming；主 loop 的 retry/recovery 在 `agent-core` 中统一处理。
- provider 将 `max_tokens` 来自 `AgentRequest.modelOptions().maxOutputTokens()`。
- provider 将 `finish_reason=length` 映射为 `OUTPUT_TRUNCATED`，将 413/context-too-long 映射为 `PROMPT_TOO_LONG`，将 429/529/5xx/IO timeout 映射为 `TRANSIENT`。
- provider 保存 assistant message 中的 `reasoning_content` 到 `AgentStep.metadata()`，并在后续渲染 assistant tool-call message 时原样带回，兼容要求回传 thinking 内容的 OpenAI-compatible endpoints。

**Next:**

- 增加 request/response debug log 时必须避免泄露 API key。
- 如果兼容厂商出现差异，优先在 provider 内做协议兼容，不要污染 `agent-core`。
- 如果后续还有 provider 特有 message 字段，优先通过 `AgentStep.metadata()` 保留和回传，而不是扩展通用 loop 语义。

## Stage 7: Native Tool Calling

**Status:** Done, core capability.

**Why:**  
真实 agent 不能靠字符串约定调用工具。原生 tool/function calling 需要工具 JSON Schema、结构化参数和 `tool_call_id` 传播。

**Current implementation:**

- Provider 将 `Tool` 转成 Chat Completions `tools`。
- 发送 `tool_choice=auto`。
- 发送 `parallel_tool_calls=false`，第一版只处理单 tool call。
- 模型返回 `tool_calls` 后转换为 `AgentStep.toolCall(toolCallId, toolName, arguments)`。
- 工具结果转换为 `role=tool` 消息，并保留 `tool_call_id`。

**Next:**

- 支持 parallel tool calls 前，需要 `AgentStep` 或 loop 层支持一轮多个工具调用。
- 如果迁移 Responses API，需要新增 provider 或 adapter，不要改动 `Tool` 基础契约。

## Stage 8: ToolRegistry

**Status:** Done, default dispatch mechanism.

**Why:**  
新增工具应该只新增 handler 并注册，而不是改 agent loop 或 CLI 工具列表。

**Current implementation:**

```java
ToolRegistry.defaultTools()
ToolRegistry.defaultToolsWithSubagent(modelClient, userApprover)
ToolRegistry.investigationTools()
ToolRegistry.builder().add(new XxxTool()).build()
registry.findByName(name)
registry.tools()
```

- `defaultTools()` 注册 `todo_write`、task system 工具和本地五工具。
- 当项目 `skills/` catalog 非空时，skill-aware 默认工厂额外注册 `load_skill`。
- `defaultToolsWithSubagent(...)` 在运行时额外注册 `subagent`，用于 CLI 默认装配。
- `agent chat` 的 runtime registry 额外注册 scheduler tools：`schedule_task/list_scheduled_tasks/cancel_scheduled_task`。
- `agent chat` 的 runtime registry 额外注册 team tools：`spawn_teammate/send_message/list_teammates`。
- `agent run` 不注册 scheduler/team tools，也不启动 scheduler/team runtime。
- `teammateTools(...)` 给队友使用，只包含 `send_message`、task system 工具、本地五工具；不包含 `spawn_teammate`、`list_teammates`、`subagent` 或 scheduler tools。
- `investigationTools()` 给子 agent 使用，只包含 `todo_write`、可选 `load_skill`、只读 task 工具、`read_file/glob/bash`。
- duplicate tool name fail fast。
- `DefaultAgent` 使用 registry 查找工具。
- CLI 只依赖 registry，不直接知道默认工具类。

**Next:**

- 工具体量增加后考虑拆分 `agent-tools-local`。
- 是否引入 ServiceLoader 需要谨慎，当前显式注册更利于安全审计。

## Stage 9: Local Tools

**Status:** Done, actively evolving.

**Why:**  
本地五工具让 agent 从“能对话”变成“能操作项目”。同时它们也是安全风险最高的部分，需要持续维护边界。

**Current implementation:**

- `bash`: 在 workspace 中运行非交互 shell command。
- `read_file`: 读取 UTF-8 文件。
- `write_file`: 写 UTF-8 文件，必要时创建父目录。
- `edit_file`: 恰好替换一次文本。
- `glob`: 在 workspace 内按 pattern 查文件。
- 默认 workspace 是进程当前目录。
- 路径工具 normalize 后必须仍在 workspace 内。
- 读写工具检查 symlink 逃逸。
- 输出最多 20,000 字符，超出时截断并标记。
- `bash` schema 暴露 `run_in_background`；只有 `agent chat` 启用后台 manager 时才会后台化，`agent run` 降级前台。

**Next:**

- `read_file` 可增加更明确的行号输出格式。
- `edit_file` 可支持 patch/diff 模式，但仍应保持“失败比误改好”。
- `bash` 若要更强安全性，需要 OS/container sandbox，而不是只靠字符串规则。

## Stage 10: Hook Registry

**Status:** Done, extension point for v1.

**Why:**  
agent loop 不应该因为权限、日志、上下文注入、结果检查等横切逻辑不断膨胀。hook registry 让 loop 只触发事件，扩展由注册表决定。

**Current implementation:**

- 事件：
  - `USER_PROMPT_SUBMIT`
  - `PRE_TOOL_USE`
  - `POST_TOOL_USE`
  - `STOP`
- 每个事件使用强类型 context。
- hook 返回 `CONTINUE`、`BLOCK`、`REPLACE`。
- 同事件多个 hook 按注册顺序串行执行。
- `REPLACE` 的 context 传给后续 hook。
- `BLOCK` 立即短路。
- `Stop` 在每次 run 即将返回前触发，覆盖 final answer、error、maxSteps incomplete。
- `PromptAssemblyHook` 作为默认第一个 `USER_PROMPT_SUBMIT` hook，统一生成 system prompt sections 和 per-turn relevant memory 注入。
- `MemoryExtractionHook` 在 `STOP` 事件中，对成功 turn 做规则检测和无工具 LLM 提取，写入长期 memory。
- 默认顺序：prompt assembly -> memory extraction on stop -> permission hook。

**Next:**

- 暂不引入 priority，注册顺序就是执行顺序。
- 暂不引入异步 hook。
- 如果未来 hook 数量增多，需要增加命名、调试输出和执行耗时记录。

## Stage 11: Permission Gate

**Status:** Done, must be kept in sync with tools.

**Why:**  
工具执行安全不能散落在每个 handler 里。权限闸门作为默认 `PreToolUse` hook 注册，保证所有工具调用在执行前经过同一条路径，同时不侵入 agent loop。

**Current implementation:**

```text
hard deny -> soft rule -> user approval -> execute
```

- Core 抽象：
  - `PermissionChecker`
  - `PermissionDecision`
  - `PermissionRequest`
  - `UserApprover`
- `PreToolUsePermissionHook` 在 `PRE_TOOL_USE` 事件中调用权限检查。
- `DefaultAgent` 默认构造器注册该 hook；CLI 注入带终端审批的默认 hook registry。
- hard deny 优先，命中后不询问用户。
- soft rule 命中后调用 `UserApprover`。
- 拒绝不会执行工具，拒绝原因作为 `TOOL_RESULT` 回传模型。
- 默认无 approver 时，软风险操作拒绝。
- CLI 在交互式终端询问 `y/N`，非交互默认拒绝。
- cron synthetic turn 标记 `agent.trigger=cron`，CLI approver 对这类 turn 的 soft-risk 操作默认拒绝，不读取终端确认。
- teammate 触发 soft-risk 操作时先向 Lead inbox 发送 `permission_request`，等待 `permission_response`；Lead 发送 approved response 前仍必须经过 CLI 人类 `y/N`。

**Current policy:**

- 永远拒绝：工作区外路径、symlink 逃逸、`sudo`、`su`、`shutdown`、`reboot`、`halt`、`mkfs`、`dd`、`rm -rf /`。
- 默认放行：`todo_write`、`load_skill`、`subagent`、task system 工具、`schedule_task`、`list_scheduled_tasks`、`cancel_scheduled_task`、team 通信/管理工具、`read_file`、`glob`。
- 默认询问：`bash`、`write_file`、`edit_file`。

**Next:**

- 若要支持 workspace 外访问，必须先引入显式 allowlist、审计日志和更强提示。
- 若要支持 session remember，必须定义作用域、过期、展示和撤销机制。

## Stage 12: Todo Planning Tool

**Status:** Done, expected to evolve after session state exists.

**Why:**  
agent 动手前需要先理清步骤，但第一版不应该把规划变成强制控制流。`todo_write` 先作为普通工具出现，配合 reminder hook 提醒模型在复杂、多步骤、运行命令或修改文件前先列计划。

**Current implementation:**

- `todo_write` 接收完整 `todos` 数组，每次调用覆盖当前计划。
- todo item 包含 `content` 和 `status`。
- status 仅允许 `pending`、`in_progress`、`completed`。
- 内容不能为空，最多一个 item 处于 `in_progress`。
- 工具不保存 mutable state，不读文件、不跑命令、不修改工作区。
- 当前 todo list 通过 `TOOL_RESULT` 留在 agent history 中。
- 只有注册了 `todo_write` 时，prompt assembly 才生成 `todo_planning` section，避免提示不可用工具。

**Next:**

- 如果引入 session/conversation 层，再评估是否增加 `TodoStore` 或 `todo_read`。
- 如果需要强制规划，再基于 hook 增加可配置 strict mode，而不是改 agent loop。

## Stage 13: Subagent Context Isolation

**Status:** Done, v1 is intentionally investigation-only.

**Why:**  
修 bug 或追调用链时，很多中间步骤只对调查有用，不应该长期占着主 agent 的 messages。`subagent` 让主 agent 开一个干净上下文执行局部调查，再只把最终结论带回主上下文。

**Current implementation:**

- `subagent` 是普通 Tool，CLI 运行时默认注册。
- 参数包含 `description`、可选 `expectedOutput`、可选 `maxSteps`。
- 子 agent 复用父 `ModelClient`，但使用独立 `AgentRequest` 和独立 steps。
- 子 agent 默认 6 步，最多 12 步。
- 子 agent 使用 `SUBAGENT` prompt mode，并只继承父 agent 的用户附加指令，不继承完整父 system prompt。
- `DefaultAgent` 通过保留 metadata key `agent.userInstructions` 把用户附加指令传给工具，并覆盖用户同名 metadata。
- 子 agent 工具集只包含 `todo_write`、可选 `load_skill`、只读 task 工具 `list_tasks/get_task/can_start`、`read_file/glob/bash`。
- 子 agent 不包含 `create_task`、`claim_task`、`complete_task`、`write_file`、`edit_file`、`subagent`，因此 v1 不写文件、不递归、不改写任务队列。
- 子 agent 只返回 final output；中间 steps 不回传给主 agent。
- 子 agent 的 `bash` 仍走 `PreToolUse` 权限 hook。

**Next:**

- 如果需要并发多个 subagent，需要先设计 trace、预算和错误聚合。
- 如果要允许子 agent 修改文件，必须先设计审计、diff 回传和主 agent 确认边界。

## Stage 14: Conversation Session

**Status:** Done, v1 is in-process only.

**Why:**  
agent 需要不止能处理一次 task，也要能在同一个命令行会话里接住上下文继续工作。但 session 不能重新制造 messages 膨胀，所以 v1 只跨轮保存成功问答，不保存工具轨迹。

**Current implementation:**

- `ConversationMessage` 表达跨轮 `USER` / `ASSISTANT` 历史。
- `AgentRequest` 携带 `conversationHistory`，现有构造器默认空历史。
- `AgentSession` 包装无状态 `Agent`，保存最近 10 轮 successful turns。
- 只有 `AgentResult.completed=true` 才进入 session history。
- 跨轮只带 user task 和 assistant final output，不带 `AgentStep` 工具轨迹。
- OpenAI-compatible provider 按 system -> history -> current user -> current steps 构造 messages。
- CLI 新增 `agent chat`，支持 `/clear`、`/exit`、`/quit`。
- `AgentSession.run(...)` 同步执行，用于串行化普通 chat turn 和 cron synthetic turn。
- session 只存在于当前 CLI 进程，不落盘。

**Next:**

- 如果需要跨进程恢复，再设计 session id、存储格式、清理和隐私边界。
- 如果需要更长上下文，再引入摘要压缩或长期记忆，而不是无限追加 history。

## Stage 15: On-Demand Skill Loading

**Status:** Done, v1 is project-local and snapshot-based.

**Why:**  
技能说明可能很长，全部塞进 system prompt 会持续占用 token。`load_skill` 把技能拆成两层：启动时只给模型短目录，真正需要时再加载完整 `SKILL.md`。

**Current implementation:**

- v1 只扫描当前项目的 `skills/` 目录，不读取 `~/.codex/skills` 或外部路径。
- `skills/` 不存在或为空时正常启动，不注册 `load_skill`，不注入 catalog。
- 只要发现 `SKILL.md`，就严格校验最小 frontmatter：文件开头 `---` 到下一行 `---`，必须有单行 `name` 和 `description`。
- `name` 必须唯一、非空，并只允许稳定标识符字符。
- 不新增 YAML 依赖，只实现最小 parser，忽略其他 frontmatter key。
- `SkillCatalog` 是启动快照：`agent run` 每次命令扫描一次，`agent chat` 启动扫描一次，`/clear` 不重扫。
- prompt assembly 的 `skill_catalog` section 注入 `name + description` 短目录。
- `load_skill(name)` 只按启动快照中的 name 查找，不接受 path。
- `load_skill` 返回完整 `SKILL.md`，沿用工具输出 20,000 字符截断策略。
- 主 agent 和 subagent 都可以使用 `load_skill`；子 agent 仍不能写文件或递归 spawn subagent。

**Next:**

- 暂不支持 `--skills-dir`、热加载或 `/reload-skills`。
- 如果技能 metadata 变复杂，再评估引入完整 YAML parser。
- 如果出现超大 skill，再评估分页或 section-level 加载。

## Stage 16: Context Compression

**Status:** Done, expected to evolve with memory quality.

**Why:**  
长任务会让 `steps` 和 tool results 快速膨胀；长会话也不能无限追加历史。上下文压缩的目标是“干净的记忆，无限的会话”：便宜规则先跑，贵的 LLM 摘要后跑，最后再用 prompt-too-long 兜底。

**Current implementation:**

- `AgentMemory` 是显式 SDK 字段，不伪装成 user/assistant 历史；最终展示由 prompt assembly 的 `session_memory` section 负责。
- `ContextManager` 在每轮 `ModelClient.nextStep(...)` 前生成压缩视图。
- 预处理顺序固定：`L3 toolResultBudget -> L1 snipCompact -> L2 microCompact -> L4 autoCompact`。
- token 预算用轻量估算：`ceil(chars / 4)`。
- 默认预算：`contextWindowTokens=128000`、`maxOutputTokens=4000`、`reservedTokens=13000`。
- `autoCompact` 复用当前 `ModelClient`，以无工具摘要请求生成 Markdown memory；连续 3 次失败后停止重试。
- provider 把 HTTP 413、`context_length_exceeded`、`prompt_too_long` 等错误映射为 `PromptTooLongException`。
- `reactiveCompact` 在 prompt-too-long 后保留 memory 和最近逻辑 step，再重试一次。
- `AgentResult.steps()` 保留原始轨迹；压缩发生情况通过 `compressionEvents` 暴露。
- `AgentSession` 超过短期窗口后，把旧成功问答折叠进 `AgentMemory`，保留最近 3 轮原文。
- CLI 默认启用压缩，支持 `--no-context-compression`、`--context-window-tokens`、`--max-output-tokens`、`--reserved-tokens`。

**Next:**

- 如果要提升 token 预算准确性，新增 provider-aware tokenizer，而不是把 tokenizer 绑定到 `agent-api`。
- 如果要提升 memory 质量，增加固定回归任务和摘要评估，不先引入持久化。
- 如果要做跨进程长期记忆，再设计 session id、存储、隐私和清理策略。

## Stage 17: Persistent Memory Layer

**Status:** Done for v1, intentionally simple.

**Why:**  
`AgentMemory` 解决的是当前 run/session 被压缩后的连续性，但它不跨进程，也会随着会话结束消失。长期 memory 解决另一件事：用户偏好、反复反馈、项目背景、常用入口和排查线索这些“以后还会用到”的知识，需要跨压缩、跨会话保留。

**Current implementation:**

- `.memory/` 保存每条长期 memory，根目录 `MEMORY.md` 保存一行一个链接的索引。
- `.memory/` 和 `MEMORY.md` 默认写入 `.gitignore`，v1 视为本机私有知识。
- 每个 memory 是带最小 frontmatter 的 Markdown 文件：`name`、`description`、`type`。
- `type` 只支持 `user`、`feedback`、`project`、`reference`。
- `MemoryCatalog` 启动时扫描 `.memory/`；坏文件跳过并警告，不阻断 agent 启动。
- `PromptAssemblyHook` 把索引作为 `persistent_memory_index` system section；`PromptAssembler` 用确定性关键词打分，把 top 3 文件内容注入当前 user turn。
- `MemoryExtractionHook` 在成功 turn 的 `STOP` 阶段触发：先用规则判断是否值得提取，再用当前 `ModelClient` 发起无工具提取请求。
- `echo` provider 只读取 memory，不写入；`openai-compatible` provider 默认启用读写。
- `agent memory list` 和 `agent memory show <name-or-file>` 提供最小管理入口。
- subagent 可以读取长期 memory 的索引和相关内容，但不注册写入 hook，避免局部调查污染全局知识。

**Next:**

- 增加 `agent memory compact`，定期去重、合并和整理过期条目。
- 增加更可解释的 retrieval trace，方便调试为什么某条 memory 被注入。
- 如果记忆规模变大，再评估 embedding 或 provider-aware rerank；v1 不引入向量库。
- 如果要团队共享，先设计隐私、审计、同步和冲突解决，不直接把 `.memory/` 纳入 Git。

## Stage 18: System Prompt Assembly

**Status:** Done for v1, new central prompt boundary.

**Why:**  
system prompt 不能继续由 `AgentRequest` 默认值、多个 `UserPromptSubmit` hook、provider 和 subagent 各自拼接。统一的 runtime assembly 让 prompt 由真实状态决定：工具是否存在、memory 是否存在、skill catalog 是否存在、session memory 是否存在，而不是靠消息里的关键词或散落的硬编码。

**Current implementation:**

- `PromptAssemblyHook` 是默认第一个 `USER_PROMPT_SUBMIT` hook。
- `PromptAssembler` 用 Java `PromptSection` 组装 system prompt，并缓存相同签名的结果。
- section 顺序固定：`identity`、`model_identity`、`user_instructions`、`workspace`、`tools`、`task_system`、`team_system`、`persistent_memory_index`、`skill_catalog`、`todo_planning`、`session_memory`、`subagent_expected_output`。
- `AgentRequest.systemPrompt` 保持 API 兼容，但语义变成用户附加指令；默认 identity 不再放在 `AgentRequest`。
- `model_identity` 来自 request metadata，由 CLI 注入 provider/model/base URL；不包含 API key。
- 当用户询问模型身份时，agent 应回答自己是本地 Java agent runtime，使用当前配置的 provider/model，而不是自称 Claude、Anthropic 或其他未在运行时配置中出现的身份。
- provider 不再单独追加 `AgentMemory`；它只发送已组装的 system prompt。
- relevant long-term memory 全文继续注入当前 user turn，不进入 system prompt，保持稳定 sections 更容易被 provider prompt cache 命中。
- subagent 使用 `SUBAGENT` prompt mode，只继承用户附加指令，不继承父 agent 的完整 system prompt。
- teammate 使用 `TEAMMATE` prompt mode，获得队友身份、inbox 通信规则和不能递归 spawn 的约束。
- `.memory` 和 `skills/` 仍按启动快照加载；chat 中新写入 memory 到下一次 run/chat 启动后生效。

**Next:**

- 如果 section 数量继续增加，给 prompt assembly 增加可观测输出，例如 section ids、cache hit/miss 和最终估算 token。
- 如果需要频繁调 prompt 文案，再评估 resources Markdown 模板；v1 保持 Java section 类。
- 如果要支持动态 reload，再先定义 memory/skills 的一致性和 cache invalidation 策略。

## Stage 19: Error Recovery

**Status:** Done for v1, focused on main loop model calls.

**Why:**  
真实 provider 的失败是常态：输出被 `max_tokens` 截断、压缩后仍然 context too long、429/529/5xx 或网络抖动都会发生。agent loop 需要把这些错误分类，然后用明确恢复动作回到模型调用，而不是让异常直接冒出 CLI。

**Current implementation:**

- `ModelErrorCode` 覆盖 `PROMPT_TOO_LONG`、`OUTPUT_TRUNCATED`、`TRANSIENT`、`NON_RETRYABLE`。
- `ModelException` 是 provider-neutral typed model error；`PromptTooLongException` 保持兼容并作为 `PROMPT_TOO_LONG` 特化。
- `ModelOptions` 挂在 `AgentRequest` 上，v1 先支持 `maxOutputTokens`，默认 `8192`。
- `AgentRecoveryEvent` 挂在 `AgentResult` 上，记录错误类型、恢复动作、attempt、恢复前后输出预算和结果。
- `DefaultAgent` 通过 `ModelCallRecovery` 包裹主 loop 的 `ModelClient.nextStep(...)`。
- recovery retry 不写入 `steps`，不消耗 `maxSteps`；只有恢复后的有效 `AgentStep` 才进入轨迹。
- `PROMPT_TOO_LONG`: 触发现有 `reactiveCompact`，重试一次；二次失败返回 failed result。
- `OUTPUT_TRUNCATED`: 把生成预算提升到默认 `65536`，追加 continuation prompt，重试一次；有 partial content 时拼接 partial + continuation。
- `TRANSIENT`: 默认最多 3 次指数退避重试，优先遵守 `Retry-After`。
- `NON_RETRYABLE`: 不重试，返回清晰 failed result。
- CLI 默认开启，支持 `--no-error-recovery`、`--model-retry-attempts`、`--generation-max-output-tokens`、`--recovery-max-output-tokens`。
- `--max-output-tokens` 保持为 context compression 预算参数，不复用为生成上限。

**Boundaries:**

- v1 不做备用模型切换。
- v1 不覆盖 streaming。
- v1 只恢复主 agent loop 的模型调用；`autoCompact` 和 memory extraction 保持自己的降级策略。
- v1 不改变 tool execution permission/retry 语义。

**Next:**

- 如果要切换备用模型，需要把 provider/model selection 从 CLI 装配提升为 runtime strategy。
- 如果要更精细的 transient 策略，需要记录 provider status、retry-after、attempt latency 和最终归因。
- 如果引入 streaming，需要重新定义“输出截断”的 partial recovery 边界。

## Stage 20: Background Tasks

**Status:** Done for v1, scoped to `agent chat` and local `bash`.

**Why:**  
慢命令不应该卡住主循环。开发服务器、watch、`tail -f`、长 sleep 这类操作可以先返回一个 `bg_id`，让模型继续做别的事；等任务完成后再把结果作为独立通知注入后续 turn。

**Current implementation:**

- `BackgroundTaskManager` 在进程内维护后台任务，最多同时运行 4 个。
- 后台任务使用 daemon thread；CLI 进程退出后不保证继续运行。
- v1 只允许 `bash` 后台化，其他工具仍同步执行。
- 权限顺序不变：`PreToolUse` approval 通过后，才会启动后台任务。
- `bash` schema 新增 `run_in_background`:
  - `true`: 显式后台。
  - `false`: 显式前台。
  - 缺省：只对保守匹配的长跑命令后台化。
- `agent run` 使用 disabled background manager；即使模型传 `run_in_background=true`，也前台执行。
- 后台启动立即返回占位 `TOOL_RESULT`，内容包含 `bg_id`、command 和 running 状态。
- 后台完成后生成 `BackgroundTaskNotification`，在下一次模型调用前追加为 `TASK_NOTIFICATION` step。
- OpenAI-compatible provider 将 `TASK_NOTIFICATION` 渲染成独立 `role=user` message，不带 `tool_call_id`。
- `AgentRequest.notifications` 用于 chat session 把上一轮之后完成的后台通知注入当前 run。
- `/clear` 只清 conversation/session memory，不取消后台任务，也不清空 manager。

**Boundaries:**

- v1 不支持 `list_background_tasks`、`stop_background_task`、增量读取输出或输出落盘。
- v1 不跨进程恢复后台任务。
- v1 不让 subagent 共享主 chat 的后台 manager。
- 后台输出继续沿用工具输出截断策略。

**Next:**

- 增加 `list_background_tasks` 和 `stop_background_task` 前，需要先定义任务状态、输出存储和用户审批语义。
- 如果后台任务要跨进程，需引入本地状态文件、日志文件和清理策略。
- 后续可把完成通知接入更正式的 message queue，而不是只在下一轮 loop 前轮询。

## Stage 21: Runtime Trace

**Status:** Done for v1, scoped to safe observability.

**Why:**  
命令行 agent 不能只吐最终答案。用户需要看到模型调用、工具调用、后台任务、压缩和恢复这些运行态事件，才能判断 agent 是否卡住、是否在执行危险操作、是否经历了压缩或重试。但 trace 不能等同于把 provider 的 `reasoning_content` 或模型原始长思考直接打印出来；默认应该展示安全摘要。

**Current implementation:**

- `TraceEvent` 挂在 `AgentResult.traceEvents()` 上。
- `DefaultAgent` 在主 loop 中记录以下事件：`MODEL_CALL`、`TOOL_CALL`、`TOOL_RESULT`、`TASK_NOTIFICATION`、`COMPRESSION`、`RECOVERY`、`PERMISSION_DENIED`、`ERROR`、`FINAL_ANSWER`。
- `TraceSink` 支持实时输出；CLI 使用 stderr sink，SDK 默认 no-op。
- `TraceFormatter` 使用单行格式，例如：

```text
[trace] tool_call tool=bash args="command=pwd" truncated=false
```

- `agent chat` 默认开启 realtime trace；`agent run` 默认关闭，可用 `--trace` 开启。
- 最终答案仍打印到 stdout，trace 打到 stderr，方便脚本管道只消费答案。
- 工具参数和工具结果只展示安全摘要，默认最多 500 字符，并标记 `truncated=true|false`。
- 明显敏感 key 会被 mask，包括 password、token、secret、api_key、authorization、credential、private_key，以及大段写入/编辑内容字段。
- provider `reasoning_content` 只用于兼容后续请求，不进入默认 trace。

**Next:**

- 如果 trace 要进入文件或 JSONL，需要新增 sink，而不是改变默认 stderr 单行格式。
- 可加入 section cache hit/miss、memory retrieval 命中原因等更细事件，但必须继续遵守安全摘要边界。
- 如果未来支持 streaming，trace 需要区分 token stream 和 agent lifecycle event。

## Stage 22: Cron Scheduler

**Status:** Done for v1, scoped to `agent chat`.

**Why:**  
有些任务不是用户立刻输入，而是到时间自动交付。cron scheduler 让 agent 在同一个进程内持续检查定时任务，时间到了就把任务排进队列，在 agent 空闲时作为 synthetic user turn 交给同一个 `AgentSession`。它和 background tasks 是两类能力：background task 处理“已经开始的慢操作”，cron scheduler 处理“未来某个时间才开始的任务”。

**Current implementation:**

- `CronScheduler` 是 daemon thread，默认每秒轮询一次。
- `CronQueue` 保存触发任务；同一个 job 同时最多一个 pending/running trigger，避免高频任务堆积。
- `CronQueueProcessor` 单线程 FIFO 消费 queue，并调用同一个 `AgentSession.run(...)`。
- `AgentSession.run(...)` 同步执行，避免普通 chat turn 和 cron turn 并发改写 session history/memory。
- `ScheduledTaskManager` 管理内存 jobs、next run time、durable load/save 和 once job 删除。
- `ScheduledJobStore` 读写 `.scheduled_tasks.json`；启动加载时坏 job 跳过并记录 warning，不拖垮启动。
- `.scheduled_tasks.json` 默认写入 `.gitignore`，和 `.memory/` 一样作为本机私有状态。
- 调度管理作为普通工具注册到 `agent chat` runtime registry：
  - `schedule_task`
  - `list_scheduled_tasks`
  - `cancel_scheduled_task`
- `agent run` 不启动 scheduler，也不暴露 scheduler tools。
- recurring job 使用六字段秒级 cron：`second minute hour day month weekday`。
- v1 cron 子语法支持 `*`、`*/n`、单值、逗号列表、`a-b` 范围；不支持 `?`、`L`、`W`、`#`。
- day-of-month 和 weekday 在 v1 都是过滤条件；如果都指定，必须同时匹配。
- once job 使用 `runAt` ISO-8601 时间；支持 `zoneId`，不填用 JVM/system zone。
- recurring durable job 在进程关闭期间错过的触发不补跑；once durable job 如果启动时已到期，启动后触发一次并删除。
- cron turn 的 `AgentRequest.metadata` 标记 `agent.trigger=cron`，任务正文使用 `<cron_trigger>` 包装。
- cron turn 使用同一套 tools/prompt/memory/trace，但审批器看到 `agent.trigger=cron` 后对 soft-risk 操作默认拒绝。
- cron 自动结果打印到 stdout，格式为 `[cron <jobId> <name>] <answer>`；trace 仍打印到 stderr。

**Next:**

- 如果需要无人值守写文件或跑命令，先设计创建时授权、授权范围、过期和审计，不直接绕过现有审批。
- 如果要跨进程运行 scheduler，需要引入外部进程或系统调度器；v1 durable 只持久化任务定义。
- 如果任务规模变大，需要增加 queue 上限、任务状态查询、失败重试和调度 trace。
- 如果需要团队共享 scheduled jobs，先设计隐私和冲突策略，不默认提交 `.scheduled_tasks.json`。

## Stage 23: Task System / Multi-Agent Coordination

**Status:** Done for v1, expected to grow with multi-agent runtime.

**Why:**  
`todo_write` 只能表达当前 agent 的临时执行清单，不能跨会话恢复，也不能表达多 Agent 的任务认领、依赖和解锁。Task system 把项目级工作项落到 `.tasks/`，让多个 agent 可以围绕同一个 durable task graph 协作。

**Current implementation:**

- 每个 task 是 `.tasks/{id}.json`，字段为 `id/subject/description/status/owner/blockedBy`。
- 状态机保持最小：`claim_task` 负责 `pending -> in_progress` 并写入 owner；`complete_task` 负责 `in_progress -> completed`。
- ID 自动生成，格式为 `task_000001`；`.tasks/.highwatermark` 防止删除后 ID 重用。
- `.tasks/` 默认不写入 `.gitignore`，可作为项目协作状态提交；只忽略运行时锁 `.tasks/.lock`。
- 写操作通过 `.tasks/.lock` 跨进程文件锁和 atomic move 保存，减少多 Agent 同时认领时的竞态。
- `blockedBy` 允许前向引用；`can_start` 遇到不存在依赖时返回 blocked，而不是崩溃。
- 工具集：`create_task/list_tasks/get_task/can_start/claim_task/complete_task`。
- `owner` 来自 CLI/runtime metadata `agent.name`，由 `--agent-name` 配置，默认 `main`；模型不能通过工具参数伪造 owner。
- 坏 JSON、非法 status、重复 id 等坏 task 文件会跳过并作为 warning 返回，不拖垮整个工具系统。
- prompt assembly 在 task 工具存在时生成 `task_system` section，解释 task system 和 `todo_write` 的边界，但不注入完整 task 列表。
- subagent 只获得只读 task 工具，不能创建、认领或完成任务。

**Next:**

- 设计 release/reassign/reopen/handoff，解决 owner 卡死和人工接管。
- 如果多进程冲突变多，需要增加操作审计和冲突可视化。
- 如果任务数量增大，需要增加分页、索引、归档和 task graph 视图。

## Stage 24: Agent Team / Persistent Teammates

**Status:** Done for v1, intentionally chat-only.

**Why:**  
`subagent` 是临时调查工具，适合把一段调用链追踪隔离出去；agent team 解决的是另一类问题：长期协作的队友需要自己的上下文、自己的 inbox、能接收后续消息并持续汇报。单个 agent 的上下文窗口有限，团队让不同 agent 分担模块和任务，但仍把生命周期、通信和权限控制在当前 `agent chat` 进程内。

**Current implementation:**

- `agent chat` 创建一个进程内 `TeamRuntime`，每次 chat 启动生成新的 `teamId`。
- Lead 名称来自 `--agent-name`，默认 `main`；消息中的 `lead` 是这个实际名称的别名。
- 文件邮箱路径为 `.teams/{teamId}/inboxes/{agent}.jsonl`，`.teams/` 默认写入 `.gitignore`。
- `MessageBus` 对 append/consume 使用进程内 per-inbox lock + 文件锁；consume 语义是 read + truncate。
- `TeamMessage` envelope 包含 `messageId/teamId/type/from/to/createdAt/content/correlationId/payload`。
- v1 消息类型：`plain_text`、`task_assignment`、`status_update`、`permission_request`、`permission_response`、`shutdown_request`、`shutdown_ack`、`teammate_terminated`。
- Lead chat registry 暴露 `spawn_teammate/send_message/list_teammates`。
- `spawn_teammate` 最多启动 4 个活跃 teammate daemon threads；每个 teammate 有独立 `AgentSession` 和独立 messages。
- teammate 每个 inbox turn 固定 `maxSteps=10`，idle loop 约每秒检查自己的 inbox。
- teammate 工具集包含 `send_message`、本地五工具和共享 task tools；不包含 `spawn_teammate/list_teammates/subagent/scheduler`。
- teammate 初始 task 通过 `task_assignment` 投递到自己的 inbox；处理完一轮后用 `status_update` 汇报 Lead。
- Lead inbox poller 约每秒消费 Lead inbox；当 session 空闲时，把消息包装成 `<team_inbox>` synthetic turn 调用同一个 `AgentSession.run(...)`。
- team synthetic turn 结果输出为 `[team <from>] ...`，trace 仍走 stderr。
- `/clear` 只清 conversation/session memory，不停止 teammates；退出 chat 时发送 shutdown request。
- teammate 触发 `bash/write_file/edit_file` 等 soft-risk 工具时，runtime 向 Lead 发送 `permission_request` 并等待 `permission_response`；Lead approval 仍要经过 CLI 人类审批。

**Next:**

- 增加队友 idle/permission/termination 更完整的 trace 和可观测状态。
- 增加 teammate-to-task 更强集成，例如自动 claim/release/handoff。
- 如果要跨进程恢复 team，需要先把 `.teams/` 从 runtime mailbox 升级为可恢复状态，并处理坏消息、重复投递和队友进程重启。
- 如果要支持 teammate-to-teammate topology、broadcast 或 plan approval，需要先扩展 `TeamMessageType` 和权限模型。

## Stage 25: Testing and Verification

**Status:** Done, expanded with each capability.

**Why:**  
每个关键能力都需要最小回归闭环，否则 agent loop、provider、工具和权限策略会互相踩踏。

**Current implementation:**

- `agent-api`: record 行为、metadata/system prompt、conversation history、model options、notifications、recovery events、trace events、结构化工具参数。
- `agent-core`: loop、agent team/message bus、task system、runtime trace、cron scheduler、background tasks、error recovery、session、context compression、persistent memory、prompt assembly、max steps、tool call id、工具失败回传、hook registry、权限闸门、registry duplicate、subagent 上下文隔离。
- `agent-provider-openai-compatible`: fake HTTP client 覆盖 final answer、tool call、task notification、conversation message order、`max_tokens`、HTTP/typed error 映射、JSON 解析失败、缺少 API key。
- `agent-cli`: 默认 echo、provider 参数校验、`OPENAI_MODEL` fallback、trace stdout/stderr 分流、team chat/run 差异、cron chat/run 差异、background chat/run 差异、error recovery 参数、`agent chat`、交互式审批和非交互默认拒绝。
- local tools: task create/list/get/can_start/claim/complete、workspace 内读写改查、越界拒绝、symlink 逃逸、bash exit code/timeout/危险命令。

**Verification commands:**

```bash
mvn test
mvn -pl agent-cli -am package
java -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar run "hello local tools"
```

**Next:**

- 引入固定 agent task regression suite。
- 增加 live smoke test 时默认跳过，只在显式 env 存在时运行。

## Open Questions

- 是否需要 `--workspace`，让工具工作区不依赖进程 cwd？
- 是否需要 `agent-tools-local` 模块，降低 `agent-core` 对 OS 工具的耦合？
- 是否需要 structured observation，而不是只把工具结果放在字符串里？
- 是否要支持 streaming 和 parallel tool calls？
- hook 是否需要命名、优先级、禁用开关和执行耗时观测？

## Maintenance Rules

- 每次新增关键能力，都更新对应 stage 的 `Status / Current implementation / Next`。
- 每次做架构取舍，都追加一条 `Decision Log`。
- 每次修改 hook 扩展点，都同步更新 `Stage 10: Hook Registry`。
- 每次修改安全边界，都同步更新 `Stage 11: Permission Gate`。
- 每次修改 skill catalog、`load_skill` 或按需上下文加载，都同步更新 `Stage 15: On-Demand Skill Loading`。
- 每次修改长期 memory 的存储、检索、注入或提取策略，都同步更新 `Stage 17: Persistent Memory Layer`。
- 每次修改 system prompt 组成、section 顺序或 `--system` 语义，都同步更新 `Stage 18: System Prompt Assembly`。
- 每次修改模型错误分类、恢复策略或 CLI recovery 参数，都同步更新 `Stage 19: Error Recovery`。
- 每次修改后台任务生命周期、通知格式或 chat/run 差异，都同步更新 `Stage 20: Background Tasks`。
- 每次修改 cron 表达式语义、scheduler 生命周期、durable 存储或 cron 权限边界，都同步更新 `Stage 22: Cron Scheduler`。
- 每次修改 task schema、owner/claim 语义、依赖规则或 `.tasks` 持久化策略，都同步更新 `Stage 23: Task System / Multi-Agent Coordination`。
- 每次修改 team message schema、inbox 路径、teammate 生命周期、team 工具或 teammate 权限边界，都同步更新 `Stage 24: Agent Team / Persistent Teammates`。
- 每次新增 provider，都同步更新 `Stage 6` 或新增 provider stage。
- 文档可以记录未完成事项，但不要把它改成零散 changelog；能力递进主线必须保留。
