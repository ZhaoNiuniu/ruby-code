# Agent 从 0 到 1 的关键路径

这不是一次性总结，而是这个 Java 17 agent 项目的 **Living Roadmap + Decision Log**。它要跟着项目持续更新：每次新增关键能力、调整架构边界、改变安全策略或引入新 provider，都应该同步更新这里。

文档保留能力递进主线，但每个阶段都按固定结构维护：

```text
Status / Why / Current implementation / Next
```

## Current Focus

- 当前阶段：命令行可用的 OpenAI-compatible agent，具备运行时 system prompt assembly、长期 persistent memory、进程内 conversation session、上下文压缩、原生 tool calling、todo planning、subagent 上下文隔离、按需 skill 加载、本地五工具、hook registry 和执行前权限闸门。
- 当前重点：把 prompt 生成、长期记忆、干净会话续接、任务规划、上下文隔离、按需上下文加载、工具执行、安全审批、hook 扩展和 provider 扩展沉淀成稳定工程边界。
- Last updated: 2026-07-01

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
- `AgentRequest`: task、maxSteps、metadata、systemPrompt、conversationHistory。
- `AgentResult`: output、steps、completed。
- `AgentStep`: `THOUGHT`、`TOOL_CALL`、`TOOL_RESULT`、`FINAL_ANSWER`、`ERROR`。
- `ConversationMessage`: 跨轮对话历史，只支持 `USER` 和 `ASSISTANT`。
- `ModelClient`: 根据 request、history、tools 产出下一步。
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
- 使用 Maven shade 生成可执行 jar。
- CLI 为权限闸门提供交互式 `UserApprover`：提示 `y/N`，非交互默认拒绝。

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
- message 顺序：system -> conversation history -> current user -> current run steps。
- 环境变量：
  - `OPENAI_API_KEY`
  - `OPENAI_BASE_URL`
  - `OPENAI_MODEL`
  - `HTTPS_PROXY` / `HTTP_PROXY`
- 默认 base URL：`https://api.openai.com/v1`。
- Qwen 通过 `OPENAI_BASE_URL` 和 `OPENAI_MODEL` 复用同一路径。
- 第一版无 streaming，无自动 retry。

**Next:**

- 增加 request/response debug log 时必须避免泄露 API key。
- 如果兼容厂商出现差异，优先在 provider 内做协议兼容，不要污染 `agent-core`。

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

- `defaultTools()` 注册 `todo_write` 和本地五工具。
- 当项目 `skills/` catalog 非空时，skill-aware 默认工厂额外注册 `load_skill`。
- `defaultToolsWithSubagent(...)` 在运行时额外注册 `subagent`，用于 CLI 默认装配。
- `investigationTools()` 给子 agent 使用，只包含 `todo_write`、可选 `load_skill`、`read_file/glob/bash`。
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

**Current policy:**

- 永远拒绝：工作区外路径、symlink 逃逸、`sudo`、`su`、`shutdown`、`reboot`、`halt`、`mkfs`、`dd`、`rm -rf /`。
- 默认放行：`todo_write`、`load_skill`、`subagent`、`read_file`、`glob`。
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
- 子 agent 工具集只包含 `todo_write`、可选 `load_skill`、`read_file/glob/bash`。
- 子 agent 不包含 `write_file`、`edit_file`、`subagent`，因此 v1 不写文件、不递归。
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
- section 顺序固定：`identity`、`user_instructions`、`workspace`、`tools`、`persistent_memory_index`、`skill_catalog`、`todo_planning`、`session_memory`、`subagent_expected_output`。
- `AgentRequest.systemPrompt` 保持 API 兼容，但语义变成用户附加指令；默认 identity 不再放在 `AgentRequest`。
- provider 不再单独追加 `AgentMemory`；它只发送已组装的 system prompt。
- relevant long-term memory 全文继续注入当前 user turn，不进入 system prompt，保持稳定 sections 更容易被 provider prompt cache 命中。
- subagent 使用 `SUBAGENT` prompt mode，只继承用户附加指令，不继承父 agent 的完整 system prompt。
- `.memory` 和 `skills/` 仍按启动快照加载；chat 中新写入 memory 到下一次 run/chat 启动后生效。

**Next:**

- 如果 section 数量继续增加，给 prompt assembly 增加可观测输出，例如 section ids、cache hit/miss 和最终估算 token。
- 如果需要频繁调 prompt 文案，再评估 resources Markdown 模板；v1 保持 Java section 类。
- 如果要支持动态 reload，再先定义 memory/skills 的一致性和 cache invalidation 策略。

## Stage 19: Testing and Verification

**Status:** Done, expanded with each capability.

**Why:**  
每个关键能力都需要最小回归闭环，否则 agent loop、provider、工具和权限策略会互相踩踏。

**Current implementation:**

- `agent-api`: record 行为、metadata/system prompt、conversation history、结构化工具参数。
- `agent-core`: loop、session、context compression、persistent memory、prompt assembly、max steps、tool call id、工具失败回传、hook registry、权限闸门、registry duplicate、subagent 上下文隔离。
- `agent-provider-openai-compatible`: fake HTTP client 覆盖 final answer、tool call、conversation message order、HTTP 失败、JSON 解析失败、缺少 API key。
- `agent-cli`: 默认 echo、provider 参数校验、`OPENAI_MODEL` fallback、`agent chat`、交互式审批和非交互默认拒绝。
- local tools: workspace 内读写改查、越界拒绝、symlink 逃逸、bash exit code/timeout/危险命令。

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
- 每次新增 provider，都同步更新 `Stage 6` 或新增 provider stage。
- 文档可以记录未完成事项，但不要把它改成零散 changelog；能力递进主线必须保留。
