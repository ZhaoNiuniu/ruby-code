package io.github.mengru.agent.core;

import io.github.mengru.agent.api.Agent;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ContextCompressionEvent;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.PromptTooLongException;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.context.ContextManager;
import io.github.mengru.agent.core.hook.HookEvent;
import io.github.mengru.agent.core.hook.HookRegistry;
import io.github.mengru.agent.core.hook.HookResult;
import io.github.mengru.agent.core.hook.PostToolUseContext;
import io.github.mengru.agent.core.hook.PreToolUseContext;
import io.github.mengru.agent.core.hook.PreToolUsePermissionHook;
import io.github.mengru.agent.core.hook.StopContext;
import io.github.mengru.agent.core.hook.UserPromptSubmitContext;
import io.github.mengru.agent.core.permission.PermissionChecker;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.prompt.PromptAssembler;
import io.github.mengru.agent.core.prompt.PromptAssemblyHook;
import io.github.mengru.agent.core.prompt.PromptMode;
import io.github.mengru.agent.core.skill.LoadSkillTool;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.core.tool.subagent.SubagentTool;
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
    private final ContextManager contextManager;

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
        this(modelClient, toolRegistry, hookRegistry, ContextManager.defaults());
    }

    public DefaultAgent(ModelClient modelClient, ToolRegistry toolRegistry, HookRegistry hookRegistry, ContextManager contextManager) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager must not be null");
    }

    @Override
    public AgentResult run(AgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<AgentStep> steps = new ArrayList<>();
        List<ContextCompressionEvent> compressionEvents = new ArrayList<>();
        ContextManager runContextManager = contextManager.newRun();
        AgentRequest effectiveRequest;
        try {
            HookResult<UserPromptSubmitContext> promptResult = hookRegistry.triggerHooks(
                    HookEvent.USER_PROMPT_SUBMIT,
                    new UserPromptSubmitContext(request)
            );
            if (promptResult.outcome() == HookResult.Outcome.BLOCK) {
                return finish(request, steps, new AgentResult("hook blocked UserPromptSubmit: " + promptResult.reason(), steps, false, compressionEvents), "user-prompt-blocked");
            }
            effectiveRequest = promptResult.context().request();
        } catch (RuntimeException e) {
            return finish(request, steps, new AgentResult("UserPromptSubmit hook failed: " + e.getMessage(), steps, false, compressionEvents), "user-prompt-error");
        }

        for (int i = 0; i < effectiveRequest.maxSteps(); i++) {
            ContextManager.ContextView contextView = runContextManager.prepareForModel(
                    effectiveRequest,
                    steps,
                    modelClient,
                    toolRegistry.tools()
            );
            compressionEvents.addAll(contextView.events());
            if (contextView.failed()) {
                return finish(
                        effectiveRequest,
                        steps,
                        new AgentResult(contextView.failureMessage(), steps, false, compressionEvents),
                        "context-compression-error"
                );
            }
            effectiveRequest = contextView.request();

            AgentStep nextStep;
            try {
                nextStep = modelClient.nextStep(contextView.request(), contextView.steps(), toolRegistry.tools());
            } catch (PromptTooLongException e) {
                ContextManager.ContextView reactiveView = runContextManager.reactiveCompact(effectiveRequest, steps, e);
                compressionEvents.addAll(reactiveView.events());
                try {
                    nextStep = modelClient.nextStep(reactiveView.request(), reactiveView.steps(), toolRegistry.tools());
                } catch (PromptTooLongException retryFailure) {
                    return finish(
                            effectiveRequest,
                            steps,
                            new AgentResult("prompt too long after reactiveCompact: " + retryFailure.getMessage(), steps, false, compressionEvents),
                            "prompt-too-long"
                    );
                }
            }
            steps.add(nextStep);
            LOG.debug("Agent step {}: {}", nextStep.type(), nextStep.content());

            if (nextStep.type() == AgentStep.Type.FINAL_ANSWER) {
                return finish(effectiveRequest, steps, new AgentResult(nextStep.content(), steps, true, compressionEvents), "final-answer");
            }
            if (nextStep.type() == AgentStep.Type.ERROR) {
                return finish(effectiveRequest, steps, new AgentResult(nextStep.content(), steps, false, compressionEvents), "model-error");
            }
            if (nextStep.type() == AgentStep.Type.TOOL_CALL) {
                AgentStep toolStep = executeTool(nextStep, effectiveRequest);
                steps.add(toolStep);
                if (toolStep.type() == AgentStep.Type.ERROR) {
                    return finish(effectiveRequest, steps, new AgentResult(toolStep.content(), steps, false, compressionEvents), "tool-error");
                }
            }
        }

        String output = steps.isEmpty() ? "" : steps.get(steps.size() - 1).content();
        return finish(effectiveRequest, steps, new AgentResult(output, steps, false, compressionEvents), "max-steps");
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
                return new AgentResult("Stop hook blocked result: " + stopResult.reason(), steps, false, result.compressionEvents());
            }
            return stopResult.context().result();
        } catch (RuntimeException e) {
            return new AgentResult("Stop hook failed: " + e.getMessage(), steps, false, result.compressionEvents());
        }
    }

    private Map<String, String> toolMetadata(AgentRequest request) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(request.metadata());
        String userInstructions = "true".equals(request.metadata().get(PromptAssembler.ASSEMBLED_METADATA_KEY))
                ? request.metadata().getOrDefault(PromptAssembler.USER_INSTRUCTIONS_METADATA_KEY, "")
                : request.systemPrompt();
        metadata.put(SubagentTool.PARENT_USER_INSTRUCTIONS_METADATA_KEY, userInstructions);
        return metadata;
    }

    private static HookRegistry permissionHookRegistry(ToolRegistry toolRegistry, PermissionChecker permissionChecker, UserApprover userApprover) {
        HookRegistry registry = HookRegistry.empty();
        SkillCatalog skillCatalog = toolRegistry.findByName(LoadSkillTool.NAME)
                .filter(LoadSkillTool.class::isInstance)
                .map(LoadSkillTool.class::cast)
                .map(LoadSkillTool::skillCatalog)
                .orElseGet(SkillCatalog::empty);
        registry.registerHook(
                HookEvent.USER_PROMPT_SUBMIT,
                new PromptAssemblyHook(
                        PromptMode.MAIN,
                        toolRegistry,
                        io.github.mengru.agent.core.memory.MemoryCatalog.empty(java.nio.file.Path.of("")),
                        skillCatalog
                )
        );
        return registry.registerHook(
                HookEvent.PRE_TOOL_USE,
                new PreToolUsePermissionHook(
                        Objects.requireNonNull(permissionChecker, "permissionChecker must not be null"),
                        Objects.requireNonNull(userApprover, "userApprover must not be null")
                )
        );
    }
}
