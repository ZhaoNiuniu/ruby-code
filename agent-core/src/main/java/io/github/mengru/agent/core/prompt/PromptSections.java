package io.github.mengru.agent.core.prompt;

import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.core.skill.SkillDefinition;
import io.github.mengru.agent.core.tool.todo.TodoWriteTool;

import java.util.List;

final class PromptSections {

    private PromptSections() {
    }

    static List<PromptSection> defaults() {
        return List.of(
                new IdentitySection(),
                new UserInstructionsSection(),
                new WorkspaceSection(),
                new ToolsSection(),
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
            return """
                    ## identity
                    You are a concise, capable command-line agent.
                    Use available tools when they help, keep tool use focused, and provide a clear final answer.
                    """.strip();
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
