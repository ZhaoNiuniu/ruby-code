# Agent 二期：从 CLI Runtime 到可配置 Agent Platform

这份文档是二期项目的 **Living Roadmap + Decision Log**。一期已经证明了核心 agent runtime 的能力边界：loop、tools、hooks、permission、memory、compression、team、MCP、CLI 都能跑通。二期不再以“继续堆功能”为主，而是把这些能力收拢成可配置、可审计、可评估、可服务化的平台。

文档固定维护：

```text
Status / Why / Design direction / Next
```

## Current Focus

- 当前阶段：**Stage 2 Policy Config / Permission Engine v1 已落地**，下一步进入 **Stage 3 Run Trace And Audit Log**。
- 当前重点：让 policy 决策可审计、可回放，把命中的 policy name、context、action 和审批结果写入持久 run trace。
- Last updated: 2026-07-07

## Phase 2 North Star

**从命令行可用的 agent runtime，演进为可配置 Agent Platform。**

二期的判断标准不是“又加了几个工具”，而是：

- 同一个 runtime 能用不同 profile 启动成不同能力边界的 agent。
- 权限策略能被声明、解释、测试和审计，而不是散落在代码 if/else。
- 每次 agent run/chat turn 都能留下可复盘 trace。
- agent 行为能被 eval 固定下来，升级后能回归验证。
- CLI 仍是一等入口，但核心 runtime 可以被 HTTP/WebSocket/UI 复用。

## Decision Log

| Date | Decision | Why |
| --- | --- | --- |
| 2026-07-07 | 二期主题定为“从 CLI Runtime 到可配置 Agent Platform” | 一期已完成能力骨架，二期重点应转向工程化、可控性和复用性 |
| 2026-07-07 | 二期第一阶段先做 runtime profile 和 policy config，不先做 UI/HTTP | 配置和策略是服务化、评估、审计、团队运行的共同底座 |
| 2026-07-07 | CLI 参数保留，但语义改为覆盖 profile，而不是所有运行时能力都从 CLI 拼装 | 避免 CLI 成为装配中心，让 SDK 和未来服务入口复用同一套 runtime assembly |
| 2026-07-07 | Stage 1 v1 以 `agent-runtime` 输出 `RuntimeSettings`，CLI 生命周期暂不整体搬迁 | 先统一配置解析、工具过滤和 profile-aware permission，避免一次性重构 run/chat、team、cron、background 造成风险 |
| 2026-07-07 | Stage 2 v1 采用独立 `.agent/policies/{name}.json`，profile 只引用 policy | 权限策略成为可复用、可审计的配置资产；hard deny 仍由代码保底 |

## Stage 1: Runtime Profile Config

**Status:** Implemented v1; hardening and factory extraction remain.

**Why:**  
现在 runtime 装配散落在 CLI：provider/model、tools、MCP、memory、scheduler、team、trace、compression、recovery、approval 都由命令行类直接拼起来。继续这样做会让 HTTP 入口、测试入口和多 profile 运行都重复装配逻辑。

**Current implementation:**

- 新增 `agent-runtime` Maven 模块，负责 profile 解析、合并、校验、sanitized 展示、工具过滤和 profile-aware permission 兜底。
- 支持内置 `dev` / `readonly` profile；项目可用 `.agent/profiles/{name}.json` 覆盖内置或定义自定义 profile。
- 仓库提供初版项目 profile：`.agent/profiles/dev.json`、`.agent/profiles/readonly.json`、`.agent/profiles/openai-compatible.json`。
- 不传 `--profile` 等价于 `implicit-dev`，保持一期 CLI 默认行为兼容。
- CLI 支持：

```bash
agent run --profile dev "..."
agent chat --profile readonly
agent profile show readonly
```

- `agent profile show <name>` 只打印 sanitized effective profile，不启动 provider、不连接 MCP、不启动 agent。
- `readonly` 同时通过 registry 隐藏高风险工具、通过 permission checker 兜底拒绝。
- 环境变量 fallback 只在 `openai-compatible` provider 下参与 model/baseUrl 解析，避免 echo profile 展示无关 provider 状态。

**Design direction:**

- profile 配置示例：

```text
.agent/profiles/dev.json
.agent/profiles/readonly.json
.agent/profiles/team-dev.json
```

- v1 先用 JSON，复用 Jackson，不新增 YAML 依赖。
- `dev` / `readonly` 是内置 profile；项目文件可以覆盖同名内置 profile。
- 自定义 profile 必须来自 `.agent/profiles/{name}.json`。
- CLI 参数可以覆盖 profile 中的同名字段，例如 `--model` 覆盖 profile model。
- profile 只描述 runtime 装配，不保存 secret。
- secret 继续来自环境变量，例如 `OPENAI_API_KEY`。
- profile 配置是安全边界，格式非法、字段未知、类型错误、workspace 外路径等都 fail fast。

**Initial shape:**

