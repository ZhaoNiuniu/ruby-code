package io.github.mengru.agent.core;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ContextCompressionEvent;
import io.github.mengru.agent.api.PromptTooLongException;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.core.hook.HookEvent;
import io.github.mengru.agent.core.hook.HookRegistry;
import io.github.mengru.agent.core.hook.HookResult;
import io.github.mengru.agent.core.hook.PostToolUseContext;
import io.github.mengru.agent.core.hook.PreToolUseContext;
import io.github.mengru.agent.core.hook.StopContext;
import io.github.mengru.agent.core.hook.UserPromptSubmitContext;
import io.github.mengru.agent.core.permission.DefaultPermissionChecker;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.context.ContextManager;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.skill.SkillDefinition;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.core.tool.subagent.SubagentTool;
import io.github.mengru.agent.core.tool.todo.TodoWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentTest {

    @TempDir
    Path workspace;

    @Test
    void runsEchoToolLoop() {
        DefaultAgent agent = new DefaultAgent(new EchoModelClient(), List.of(new EchoTool()));

        AgentResult result = agent.run(AgentRequest.of("hello agent"));

        assertThat(result.completed()).isTrue();
        assertThat(result.output()).isEqualTo("Echo agent completed: echo: hello agent");
        assertThat(result.steps())
                .extracting(AgentStep::type)
                .containsExactly(
                        AgentStep.Type.TOOL_CALL,
                        AgentStep.Type.TOOL_RESULT,
                        AgentStep.Type.FINAL_ANSWER
                );
        assertThat(result.steps().get(1).toolName()).isEqualTo("echo");
    }

    @Test
    void runsEchoToolLoopWithRegistryConstructor() {
        DefaultAgent agent = new DefaultAgent(new EchoModelClient(), ToolRegistry.defaultTools());

        AgentResult result = agent.run(AgentRequest.of("hello registry"));

        assertThat(result.completed()).isTrue();
        assertThat(result.output()).isEqualTo("Echo agent completed: hello registry");
    }

    @Test
    void returnsIncompleteResultWhenMaxStepsIsReached() {
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> AgentStep.thought("still thinking"), List.of());

        AgentResult result = agent.run(new AgentRequest("slow task", 2, java.util.Map.of()));

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).hasSize(2);
        assertThat(result.output()).isEqualTo("still thinking");
    }

    @Test
    void reactiveCompactRetriesOnceWhenPromptIsTooLong() {
        AtomicInteger calls = new AtomicInteger();
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (calls.getAndIncrement() == 0) {
                throw new PromptTooLongException("too long");
            }
            return AgentStep.finalAnswer("recovered");
        }, ToolRegistry.of(List.of()), HookRegistry.empty(), ContextManager.defaults());

        AgentResult result = agent.run(AgentRequest.of("large task"));

        assertThat(result.completed()).isTrue();
        assertThat(result.output()).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.compressionEvents())
                .extracting(ContextCompressionEvent::stage)
                .contains(ContextCompressionEvent.Stage.REACTIVE_COMPACT);
    }

    @Test
    void passesStructuredToolArgumentsAndPropagatesToolCallId() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("value", "abc");
        Tool uppercase = new Tool() {
            @Override
            public String name() {
                return "uppercase";
            }

            @Override
            public String description() {
                return "Uppercases a value.";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success(request.stringArgument("value").toUpperCase());
            }
        };
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-1", "uppercase", arguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of(uppercase));

        AgentResult result = agent.run(AgentRequest.of("uppercase"));

        assertThat(result.completed()).isTrue();
        assertThat(result.output()).isEqualTo("ABC");
        assertThat(result.steps().get(1).toolCallId()).isEqualTo("call-1");
    }

    @Test
    void recordsUnknownToolAsToolResultStep() {
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-1", "missing", JsonNodeFactory.instance.objectNode());
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of());

        AgentResult result = agent.run(new AgentRequest("call missing", 2, java.util.Map.of()));

        assertThat(result.completed()).isTrue();
        assertThat(result.steps())
                .extracting(AgentStep::type)
                .containsExactly(AgentStep.Type.TOOL_CALL, AgentStep.Type.TOOL_RESULT, AgentStep.Type.FINAL_ANSWER);
        assertThat(result.steps().get(1).toolCallId()).isEqualTo("call-1");
        assertThat(result.steps().get(1).toolName()).isEqualTo("missing");
        assertThat(result.output()).contains("Unknown tool: missing");
    }

    @Test
    void recordsToolFailureAsToolResultStep() {
        Tool failing = new Tool() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public String description() {
                return "Always fails.";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.failure("not today");
            }
        };
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-2", "failing", JsonNodeFactory.instance.objectNode());
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of(failing));

        AgentResult result = agent.run(new AgentRequest("fail once", 2, java.util.Map.of()));

        assertThat(result.completed()).isTrue();
        assertThat(result.steps())
                .extracting(AgentStep::type)
                .containsExactly(AgentStep.Type.TOOL_CALL, AgentStep.Type.TOOL_RESULT, AgentStep.Type.FINAL_ANSWER);
        assertThat(result.steps().get(1).toolCallId()).isEqualTo("call-2");
        assertThat(result.output()).contains("Tool failing failed: not today");
    }

    @Test
    void permissionDenialIsReturnedAsToolResultWithoutExecutingTool() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Tool writeFile = testTool("write_file", request -> {
            executed.set(true);
            return ToolResult.success("should not run");
        });
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", "blocked.txt");
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-permission", "write_file", arguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of(writeFile), new DefaultPermissionChecker(workspace), UserApprover.denyAll());

        AgentResult result = agent.run(new AgentRequest("write", 2, java.util.Map.of()));

        assertThat(executed).isFalse();
        assertThat(result.completed()).isTrue();
        assertThat(result.steps())
                .extracting(AgentStep::type)
                .containsExactly(AgentStep.Type.TOOL_CALL, AgentStep.Type.TOOL_RESULT, AgentStep.Type.FINAL_ANSWER);
        assertThat(result.steps().get(1).toolCallId()).isEqualTo("call-permission");
        assertThat(result.steps().get(1).toolName()).isEqualTo("write_file");
        assertThat(result.output()).contains("permission denied");
    }

    @Test
    void defaultConstructorStillEnablesPermissionHook() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Tool writeFile = testTool("write_file", request -> {
            executed.set(true);
            return ToolResult.success("should not run");
        });
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", "target/default-permission-hook.txt");
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-default-hook", "write_file", arguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of(writeFile));

        AgentResult result = agent.run(new AgentRequest("write", 2, java.util.Map.of()));

        assertThat(executed).isFalse();
        assertThat(result.output()).contains("permission denied");
    }

    @Test
    void defaultConstructorInjectsTodoReminderWhenTodoWriteToolIsRegistered() {
        AtomicReference<String> seenSystemPrompt = new AtomicReference<>();
        ToolRegistry registry = ToolRegistry.builder()
                .add(new TodoWriteTool())
                .build();
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            seenSystemPrompt.set(request.systemPrompt());
            return AgentStep.finalAnswer("ok");
        }, registry);

        AgentResult result = agent.run(AgentRequest.of("change files"));

        assertThat(result.completed()).isTrue();
        assertThat(seenSystemPrompt.get()).contains("## identity");
        assertThat(seenSystemPrompt.get()).contains("## todo_planning");
        assertThat(seenSystemPrompt.get()).contains("todo_write");
    }

    @Test
    void defaultConstructorInjectsSkillCatalogWhenLoadSkillToolIsRegistered() {
        AtomicReference<String> seenSystemPrompt = new AtomicReference<>();
        ToolRegistry registry = ToolRegistry.defaultToolsWithSkills(SkillCatalog.of(List.of(new SkillDefinition(
                "java-agent",
                "Java agent guidance",
                Path.of("skills/java-agent/SKILL.md"),
                "content"
        ))));
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            seenSystemPrompt.set(request.systemPrompt());
            return AgentStep.finalAnswer("ok");
        }, registry);

        AgentResult result = agent.run(AgentRequest.of("change files"));

        assertThat(result.completed()).isTrue();
        assertThat(seenSystemPrompt.get()).contains("## skill_catalog");
        assertThat(seenSystemPrompt.get()).contains("java-agent: Java agent guidance");
        assertThat(seenSystemPrompt.get().indexOf("## skill_catalog")).isLessThan(seenSystemPrompt.get().indexOf("## todo_planning"));
    }

    @Test
    void todoWriteToolCallProducesToolResultAndLoopContinues() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.putArray("todos")
                .addObject()
                .put("content", "plan work")
                .put("status", "in_progress");
        ToolRegistry registry = ToolRegistry.builder()
                .add(new TodoWriteTool())
                .build();
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-todo", TodoWriteTool.NAME, arguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, registry);

        AgentResult result = agent.run(new AgentRequest("plan", 2, java.util.Map.of()));

        assertThat(result.completed()).isTrue();
        assertThat(result.steps())
                .extracting(AgentStep::type)
                .containsExactly(AgentStep.Type.TOOL_CALL, AgentStep.Type.TOOL_RESULT, AgentStep.Type.FINAL_ANSWER);
        assertThat(result.steps().get(1).toolName()).isEqualTo(TodoWriteTool.NAME);
        assertThat(result.output()).contains("todo list updated");
    }

    @Test
    void toolMetadataCarriesUserInstructionsAndOverridesUserMetadata() {
        AtomicReference<String> seenParentPrompt = new AtomicReference<>();
        Tool custom = testTool("custom", request -> {
            seenParentPrompt.set(request.metadata().get(SubagentTool.PARENT_USER_INSTRUCTIONS_METADATA_KEY));
            return ToolResult.success("ok");
        });
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-custom", "custom", JsonNodeFactory.instance.objectNode());
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of(custom), HookRegistry.empty());

        AgentResult result = agent.run(new AgentRequest(
                "metadata",
                2,
                java.util.Map.of(SubagentTool.PARENT_USER_INSTRUCTIONS_METADATA_KEY, "spoofed"),
                "real user instructions"
        ));

        assertThat(result.completed()).isTrue();
        assertThat(seenParentPrompt.get()).isEqualTo("real user instructions");
    }

    @Test
    void userApprovalAllowsSoftRiskToolExecution() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Tool writeFile = testTool("write_file", request -> {
            executed.set(true);
            return ToolResult.success("approved");
        });
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", "approved.txt");
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-approved", "write_file", arguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of(writeFile), new DefaultPermissionChecker(workspace), UserApprover.allowAll());

        AgentResult result = agent.run(new AgentRequest("write", 2, java.util.Map.of()));

        assertThat(executed).isTrue();
        assertThat(result.output()).isEqualTo("approved");
    }

    @Test
    void hardDenyHappensBeforeUserApproval() {
        AtomicBoolean asked = new AtomicBoolean(false);
        AtomicBoolean executed = new AtomicBoolean(false);
        Tool writeFile = testTool("write_file", request -> {
            executed.set(true);
            return ToolResult.success("should not run");
        });
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", "../outside.txt");
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-hard-deny", "write_file", arguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of(writeFile), new DefaultPermissionChecker(workspace), (permissionRequest, decision) -> {
            asked.set(true);
            return true;
        });

        AgentResult result = agent.run(new AgentRequest("write outside", 2, java.util.Map.of()));

        assertThat(asked).isFalse();
        assertThat(executed).isFalse();
        assertThat(result.output()).contains("permission denied");
        assertThat(result.output()).contains("escapes workspace");
    }

    @Test
    void permissionCheckerExceptionProducesErrorStep() {
        Tool readFile = testTool("read_file", request -> ToolResult.success("should not run"));
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", "README.md");
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) ->
                AgentStep.toolCall("call-error", "read_file", arguments), List.of(readFile),
                request -> {
                    throw new IllegalStateException("policy unavailable");
                },
                UserApprover.allowAll()
        );

        AgentResult result = agent.run(AgentRequest.of("read"));

        assertThat(result.completed()).isFalse();
        assertThat(result.steps())
                .extracting(AgentStep::type)
                .containsExactly(AgentStep.Type.TOOL_CALL, AgentStep.Type.ERROR);
        assertThat(result.output()).contains("PreToolUse hook failed");
    }

    @Test
    void userPromptSubmitHookCanReplaceRequest() {
        HookRegistry registry = HookRegistry.empty()
                .registerHook(HookEvent.USER_PROMPT_SUBMIT, (UserPromptSubmitContext context) ->
                        HookResult.replace(new UserPromptSubmitContext(AgentRequest.of("changed task"))));
        DefaultAgent agent = new DefaultAgent(new EchoModelClient(), List.of(), registry);

        AgentResult result = agent.run(AgentRequest.of("original task"));

        assertThat(result.completed()).isTrue();
        assertThat(result.output()).isEqualTo("Echo agent completed: changed task");
    }

    @Test
    void preToolUseHookCanBlockToolExecution() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Tool custom = testTool("custom", request -> {
            executed.set(true);
            return ToolResult.success("should not run");
        });
        HookRegistry registry = HookRegistry.empty()
                .registerHook(HookEvent.PRE_TOOL_USE, (PreToolUseContext context) ->
                        HookResult.block("blocked by hook", context));
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-blocked", "custom", JsonNodeFactory.instance.objectNode());
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of(custom), registry);

        AgentResult result = agent.run(new AgentRequest("blocked", 2, java.util.Map.of()));

        assertThat(executed).isFalse();
        assertThat(result.completed()).isTrue();
        assertThat(result.output()).contains("permission denied: blocked by hook");
    }

    @Test
    void postToolUseHookCanReplaceToolResult() {
        Tool custom = testTool("custom", request -> ToolResult.success("original"));
        HookRegistry registry = HookRegistry.empty()
                .registerHook(HookEvent.POST_TOOL_USE, (PostToolUseContext context) ->
                        HookResult.replace(new PostToolUseContext(
                                context.request(),
                                context.toolCall(),
                                context.tool(),
                                context.toolRequest(),
                                ToolResult.success("replaced")
                        )));
        DefaultAgent agent = new DefaultAgent((request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-post", "custom", JsonNodeFactory.instance.objectNode());
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        }, List.of(custom), registry);

        AgentResult result = agent.run(new AgentRequest("post", 2, java.util.Map.of()));

        assertThat(result.output()).isEqualTo("replaced");
    }

    @Test
    void stopHookRunsBeforeAllReturnPaths() {
        List<String> reasons = new ArrayList<>();
        HookRegistry registry = HookRegistry.empty()
                .registerHook(HookEvent.STOP, (StopContext context) -> {
                    reasons.add(context.reason());
                    return HookResult.continueWith(context);
                });

        new DefaultAgent(new EchoModelClient(), List.of(), registry).run(AgentRequest.of("done"));
        new DefaultAgent((request, previousSteps, tools) -> AgentStep.error("model failed"), List.of(), registry)
                .run(AgentRequest.of("error"));
        new DefaultAgent((request, previousSteps, tools) -> AgentStep.thought("still thinking"), List.of(), registry)
                .run(new AgentRequest("slow", 1, java.util.Map.of()));

        assertThat(reasons).containsExactly("final-answer", "model-error", "max-steps");
    }

    @Test
    void stopHookCanReplaceFinalResult() {
        HookRegistry registry = HookRegistry.empty()
                .registerHook(HookEvent.STOP, (StopContext context) ->
                        HookResult.replace(new StopContext(
                                context.request(),
                                context.steps(),
                                new AgentResult("replaced final", context.steps(), true),
                                context.reason()
                        )));
        DefaultAgent agent = new DefaultAgent(new EchoModelClient(), List.of(), registry);

        AgentResult result = agent.run(AgentRequest.of("done"));

        assertThat(result.output()).isEqualTo("replaced final");
    }

    private static Tool testTool(String name, java.util.function.Function<ToolRequest, ToolResult> executor) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return executor.apply(request);
            }
        };
    }
}
