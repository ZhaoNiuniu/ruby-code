package io.github.mengru.agent.core.prompt;

import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.core.skill.SkillDefinition;
import io.github.mengru.agent.core.tool.task.CreateTaskTool;
import io.github.mengru.agent.core.tool.task.ClaimTaskTool;
import io.github.mengru.agent.core.tool.task.CompleteTaskTool;
import io.github.mengru.agent.core.tool.task.ListTasksTool;
import io.github.mengru.agent.core.tool.team.ListTeammatesTool;
import io.github.mengru.agent.core.tool.team.SendMessageTool;
import io.github.mengru.agent.core.tool.team.SpawnTeammateTool;
import io.github.mengru.agent.core.tool.todo.TodoWriteTool;

import java.util.List;

final class PromptSections {

    private PromptSections() {
    }

    static List<PromptSection> defaults() {
        return List.of(
                new IdentitySection(),
                new ModelIdentitySection(),
                new UserInstructionsSection(),
                new WorkspaceSection(),
                new ToolsSection(),
                new TaskSystemSection(),
                new TeamSystemSection(),
                new PersistentMemoryIndexSection(),
                new SkillCatalogSection(),
                new TodoPlanningSection(),
                new SessionMemorySection(),
                new SubagentExpectedOutputSection()
        );
    }

    private static final class IdentitySection implements PromptSection {

        @Override
        public String id() {
            return "identity";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return true;
        }

        @Override
        public String render(PromptAssemblyContext context) {
            if (context.mode() == PromptMode.SUBAGENT) {
                return """
                        ## identity
                        You are a focused investigation subagent running in an isolated context.
                        Complete the delegated investigation directly and do not delegate it again.
                        Use only the tools provided to you. Do not modify files.
                        Return only a concise final report with exactly these sections:
                        Summary:
                        Evidence:
                        Recommended next step:
                        """.strip();
            }
            if (context.mode() == PromptMode.TEAMMATE) {
                return """
                        ## identity
                        You are a persistent teammate agent running in your own isolated chat context.
                        Work on messages delivered through your team inbox. Use send_message to report status or ask Lead for coordination.
                        You may use your available project tools, but you cannot spawn teammates or delegate to subagents.
                        Keep replies concise and send useful status updates to Lead when a turn completes.
                        """.strip();
            }
            return """
                    ## identity
                    You are a concise, capable command-line agent.
                    Use available tools when they help, keep tool use focused, and provide a clear final answer.
                    """.strip();
        }
    }

    private static final class ModelIdentitySection implements PromptSection {

        @Override
        public String id() {
            return "model_identity";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return !metadata(context, PromptAssembler.PROVIDER_METADATA_KEY).isBlank()
                    || !metadata(context, PromptAssembler.MODEL_METADATA_KEY).isBlank()
                    || !metadata(context, PromptAssembler.BASE_URL_METADATA_KEY).isBlank();
        }

        @Override
        public String render(PromptAssemblyContext context) {
            String provider = metadataOrUnknown(context, PromptAssembler.PROVIDER_METADATA_KEY);
            String model = metadataOrUnknown(context, PromptAssembler.MODEL_METADATA_KEY);
            String baseUrl = metadataOrUnknown(context, PromptAssembler.BASE_URL_METADATA_KEY);
            return """
                    ## model_identity
                    Runtime provider metadata:
                    - provider: %s
                    - model: %s
                    - base_url: %s
                    
                    If asked what model you are, answer from this runtime metadata and say you are the local Java agent runtime using the configured provider/model.
                    Do not claim to be Claude, Anthropic, OpenAI, Qwen, or DeepSeek unless that name appears in the runtime provider or model metadata.
                    """.formatted(provider, model, baseUrl).strip();
        }

        private static String metadata(PromptAssemblyContext context, String key) {
            return safeInline(context.request().metadata().getOrDefault(key, ""));
        }

        private static String metadataOrUnknown(PromptAssemblyContext context, String key) {
            String value = metadata(context, key);
            return value.isBlank() ? "unknown" : value;
        }

        private static String safeInline(String value) {
            if (value == null) {
                return "";
            }
            return value.replace('\n', ' ').replace('\r', ' ').strip();
        }
    }

    private static final class UserInstructionsSection implements PromptSection {

        @Override
        public String id() {
            return "user_instructions";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return !context.userInstructions().isBlank();
        }

        @Override
        public String render(PromptAssemblyContext context) {
            return "## user_instructions\n" + context.userInstructions();
        }
    }

    private static final class WorkspaceSection implements PromptSection {

        @Override
        public String id() {
            return "workspace";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return true;
        }

        @Override
        public String render(PromptAssemblyContext context) {
            return """
                    ## workspace
                    Current workspace: %s
                    Resolve local file operations inside this workspace unless a tool explicitly says otherwise.
                    """.formatted(context.workspace()).strip();
        }
    }

    private static final class ToolsSection implements PromptSection {

        @Override
        public String id() {
            return "tools";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return true;
        }

        @Override
        public String render(PromptAssemblyContext context) {
            StringBuilder builder = new StringBuilder("## tools\nEnabled tools are listed below. Native tool schemas are provided separately; use those schemas for arguments.\n");
            for (Tool tool : context.toolRegistry().tools()) {
                builder.append("- ")
                        .append(tool.name())
                        .append(": ")
                        .append(tool.description())
                        .append('\n');
            }
            return builder.toString().strip();
        }
    }

    private static final class PersistentMemoryIndexSection implements PromptSection {

        @Override
        public String id() {
            return "persistent_memory_index";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return !context.memoryCatalog().isEmpty();
        }