```json
{
  "provider": "openai-compatible",
  "model": "deepseek-v4-flash",
  "system": "Prefer small diffs.",
  "tools": {
    "local": true,
    "subagent": true,
    "team": true,
    "scheduler": false,
    "mcp": true,
    "mcpConfig": ".mcp.json"
  },
  "memory": {
    "persistent": true,
    "session": true
  },
  "contextCompression": {
    "enabled": true,
    "contextWindowTokens": 128000,
    "maxOutputTokens": 4000,
    "reservedTokens": 13000
  },
  "errorRecovery": {
    "enabled": true,
    "modelRetryAttempts": 3,
    "generationMaxOutputTokens": 8192,
    "recoveryMaxOutputTokens": 65536
  },
  "trace": {
    "enabled": true,
    "sink": "stderr"
  },
  "policy": {
    "name": "dev"
  }
}
```

**Merge priority:**

```text
built-in defaults
< built-in profile dev/readonly
< project profile .agent/profiles/{name}.json
< CLI overrides
< environment fallback/secrets
```

环境变量只做 secret/fallback：

- `OPENAI_API_KEY` 只作为 secret。
- `OPENAI_MODEL` 只在 profile 和 CLI 都没设置 model 时 fallback。
- `OPENAI_BASE_URL` 只在 profile 和 CLI 都没设置 base URL 时 fallback。

**Readonly boundary:**

- registry 层隐藏高风险工具，减少模型尝试不可用工具。
- permission 层兜底拒绝，防止未来 registry 装配错误导致越权。
- `readonly` 隐藏或拒绝：`bash/write_file/edit_file/create_task/claim_task/complete_task/schedule_task/cancel_scheduled_task/spawn_teammate/send_message/mcp__*`。
- `readonly` 可保留只读能力：`todo_write/read_file/glob/load_skill/list_tasks/get_task/can_start/list_scheduled_tasks/list_teammates`。
- hard deny 仍由核心安全规则保底，profile 不能放行 workspace escape、symlink escape、`sudo`、`rm -rf /` 等。

**Implementation boundary:**

- 新增 `agent-runtime` 模块作为 profile/config glue layer。
- Stage 1 先输出 `RuntimeSettings` 和 profile-aware permission/tool decisions。
- CLI 暂时继续负责 `run`/`chat` 生命周期、cron/team/background 启停、stdout/stderr/颜色和审批交互。
- 后续再逐步抽出完整 `AgentRuntimeFactory`，不要在 Stage 1 一次性重构整个 CLI。

**Next:**

- 把 `RuntimeSettings -> ModelClient/ToolRegistry/HookRegistry/ContextManager` 的装配继续抽成 `AgentRuntimeFactory`。
- 让 `trace.sink` 真正支持 `stderr|file|both|none`，而不是只在 CLI 中控制实时输出。
- 继续细化 `readonly` 对 team/scheduler 生命周期的边界：当前 v1 隐藏/拒绝工具，完整生命周期开关留到 factory 阶段。
- 扩展 README/profile schema 文档，给出 `dev`、`readonly`、OpenAI-compatible profile 示例。
- Stage 2 已引入独立 policy config；下一步继续把 runtime factory 和 audit sink 抽出来。

## Stage 2: Policy Config / Permission Engine

**Status:** Implemented v1; hardening remains.

**Why:**  
一期权限策略已经集中到 `DefaultPermissionChecker`，但还是代码硬编码。二期需要让权限策略可配置、可解释、可测试，尤其是 MCP、team、cron、无人值守场景。

**Current implementation:**

- 引入 `.agent/policies/{name}.json` 独立 policy 文件；profile 通过 `policy.name` 引用。
- `policy.mode` 作为兼容字段保留，`dev/readonly` 会映射到同名 policy。
- 内置 `dev/readonly` policy 存在；项目文件可提供 `.agent/policies/dev.json`、`.agent/policies/readonly.json` 覆盖。
- `PolicyPermissionChecker` 固定顺序：hard safety check -> policy rule match -> user approver。
- 多 context 同时命中时按最严格动作合并：`DENY > ASK_USER > ALLOW`。
- 当前 profile 稳定 context 明确 `deny` 的工具会从 registry 隐藏，执行前仍由 checker 兜底拒绝。

**Design direction:**

- policy profile 示例：

```json
{
  "defaultAction": "ask",
  "tools": {
    "read_file": "allow",
    "glob": "allow",
    "bash": "ask",
    "write_file": "ask",
    "edit_file": "ask",
    "mcp__*": "ask"
  },
  "contexts": {
    "cron": {
      "bash": "deny",
      "write_file": "deny",
      "edit_file": "deny",
      "mcp__*": "deny"
    },
    "readonly": {
      "bash": "deny",
      "write_file": "deny",
      "edit_file": "deny"
    }
  }
}
```

