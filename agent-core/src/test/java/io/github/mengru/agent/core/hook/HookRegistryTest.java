package io.github.mengru.agent.core.hook;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.skill.SkillDefinition;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.core.tool.todo.TodoWriteTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HookRegistryTest {

    @Test
    void runsHooksInRegistrationOrder() {
        List<String> calls = new ArrayList<>();
        HookRegistry registry = HookRegistry.empty()
                .registerHook(HookEvent.USER_PROMPT_SUBMIT, (UserPromptSubmitContext context) -> {
                    calls.add("first");
                    return HookResult.continueWith(context);
                })
                .registerHook(HookEvent.USER_PROMPT_SUBMIT, (UserPromptSubmitContext context) -> {
                    calls.add("second");
                    return HookResult.continueWith(context);
                });

        registry.triggerHooks(HookEvent.USER_PROMPT_SUBMIT, new UserPromptSubmitContext(AgentRequest.of("hello")));

        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    void replaceContextFlowsToNextHook() {
        HookRegistry registry = HookRegistry.empty()
                .registerHook(HookEvent.USER_PROMPT_SUBMIT, (UserPromptSubmitContext context) ->
                        HookResult.replace(new UserPromptSubmitContext(AgentRequest.of("changed"))))
                .registerHook(HookEvent.USER_PROMPT_SUBMIT, (UserPromptSubmitContext context) ->
                        HookResult.replace(new UserPromptSubmitContext(AgentRequest.of(context.request().task() + " again"))));

        HookResult<UserPromptSubmitContext> result = registry.triggerHooks(
                HookEvent.USER_PROMPT_SUBMIT,
                new UserPromptSubmitContext(AgentRequest.of("original"))
        );

        assertThat(result.outcome()).isEqualTo(HookResult.Outcome.CONTINUE);
        assertThat(result.context().request().task()).isEqualTo("changed again");
    }

    @Test
    void blockShortCircuitsRemainingHooks() {
        List<String> calls = new ArrayList<>();
        HookRegistry registry = HookRegistry.empty()
                .registerHook(HookEvent.USER_PROMPT_SUBMIT, (UserPromptSubmitContext context) -> {
                    calls.add("first");
                    return HookResult.block("stop", context);
                })
                .registerHook(HookEvent.USER_PROMPT_SUBMIT, (UserPromptSubmitContext context) -> {
                    calls.add("second");
                    return HookResult.continueWith(context);
                });

        HookResult<UserPromptSubmitContext> result = registry.triggerHooks(
                HookEvent.USER_PROMPT_SUBMIT,
                new UserPromptSubmitContext(AgentRequest.of("hello"))
        );

        assertThat(result.outcome()).isEqualTo(HookResult.Outcome.BLOCK);
        assertThat(result.reason()).isEqualTo("stop");
        assertThat(calls).containsExactly("first");
    }

    @Test
    void defaultsForInjectsTodoReminderWhenTodoWriteToolIsRegistered() {
        HookRegistry registry = HookRegistry.defaultsFor(
                ToolRegistry.builder().add(new TodoWriteTool()).build(),
                UserApprover.denyAll()
        );

        HookResult<UserPromptSubmitContext> first = registry.triggerHooks(
                HookEvent.USER_PROMPT_SUBMIT,
                new UserPromptSubmitContext(new AgentRequest("change files", 8, java.util.Map.of(), "base prompt"))
        );
        HookResult<UserPromptSubmitContext> second = registry.triggerHooks(HookEvent.USER_PROMPT_SUBMIT, first.context());

        assertThat(first.context().request().systemPrompt()).contains("base prompt");
        assertThat(first.context().request().systemPrompt()).contains("## todo_planning");
        assertThat(first.context().request().systemPrompt()).contains("todo_write");
        assertThat(countOccurrences(second.context().request().systemPrompt(), "## todo_planning")).isEqualTo(1);
    }

    @Test
    void defaultsForInjectsSkillCatalogBeforeTodoReminder() {
        HookRegistry registry = HookRegistry.defaultsFor(
                ToolRegistry.defaultToolsWithSkills(skillCatalog()),
                UserApprover.denyAll()
        );

        HookResult<UserPromptSubmitContext> first = registry.triggerHooks(
                HookEvent.USER_PROMPT_SUBMIT,
                new UserPromptSubmitContext(new AgentRequest("change files", 8, java.util.Map.of(), "base prompt"))
        );
        HookResult<UserPromptSubmitContext> second = registry.triggerHooks(HookEvent.USER_PROMPT_SUBMIT, first.context());

        String systemPrompt = first.context().request().systemPrompt();
        assertThat(systemPrompt).contains("base prompt");
        assertThat(systemPrompt).contains("## skill_catalog");
        assertThat(systemPrompt).contains("java-agent: Java agent guidance");
        assertThat(systemPrompt.indexOf("## skill_catalog")).isLessThan(systemPrompt.indexOf("## todo_planning"));
        assertThat(countOccurrences(second.context().request().systemPrompt(), "## skill_catalog")).isEqualTo(1);
    }

    @Test
    void defaultsForDoesNotInjectTodoReminderWhenTodoWriteToolIsMissing() {
        HookRegistry registry = HookRegistry.defaultsFor(
                ToolRegistry.builder().add(new TestTool("custom")).build(),
                UserApprover.denyAll()
        );

        HookResult<UserPromptSubmitContext> result = registry.triggerHooks(
                HookEvent.USER_PROMPT_SUBMIT,
                new UserPromptSubmitContext(new AgentRequest("change files", 8, java.util.Map.of(), "base prompt"))
        );

        assertThat(result.context().request().systemPrompt()).contains("## identity");
        assertThat(result.context().request().systemPrompt()).contains("## user_instructions");
        assertThat(result.context().request().systemPrompt()).contains("base prompt");
        assertThat(result.context().request().systemPrompt()).doesNotContain("## todo_planning");
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static SkillCatalog skillCatalog() {
        return SkillCatalog.of(List.of(new SkillDefinition(
                "java-agent",
                "Java agent guidance",
                Path.of("skills/java-agent/SKILL.md"),
                "content"
        )));
    }

    private record TestTool(String name) implements Tool {

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            return ToolResult.success("ok");
        }
    }
}