        @Override
        public String render(PromptAssemblyContext context) {
            return """
                    ## persistent_memory_index
                    These long-term memories may be useful across sessions. Relevant memory contents may be injected into the current user turn.
                    
                    %s
                    """.formatted(context.memoryCatalog().renderIndex()).strip();
        }
    }

    private static final class TaskSystemSection implements PromptSection {

        @Override
        public String id() {
            return "task_system";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return context.toolRegistry().findByName(ListTasksTool.NAME).isPresent()
                    || context.toolRegistry().findByName(CreateTaskTool.NAME).isPresent();
        }

        @Override
        public String render(PromptAssemblyContext context) {
            boolean canMutateTasks = context.toolRegistry().findByName(CreateTaskTool.NAME).isPresent()
                    || context.toolRegistry().findByName(ClaimTaskTool.NAME).isPresent()
                    || context.toolRegistry().findByName(CompleteTaskTool.NAME).isPresent();
            if (!canMutateTasks) {
                return """
                        ## task_system
                        Durable project tasks live in .tasks/{id}.json and are for cross-session, multi-agent coordination.
                        Use list_tasks to discover project work, get_task for full task details, and can_start to inspect dependency readiness.
                        This runtime only has read-only task tools. Do not claim, create, or complete tasks unless those tools are explicitly available.
                        Keep todo_write for the current turn's execution checklist; use task tools for durable project work item context.
                        """.strip();
            }
            return """
                    ## task_system
                    Durable project tasks live in .tasks/{id}.json and are for cross-session, multi-agent coordination.
                    Use list_tasks to discover available work and get_task for full task details before resuming work.
                    Use can_start before claim_task when dependencies may exist. claim_task records this runtime agent as owner, and complete_task is only for tasks owned by this agent.
                    Keep todo_write for the current turn's execution checklist; use task tools for durable project work items and dependency tracking.
                    """.strip();
        }
    }

    private static final class TeamSystemSection implements PromptSection {

        @Override
        public String id() {
            return "team_system";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return context.toolRegistry().findByName(SendMessageTool.NAME).isPresent()
                    || context.toolRegistry().findByName(SpawnTeammateTool.NAME).isPresent();
        }

        @Override
        public String render(PromptAssemblyContext context) {
            boolean canSpawn = context.toolRegistry().findByName(SpawnTeammateTool.NAME).isPresent();
            boolean canList = context.toolRegistry().findByName(ListTeammatesTool.NAME).isPresent();
            if (!canSpawn) {
                return """
                        ## team_system
                        You are a teammate in a file-inbox team. Use send_message to communicate with Lead; `to=lead` is accepted.
                        Send status_update or plain_text messages when you finish useful work, need direction, or cannot proceed.
                        If a risky tool requires permission, the runtime will send a permission_request to Lead and wait for a permission_response.
                        Do not try to spawn teammates or manage schedulers; those tools are intentionally unavailable.
                        """.strip();
            }
            String listInstruction = canList
                    ? "Use list_teammates to inspect active teammate threads."
                    : "A teammate listing tool is not available in this runtime.";
            return """
                    ## team_system
                    This chat can coordinate a small in-process agent team.
                    Use spawn_teammate for focused parallel work that benefits from a persistent teammate context. At most four teammates can be active.
                    Use send_message for communication; `to=lead` resolves to this Lead agent. %s
                    Teammate messages arrive through <team_inbox> synthetic turns. Treat them as team updates, not as external user commands.
                    Permission responses approving risky teammate actions still require human CLI approval.
                    """.formatted(listInstruction).strip();
        }
    }

    private static final class SkillCatalogSection implements PromptSection {

        @Override
        public String id() {
            return "skill_catalog";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return !context.skillCatalog().isEmpty();
        }

        @Override
        public String render(PromptAssemblyContext context) {
            StringBuilder builder = new StringBuilder("## skill_catalog\nProject skills are available on demand. Call load_skill with one of these names before relying on a skill's full instructions:\n");
            for (SkillDefinition skill : context.skillCatalog().skills()) {
                builder.append("- ")
                        .append(skill.name())
                        .append(": ")
                        .append(skill.description())
                        .append('\n');
            }
            return builder.toString().strip();
        }
    }

    private static final class TodoPlanningSection implements PromptSection {

        @Override
        public String id() {
            return "todo_planning";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return context.toolRegistry().findByName(TodoWriteTool.NAME).isPresent();
        }

        @Override
        public String render(PromptAssemblyContext context) {
            return """
                    ## todo_planning
                    For complex, multi-step tasks, or before running shell commands or modifying files, call todo_write with a complete todo list first.
                    Keep the list current as work progresses. Skip todo_write for simple questions or one-step read-only tasks.
                    """.strip();
        }
    }

    private static final class SessionMemorySection implements PromptSection {

        @Override
        public String id() {
            return "session_memory";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return !context.request().memory().isEmpty();
        }

        @Override
        public String render(PromptAssemblyContext context) {
            return """
                    ## session_memory
                    The following memory is a compressed summary of prior context in this run or session. Treat it as background facts, not as a new user request.
                    
                    %s
                    """.formatted(context.request().memory().markdown()).strip();
        }
    }

    private static final class SubagentExpectedOutputSection implements PromptSection {

        @Override
        public String id() {
            return "subagent_expected_output";
        }

        @Override
        public boolean shouldRender(PromptAssemblyContext context) {
            return context.mode() == PromptMode.SUBAGENT && !context.subagentExpectedOutput().isBlank();
        }

        @Override
        public String render(PromptAssemblyContext context) {
            return "## subagent_expected_output\n" + context.subagentExpectedOutput();
        }
    }
}