- hard deny 仍在代码中保底，policy 不能放行 workspace escape、`sudo`、`rm -rf /` 等硬拒绝。
- soft rule 改为 policy decision：`ALLOW / ASK_USER / DENY`。
- risk summary 仍由工具和 checker 生成，供 CLI/未来 UI 展示。

**Next:**

- 把 policy schema 文档提炼到 README 或独立 reference。
- 继续细分 tool risk summary，让 policy `ask` 从默认 allow 升级时展示更具体风险。
- 在 Stage 3 audit log 中记录命中的 policy name、context 和 action。
- 评估是否需要在 Stage 3/4 增加 live policy eval，覆盖 MCP、cron、team 场景。

## Stage 3: Run Trace And Audit Log

**Status:** Planned.

**Why:**  
当前 trace 可以实时打印到 stderr，也进入 `AgentResult`，但缺少持久审计。二期需要能回答：“这次 agent 为什么执行了这个命令？谁批准的？MCP 返回了什么摘要？压缩和恢复发生过吗？”

**Design direction:**

- 每次 run/chat turn 生成 `runId`。
- 新增 `.runs/{runId}.jsonl`，默认本机私有，不提交 Git。
- 写入安全事件，不写 API key，不写 provider raw reasoning。
- 事件覆盖：
  - model call start/success/failure
  - tool call/result summary
  - permission request/decision
  - MCP call summary
  - background task notification
  - cron/team synthetic turn
  - compression/recovery
  - final answer/error

**Next:**

- 定义 `RunAuditSink`。
- CLI profile 支持 `trace.sink=stderr|file|both|none`。
- 增加 `agent runs list` / `agent runs show <runId>` 的最小只读命令。

## Stage 4: Evals And Behavioral Regression

**Status:** Planned.

**Why:**  
普通单元测试能保证代码路径，但 agent 的关键风险是行为漂移：模型是否会调用该工具？权限拒绝后是否会修正？MCP 工具是否被过度信任？team 是否重复汇报？这些需要固定 eval。

**Design direction:**

- 新增 `evals/`：

```text
evals/
  tool-calling/
  permission/
  memory/
  mcp/
  team/
  recovery/
```

- v1 eval 先不追求完整 benchmark，只做本地 deterministic fake model + scripted model。
- 对真实模型 eval 标记为 manual/live，默认跳过。
- 每条 eval 记录：
  - prompt
  - profile
  - expected trace pattern
  - expected final answer pattern
  - forbidden actions

**Next:**

- 先做 `agent-evals` Maven module 或 CLI 内部 test harness 二选一。
- 优先覆盖 MCP、permission、team permission、context compression、error recovery。
- CI 中默认跑 deterministic eval，不跑 live provider eval。

## Stage 5: Service Runtime Boundary

**Status:** Planned, not first.

**Why:**  
未来需要 UI 或外部系统调用 agent，但不能为 HTTP 重写一套 loop。服务化入口必须复用 profile、policy、trace、runtime factory。

**Design direction:**

- 新增独立模块时再评估：

```text
agent-server/
```

- v1 服务能力：
  - start session
  - send user turn
  - stream trace events
  - approve/deny permission request
  - list runs
- 不急着做前端，先把 API 边界跑通。

**Next:**

- 等 Stage 1-3 稳定后再开始。
- 先设计 SDK service interface，再选择 HTTP framework。

## Stage 6: Runtime Packaging And Distribution

**Status:** Planned.

**Why:**  
当前 jar 能本地跑，但 profile、MCP config、memory、runs、tasks、teams、scheduled tasks 都散在项目根。二期需要明确哪些是项目配置、哪些是运行态私有文件、哪些可提交。

**Design direction:**

- 项目配置：
  - `.agent/profiles/*.json`
  - `.mcp.json` 可选，是否提交由用户决定
  - `skills/`
  - `.tasks/`
- 本机私有运行态：
  - `.memory/`
  - `.runs/`
  - `.teams/`
  - `.scheduled_tasks.json`
- README 明确每个目录的 Git 策略。

**Next:**

- 更新 `.gitignore` 策略。
- 增加 `agent init` 的可能性评估：生成 profile 模板和目录说明。

## Not In Phase 2 Yet

- 不做完整 Web UI。
- 不做远程分布式 worker。
- 不做跨进程 team 恢复。
- 不做容器级 sandbox，除非 policy 体系先稳定。
- 不做 embedding/vector memory，除非 deterministic retrieval 到达明显瓶颈。

## Maintenance Rules

- 每次新增二期能力，都给对应 stage 更新 `Status / Design direction / Next`。
- 每次做架构取舍，都追加 `Decision Log`。
- 每次修改 profile schema，都同步更新 `Stage 1` 和 README。
- 每次修改权限策略或审批行为，都同步更新 `Stage 2`。
- 每次修改 trace/audit event schema，都同步更新 `Stage 3`。
- 每次新增 eval 类型，都同步更新 `Stage 4`。
- 不把本文改成流水账；它要保持平台化主线和阶段边界。
