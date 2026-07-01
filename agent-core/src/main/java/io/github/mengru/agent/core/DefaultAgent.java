package io.github.mengru.agent.core;

import io.github.mengru.agent.api.Agent;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.hook.HookEvent;
import io.github.mengru.agent.core.hook.HookRegistry;
import io.github.mengru.agent.core.hook.HookResult;
import io.github.mengru.agent.core.hook.PostToolUseContext;
import io.github.mengru.agent.core.hook.PreToolUseContext;
import io.github.mengru.agent.core.hook.PreToolUsePermissionHook;
import io.github.mengru.agent.core.hook.SkillCatalogHook;
import io.github.mengru.agent.core.hook.StopContext;
import io.github.mengru.agent.core.hook.TodoReminderHook;
import io.github.mengru.agent.core.hook.UserPromptSubmitContext;
import io.github.mengru.agent.core.permission.PermissionChecker;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.LoadSkillTool;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.core.tool.subagent.SubagentTool;
import io.github.mengru.agent.core.tool.todo.TodoWriteTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultAgent implements Agent {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAgent.class);

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;

    public DefaultAgent(ModelClient modelClient, List<Tool> tools) {
        this(modelClient, ToolRegistry.of(tools));
    }

    public DefaultAgent(ModelClient modelClient, ToolRegistry toolRegistry) {
        this(modelClient, toolRegistry, HookRegistry.defaultsFor(toolRegistry));
    }

    public DefaultAgent(ModelClient modelClient, List<Tool> tools, PermissionChecker permissionChecker, UserApprover userApprover) {
        this(modelClient, ToolRegistry.of(tools), permissionChecker, userApprover);
    }

    public DefaultAgent(ModelClient modelClient, ToolRegistry toolRegistry, PermissionChecker permissionChecker, UserApprover userApprover) {
        this(modelClient, toolRegistry, permissionHookRegistry(toolRegistry, permissionChecker, userApprover));
    }

    public DefaultAgent(ModelClient modelClient, List<Tool> tools, HookRegistry hookRegistry) {
        this(modelClient, ToolRegistry.of(tools), hookRegistry);
    }

    public DefaultAgent(ModelClient modelClient, ToolRegistry toolRegistry, HookRegistry hookRegistry) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
    }

    @Override
    public AgentResult run(AgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<AgentStep> steps = new ArrayList<>();
        AgentRequest effectiveRequest;
        try {
            HookResult<UserPromptSubmitContext> promptResult = hookRegistry.triggerHooks(
                    HookEvent.USER_PROMPT_SUBMIT,
                    new UserPromptSubmitContext(request)
            );
            if (promptResult.outcome() == HookResult.Outcome.BLOCK) {
                return finish(request, steps, new AgentResult("hook blocked UserPromptSubmit: " + promptResult.reason(), steps, false), "user-prompt-blocked");
            }
            effectiveRequest = promptResult.context().request();
        } catch (RuntimeException e) {
            return finish(request, steps, new AgentResult("UserPromptSubmit hook failed: " + e.getMessage(), steps, false), "user-prompt-error");
        }

        for (int i = 0; i < effectiveRequest.maxSteps(); i++) {
            AgentStep nextStep = modelClient.nextStep(effectiveRequest, List.copyOf(steps), toolRegistry.tools());
            steps.add(nextStep);
            LOG.debug("Agent step {}: {}", nextStep.type(), nextStep.content());

            if (nextStep.type() == AgentStep.Type.FINAL_ANSWER) {
                return finish(effectiveRequest, steps, new AgentResult(nextStep.content(), steps, true), "final-answer");
            }
            if (nextStep.type() == AgentStep.Type.ERROR) {
                return finish(effectiveRequest, steps, new AgentResult(nextStep.content(), steps, false), "model-error");
            }
            if (nextStep.type() == AgentStep.Type.TOOL_CALL) {
                AgentStep toolStep = executeTool(nextStep, effectiveRequest);
                steps.add(toolStep);
                if (toolStep.type() == AgentStep.Type.ERROR) {
                    return finish(effectiveRequest, steps, new AgentResult(toolStep.content(), steps, false), "tool-error");
                }
            }
        }

        String output = steps.isEmpty() ? "" : steps.get(steps.size() - 1).content();
        return finish(effectiveRequest, steps, new AgentResult(output, steps, false), "max-steps");
    }

    private AgentStep executeTool(AgentStep toolCall, AgentRequest request) {
        Optional<String> toolName = toolCall.toolNameOptional();
        if (toolName.isEmpty()) {
            return AgentStep.toolResult(toolCall.toolCallId(), "unknown", "Tool call did not include a tool name");
        }

        Optional<Tool> tool = toolRegistry.findByName(toolName.get());
        if (tool.isEmpty()) {
            return AgentStep.toolResult(toolCall.toolCallId(), toolName.get(), "Unknown tool: " + toolName.get());
        }

        ToolRequest toolRequest = new ToolRequest(
                tool.get().name(),
                toolCall.toolArgumentsOrEmptyObject(),
                toolMetadata(request)
        );
        PreToolUseContext preToolUseContext;
        try {
            HookResult<PreToolUseContext> preToolUseResult = hookRegistry.triggerHooks(
                    HookEvent.PRE_TOOL_USE,
                    new PreToolUseContext(request, toolCall, tool.get(), toolRequest)
            );
            if (preToolUseResult.outcome() == HookResult.Outcome.BLOCK) {
                return AgentStep.toolResult(toolCall.toolCallId(), tool.get().name(), "permission denied: " + preToolUseResult.reason());
            }
            preToolUseContext = preToolUseResult.context();
        } catch (RuntimeException e) {
            return AgentStep.error("PreToolUse hook failed for tool " + tool.get().name() + ": " + e.getMessage());
        }

        ToolResult result;
        try {
            result = preToolUseContext.tool().execute(preToolUseContext.toolRequest());
        } catch (RuntimeException e) {
            return AgentStep.error("Tool " + preToolUseContext.tool().name() + " crashed: " + e.getMessage());
        }
        try {
            HookResult<PostToolUseContext> postToolUseResult = hookRegistry.triggerHooks(
                    HookEvent.POST_TOOL_USE,
                    new PostToolUseContext(
                            preToolUseContext.request(),
                            preToolUseContext.toolCall(),
                            preToolUseContext.tool(),
                            preToolUseContext.toolRequest(),
                            result
                    )
            );
            if (postToolUseResult.outcome() == HookResult.Outcome.BLOCK) {
                result = ToolResult.failure("PostToolUse hook blocked result: " + postToolUseResult.reason());
            } else {
                result = postToolUseResult.context().toolResult();
            }
        } catch (RuntimeException e) {
            return AgentStep.error("PostToolUse hook failed for tool " + preToolUseContext.tool().name() + ": " + e.getMessage());
        }
        if (result.success()) {
            return AgentStep.toolResult(toolCall.toolCallId(), preToolUseContext.tool().name(), result.output());
        }
        return AgentStep.toolResult(toolCall.toolCallId(), preToolUseContext.tool().name(), "Tool " + preToolUseContext.tool().name() + " failed: " + result.error());
    }

    private AgentResult finish(AgentRequest request, List<AgentStep> steps, AgentResult result, String reason) {
        try {
            HookResult<StopContext> stopResult = hookRegistry.triggerHooks(
                    HookEvent.STOP,
                    new StopContext(request, steps, result, reason)
            );
            if (stopResult.outcome() == HookResult.Outcome.BLOCK) {
                return new AgentResult("Stop hook blocked result: " + stopResult.reason(), steps, false);
            }
            return stopResult.context().result();
        } catch (RuntimeException e) {
            return new AgentResult("Stop hook failed: " + e.getMessage(), steps, false);
        }
    }

    private Map<String, String> toolMetadata(AgentRequest request) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(request.metadata());
        metadata.put(SubagentTool.PARENT_SYSTEM_PROMPT_METADATA_KEY, request.systemPrompt());
        return metadata;
    }

    private static HookRegistry permissionHookRegistry(ToolRegistry toolRegistry, PermissionChecker permissionChecker, UserApprover userApprover) {
        HookRegistry registry = HookRegistry.empty();
        SkillCatalog skillCatalog = toolRegistry.findByName(LoadSkillTool.NAME)
                .filter(LoadSkillTool.class::isInstance)
                .map(LoadSkillTool.class::cast)
                .map(LoadSkillTool::skillCatalog)
                .orElseGet(SkillCatalog::empty);
        if (!skillCatalog.isEmpty()) {
            registry.registerHook(HookEvent.USER_PROMPT_SUBMIT, new SkillCatalogHook(skillCatalog));
        }
        if (toolRegistry.findByName(TodoWriteTool.NAME).isPresent()) {
            registry.registerHook(HookEvent.USER_PROMPT_SUBMIT, new TodoReminderHook());
        }
        return registry.registerHook(
                HookEvent.PRE_TOOL_USE,
                new PreToolUsePermissionHook(
                        Objects.requireNonNull(permissionChecker, "permissionChecker must not be null"),
                        Objects.requireNonNull(userApprover, "userApprover must not be null")
                )
        );
    }
}
