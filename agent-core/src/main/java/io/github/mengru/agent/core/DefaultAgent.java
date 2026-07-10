package io.github.mengru.agent.core;

import io.github.mengru.agent.api.Agent;
import io.github.mengru.agent.api.AuditEvent;
import io.github.mengru.agent.api.AgentRecoveryEvent;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.BackgroundTaskNotification;
import io.github.mengru.agent.api.ContextCompressionEvent;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.ModelErrorCode;
import io.github.mengru.agent.api.ModelException;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.api.TraceEvent;
import io.github.mengru.agent.core.background.BackgroundTaskManager;
import io.github.mengru.agent.core.audit.RunAuditSink;
import io.github.mengru.agent.core.audit.RunAuditSummary;
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
import io.github.mengru.agent.core.recovery.ErrorRecoveryConfig;
import io.github.mengru.agent.core.recovery.ModelCallRecovery;
import io.github.mengru.agent.core.skill.LoadSkillTool;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.core.tool.local.BashTool;
import io.github.mengru.agent.core.tool.subagent.SubagentTool;
import io.github.mengru.agent.core.trace.TraceSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultAgent implements Agent {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAgent.class);

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final ContextManager contextManager;
    private final ModelCallRecovery modelCallRecovery;
    private final BackgroundTaskManager backgroundTaskManager;
    private final TraceSink traceSink;
    private final RunAuditSink runAuditSink;
    private final ThreadLocal<String> activeRunId = new ThreadLocal<>();

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
        this(modelClient, toolRegistry, hookRegistry, contextManager, ModelCallRecovery.defaults());
    }

    public DefaultAgent(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            ErrorRecoveryConfig errorRecoveryConfig
    ) {
        this(modelClient, toolRegistry, hookRegistry, contextManager, new ModelCallRecovery(errorRecoveryConfig));
    }

    public DefaultAgent(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            ModelCallRecovery modelCallRecovery
    ) {
        this(modelClient, toolRegistry, hookRegistry, contextManager, modelCallRecovery, BackgroundTaskManager.disabled());
    }

    public DefaultAgent(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            ErrorRecoveryConfig errorRecoveryConfig,
            BackgroundTaskManager backgroundTaskManager
    ) {
        this(modelClient, toolRegistry, hookRegistry, contextManager, new ModelCallRecovery(errorRecoveryConfig), backgroundTaskManager);
    }

    public DefaultAgent(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            ModelCallRecovery modelCallRecovery,
            BackgroundTaskManager backgroundTaskManager
    ) {
        this(modelClient, toolRegistry, hookRegistry, contextManager, modelCallRecovery, backgroundTaskManager, TraceSink.noop());
    }

    public DefaultAgent(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            ErrorRecoveryConfig errorRecoveryConfig,
            BackgroundTaskManager backgroundTaskManager,
            TraceSink traceSink
    ) {
        this(modelClient, toolRegistry, hookRegistry, contextManager, new ModelCallRecovery(errorRecoveryConfig), backgroundTaskManager, traceSink);
    }

    public DefaultAgent(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            ErrorRecoveryConfig errorRecoveryConfig,
            BackgroundTaskManager backgroundTaskManager,
            TraceSink traceSink,
            RunAuditSink runAuditSink
    ) {
        this(modelClient, toolRegistry, hookRegistry, contextManager, new ModelCallRecovery(errorRecoveryConfig), backgroundTaskManager, traceSink, runAuditSink);
    }

    public DefaultAgent(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            ModelCallRecovery modelCallRecovery,
            BackgroundTaskManager backgroundTaskManager,
            TraceSink traceSink
    ) {
        this(modelClient, toolRegistry, hookRegistry, contextManager, modelCallRecovery, backgroundTaskManager, traceSink, RunAuditSink.noop());
    }

    public DefaultAgent(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            ModelCallRecovery modelCallRecovery,
            BackgroundTaskManager backgroundTaskManager,
            TraceSink traceSink,
            RunAuditSink runAuditSink
    ) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager must not be null");
        this.modelCallRecovery = Objects.requireNonNull(modelCallRecovery, "modelCallRecovery must not be null");
        this.backgroundTaskManager = Objects.requireNonNull(backgroundTaskManager, "backgroundTaskManager must not be null");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink must not be null");
        this.runAuditSink = Objects.requireNonNull(runAuditSink, "runAuditSink must not be null");
    }

    @Override
    public AgentResult run(AgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        RunAuditRun auditRun = RunAuditRun.from(request, runAuditSink);
        activeRunId.set(auditRun.runId());
        try {
            return runWithAudit(request, auditRun);
        } finally {
            activeRunId.remove();
        }
    }

    private AgentResult runWithAudit(AgentRequest request, RunAuditRun auditRun) {
        List<AgentStep> steps = new ArrayList<>();
        List<ContextCompressionEvent> compressionEvents = new ArrayList<>();
        List<AgentRecoveryEvent> recoveryEvents = new ArrayList<>();
        List<TraceEvent> traceEvents = new ArrayList<>();
        ContextManager runContextManager = contextManager.newRun();
        AgentRequest effectiveRequest;
        try {
            HookResult<UserPromptSubmitContext> promptResult = hookRegistry.triggerHooks(
                    HookEvent.USER_PROMPT_SUBMIT,
                    new UserPromptSubmitContext(request)
            );
            if (promptResult.outcome() == HookResult.Outcome.BLOCK) {
                emitTrace(traceEvents, errorTrace("user-prompt-blocked", promptResult.reason()));
                return finish(request, steps, new AgentResult("hook blocked UserPromptSubmit: " + promptResult.reason(), steps, false, compressionEvents, recoveryEvents, traceEvents), "user-prompt-blocked", auditRun);
            }
            effectiveRequest = promptResult.context().request();
        } catch (RuntimeException e) {
            emitTrace(traceEvents, errorTrace("user-prompt-error", e.getMessage()));
            return finish(request, steps, new AgentResult("UserPromptSubmit hook failed: " + e.getMessage(), steps, false, compressionEvents, recoveryEvents, traceEvents), "user-prompt-error", auditRun);
        }
        appendNotifications(steps, effectiveRequest.notifications(), traceEvents);

        for (int i = 0; i < effectiveRequest.maxSteps(); i++) {
            appendNotifications(steps, backgroundTaskManager.collectCompletedNotifications(), traceEvents);
            ContextManager.ContextView contextView = runContextManager.prepareForModel(
                    effectiveRequest,
                    steps,
                    modelClient,
                    toolRegistry.tools()
            );
            compressionEvents.addAll(contextView.events());
            emitCompressionTraces(traceEvents, contextView.events());
            if (contextView.failed()) {
                emitTrace(traceEvents, errorTrace("context-compression-error", contextView.failureMessage()));
                return finish(
                        effectiveRequest,
                        steps,
                        new AgentResult(contextView.failureMessage(), steps, false, compressionEvents, recoveryEvents, traceEvents),
                        "context-compression-error",
                        auditRun
                );
            }
            effectiveRequest = contextView.request();

            int recoveryEventStart = recoveryEvents.size();
            ModelCallResult modelCallResult = callModelWithRecovery(
                    effectiveRequest,
                    contextView.steps(),
                    steps,
                    runContextManager,
                    compressionEvents,
                    recoveryEvents,
                    traceEvents
            );
            emitRecoveryTraces(traceEvents, recoveryEvents.subList(recoveryEventStart, recoveryEvents.size()));
            if (modelCallResult.failed()) {
                emitTrace(traceEvents, errorTrace(modelCallResult.reason(), modelCallResult.failureMessage()));
                return finish(
                        effectiveRequest,
                        steps,
                        new AgentResult(modelCallResult.failureMessage(), steps, false, compressionEvents, recoveryEvents, traceEvents),
                        modelCallResult.reason(),
                        auditRun
                );
            }
            AgentStep nextStep = modelCallResult.step();
            effectiveRequest = modelCallResult.request();
            steps.add(nextStep);
            LOG.debug("Agent step {}: {}", nextStep.type(), nextStep.content());

            if (nextStep.type() == AgentStep.Type.FINAL_ANSWER) {
                emitTrace(traceEvents, finalAnswerTrace(true));
                return finish(effectiveRequest, steps, new AgentResult(nextStep.content(), steps, true, compressionEvents, recoveryEvents, traceEvents), "final-answer", auditRun);
            }
            if (nextStep.type() == AgentStep.Type.ERROR) {
                emitTrace(traceEvents, errorTrace("model-error", nextStep.content()));
                return finish(effectiveRequest, steps, new AgentResult(nextStep.content(), steps, false, compressionEvents, recoveryEvents, traceEvents), "model-error", auditRun);
            }
            if (nextStep.type() == AgentStep.Type.TOOL_CALL) {
                emitTrace(traceEvents, toolCallTrace(nextStep));
                AgentStep toolStep = executeTool(nextStep, effectiveRequest);
                steps.add(toolStep);
                emitTrace(traceEvents, toolResultTrace(toolStep));
                if (toolStep.type() == AgentStep.Type.ERROR) {
                    return finish(effectiveRequest, steps, new AgentResult(toolStep.content(), steps, false, compressionEvents, recoveryEvents, traceEvents), "tool-error", auditRun);
                }
            }
        }

        String output = steps.isEmpty() ? "" : steps.get(steps.size() - 1).content();
        emitTrace(traceEvents, errorTrace("max-steps", output));
        return finish(effectiveRequest, steps, new AgentResult(output, steps, false, compressionEvents, recoveryEvents, traceEvents), "max-steps", auditRun);
    }

    private ModelCallResult callModelWithRecovery(
            AgentRequest request,
            List<AgentStep> contextSteps,
            List<AgentStep> originalSteps,
            ContextManager runContextManager,
            List<ContextCompressionEvent> compressionEvents,
            List<AgentRecoveryEvent> recoveryEvents,
            List<TraceEvent> traceEvents
    ) {
        try {
            emitTrace(traceEvents, modelCallTrace("start", "main", ""));
            AgentStep step = modelClient.nextStep(request, contextSteps, toolRegistry.tools());
            emitTrace(traceEvents, modelCallTrace("success", "main", ""));
            return ModelCallResult.ok(step, request);
        } catch (ModelException e) {
            emitTrace(traceEvents, modelCallTrace("failed", "main", e.code().name()));
            if (!modelCallRecovery.enabled()) {
                recoveryEvents.add(modelCallRecovery.event(
                        e.code(),
                        "recovery_disabled",
                        0,
                        false,
                        request.modelOptions().maxOutputTokens(),
                        request.modelOptions().maxOutputTokens(),
                        e.getMessage()
                ));
                return ModelCallResult.failed("model call failed without recovery: " + e.getMessage(), "model-error");
            }
            return recoverModelCall(request, contextSteps, originalSteps, runContextManager, compressionEvents, recoveryEvents, traceEvents, e);
        } catch (RuntimeException e) {
            emitTrace(traceEvents, modelCallTrace("failed", "main", "NON_RETRYABLE"));
            recoveryEvents.add(modelCallRecovery.event(
                    ModelErrorCode.NON_RETRYABLE,
                    "model_exception",
                    0,
                    false,
                    request.modelOptions().maxOutputTokens(),
                    request.modelOptions().maxOutputTokens(),
                    e.getMessage()
            ));
            return ModelCallResult.failed("model call failed: " + e.getMessage(), "model-error");
        }
    }

    private ModelCallResult recoverModelCall(
            AgentRequest request,
            List<AgentStep> contextSteps,
            List<AgentStep> originalSteps,
            ContextManager runContextManager,
            List<ContextCompressionEvent> compressionEvents,
            List<AgentRecoveryEvent> recoveryEvents,
            List<TraceEvent> traceEvents,
            ModelException exception
    ) {
        return switch (exception.code()) {
            case PROMPT_TOO_LONG -> recoverPromptTooLong(request, originalSteps, runContextManager, compressionEvents, recoveryEvents, traceEvents, exception);
            case OUTPUT_TRUNCATED -> recoverOutputTruncated(request, contextSteps, recoveryEvents, traceEvents, exception);
            case TRANSIENT -> recoverTransient(request, contextSteps, recoveryEvents, traceEvents, exception);
            case NON_RETRYABLE -> {
                recoveryEvents.add(modelCallRecovery.event(
                        ModelErrorCode.NON_RETRYABLE,
                        "not_retryable",
                        0,
                        false,
                        request.modelOptions().maxOutputTokens(),
                        request.modelOptions().maxOutputTokens(),
                        exception.getMessage()
                ));
                yield ModelCallResult.failed("model call failed: " + exception.getMessage(), "model-error");
            }
        };
    }

    private ModelCallResult recoverPromptTooLong(
            AgentRequest request,
            List<AgentStep> originalSteps,
            ContextManager runContextManager,
            List<ContextCompressionEvent> compressionEvents,
            List<AgentRecoveryEvent> recoveryEvents,
            List<TraceEvent> traceEvents,
            ModelException exception
    ) {
        ContextManager.ContextView reactiveView = runContextManager.reactiveCompact(request, originalSteps, exception);
        compressionEvents.addAll(reactiveView.events());
        emitCompressionTraces(traceEvents, reactiveView.events());
        if (reactiveView.failed()) {
            recoveryEvents.add(modelCallRecovery.event(
                    ModelErrorCode.PROMPT_TOO_LONG,
                    "reactive_compact",
                    1,
                    false,
                    request.modelOptions().maxOutputTokens(),
                    request.modelOptions().maxOutputTokens(),
                    reactiveView.failureMessage()
            ));
            return ModelCallResult.failed(reactiveView.failureMessage(), "prompt-too-long");
        }
        try {
            emitTrace(traceEvents, modelCallTrace("start", "prompt_too_long_recovery", ""));
            AgentStep step = modelClient.nextStep(reactiveView.request(), reactiveView.steps(), toolRegistry.tools());
            emitTrace(traceEvents, modelCallTrace("success", "prompt_too_long_recovery", ""));
            recoveryEvents.add(modelCallRecovery.event(
                    ModelErrorCode.PROMPT_TOO_LONG,
                    "reactive_compact_retry",
                    1,
                    true,
                    request.modelOptions().maxOutputTokens(),
                    reactiveView.request().modelOptions().maxOutputTokens(),
                    exception.getMessage()
            ));
            return ModelCallResult.ok(step, reactiveView.request());
        } catch (ModelException retryFailure) {
            emitTrace(traceEvents, modelCallTrace("failed", "prompt_too_long_recovery", retryFailure.code().name()));
            recoveryEvents.add(modelCallRecovery.event(
                    retryFailure.code(),
                    "reactive_compact_retry",
                    1,
                    false,
                    request.modelOptions().maxOutputTokens(),
                    reactiveView.request().modelOptions().maxOutputTokens(),
                    retryFailure.getMessage()
            ));
            String prefix = retryFailure.code() == ModelErrorCode.PROMPT_TOO_LONG
                    ? "prompt too long after reactiveCompact: "
                    : "model call failed during prompt-too-long recovery: ";
            return ModelCallResult.failed(prefix + retryFailure.getMessage(), "prompt-too-long");
        } catch (RuntimeException retryFailure) {
            emitTrace(traceEvents, modelCallTrace("failed", "prompt_too_long_recovery", "NON_RETRYABLE"));
            recoveryEvents.add(modelCallRecovery.event(
                    ModelErrorCode.NON_RETRYABLE,
                    "reactive_compact_retry",
                    1,
                    false,
                    request.modelOptions().maxOutputTokens(),
                    reactiveView.request().modelOptions().maxOutputTokens(),
                    retryFailure.getMessage()
            ));
            return ModelCallResult.failed("model call failed during prompt-too-long recovery: " + retryFailure.getMessage(), "model-error");
        }
    }

    private ModelCallResult recoverOutputTruncated(
            AgentRequest request,
            List<AgentStep> contextSteps,
            List<AgentRecoveryEvent> recoveryEvents,
            List<TraceEvent> traceEvents,
            ModelException exception
    ) {
        int before = request.modelOptions().maxOutputTokens();
        AgentRequest retryRequest = modelCallRecovery.continuationRequest(request, exception.partialContent());
        int after = retryRequest.modelOptions().maxOutputTokens();
        try {
            emitTrace(traceEvents, modelCallTrace("start", "output_truncated_recovery", ""));
            AgentStep step = modelClient.nextStep(retryRequest, contextSteps, toolRegistry.tools());
            emitTrace(traceEvents, modelCallTrace("success", "output_truncated_recovery", ""));
            if (step.type() == AgentStep.Type.FINAL_ANSWER && !exception.partialContent().isBlank()) {
                step = AgentStep.finalAnswer(exception.partialContent() + step.content());
            }
            recoveryEvents.add(modelCallRecovery.event(
                    ModelErrorCode.OUTPUT_TRUNCATED,
                    "increase_output_budget_and_continue",
                    1,
                    true,
                    before,
                    after,
                    exception.getMessage()
            ));
            return ModelCallResult.ok(step, retryRequest);
        } catch (ModelException retryFailure) {
            emitTrace(traceEvents, modelCallTrace("failed", "output_truncated_recovery", retryFailure.code().name()));
            recoveryEvents.add(modelCallRecovery.event(
                    retryFailure.code(),
                    "increase_output_budget_and_continue",
                    1,
                    false,
                    before,
                    after,
                    retryFailure.getMessage()
            ));
            return ModelCallResult.failed("model output recovery failed: " + retryFailure.getMessage(), "output-truncated");
        } catch (RuntimeException retryFailure) {
            emitTrace(traceEvents, modelCallTrace("failed", "output_truncated_recovery", "NON_RETRYABLE"));
            recoveryEvents.add(modelCallRecovery.event(
                    ModelErrorCode.NON_RETRYABLE,
                    "increase_output_budget_and_continue",
                    1,
                    false,
                    before,
                    after,
                    retryFailure.getMessage()
            ));
            return ModelCallResult.failed("model output recovery failed: " + retryFailure.getMessage(), "model-error");
        }
    }

    private ModelCallResult recoverTransient(
            AgentRequest request,
            List<AgentStep> contextSteps,
            List<AgentRecoveryEvent> recoveryEvents,
            List<TraceEvent> traceEvents,
            ModelException exception
    ) {
        ModelException last = exception;
        for (int attempt = 1; attempt <= modelCallRecovery.transientRetryAttempts(); attempt++) {
            try {
                modelCallRecovery.sleep(modelCallRecovery.retryDelay(last, attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recoveryEvents.add(modelCallRecovery.event(
                        ModelErrorCode.TRANSIENT,
                        "retry_interrupted",
                        attempt,
                        false,
                        request.modelOptions().maxOutputTokens(),
                        request.modelOptions().maxOutputTokens(),
                        e.getMessage()
                ));
                return ModelCallResult.failed("model retry interrupted", "transient-model-error");
            }
            try {
                emitTrace(traceEvents, modelCallTrace("start", "transient_retry_" + attempt, ""));
                AgentStep step = modelClient.nextStep(request, contextSteps, toolRegistry.tools());
                emitTrace(traceEvents, modelCallTrace("success", "transient_retry_" + attempt, ""));
                recoveryEvents.add(modelCallRecovery.event(
                        ModelErrorCode.TRANSIENT,
                        "retry_with_backoff",
                        attempt,
                        true,
                        request.modelOptions().maxOutputTokens(),
                        request.modelOptions().maxOutputTokens(),
                        last.getMessage()
                ));
                return ModelCallResult.ok(step, request);
            } catch (ModelException retryFailure) {
                emitTrace(traceEvents, modelCallTrace("failed", "transient_retry_" + attempt, retryFailure.code().name()));
                last = retryFailure;
                recoveryEvents.add(modelCallRecovery.event(
                        retryFailure.code(),
                        "retry_with_backoff",
                        attempt,
                        false,
                        request.modelOptions().maxOutputTokens(),
                        request.modelOptions().maxOutputTokens(),
                        retryFailure.getMessage()
                ));
                if (retryFailure.code() != ModelErrorCode.TRANSIENT) {
                    return ModelCallResult.failed("model call failed during transient recovery: " + retryFailure.getMessage(), "model-error");
                }
            } catch (RuntimeException retryFailure) {
                emitTrace(traceEvents, modelCallTrace("failed", "transient_retry_" + attempt, "NON_RETRYABLE"));
                recoveryEvents.add(modelCallRecovery.event(
                        ModelErrorCode.NON_RETRYABLE,
                        "retry_with_backoff",
                        attempt,
                        false,
                        request.modelOptions().maxOutputTokens(),
                        request.modelOptions().maxOutputTokens(),
                        retryFailure.getMessage()
                ));
                return ModelCallResult.failed("model call failed during transient recovery: " + retryFailure.getMessage(), "model-error");
            }
        }
        return ModelCallResult.failed(
                "transient model error after " + modelCallRecovery.transientRetryAttempts() + " retries: " + last.getMessage(),
                "transient-model-error"
        );
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

        AgentStep backgroundStep = maybeStartBackgroundTask(preToolUseContext);
        if (backgroundStep != null) {
            return backgroundStep;
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

    private AgentStep maybeStartBackgroundTask(PreToolUseContext context) {
        if (!(context.tool() instanceof BashTool bashTool)) {
            return null;
        }
        if (!backgroundTaskManager.shouldRunBashInBackground(context.toolRequest())) {
            return null;
        }
        ToolResult result = backgroundTaskManager.startBash(bashTool, context.toolRequest());
        if (result.success()) {
            return AgentStep.toolResult(context.toolCall().toolCallId(), bashTool.name(), result.output());
        }
        return AgentStep.toolResult(context.toolCall().toolCallId(), bashTool.name(), "Tool bash failed: " + result.error());
    }

    private void appendNotifications(List<AgentStep> steps, List<BackgroundTaskNotification> notifications, List<TraceEvent> traceEvents) {
        for (BackgroundTaskNotification notification : notifications) {
            AgentStep step = AgentStep.taskNotification(notification);
            steps.add(step);
            Summary summary = summarizeText(notification.content());
            emitTrace(traceEvents, TraceEvent.of(
                    TraceEvent.Type.TASK_NOTIFICATION,
                    orderedAttributes(
                            "bg_id", notification.bgId(),
                            "tool", notification.toolName(),
                            "status", notification.status(),
                            "summary", summary.text(),
                            "truncated", Boolean.toString(summary.truncated())
                    )
            ));
        }
    }

    private void emitTrace(List<TraceEvent> traceEvents, TraceEvent event) {
        TraceEvent enriched = enrichTraceWithRunId(event);
        traceEvents.add(enriched);
        try {
            traceSink.emit(enriched);
        } catch (RuntimeException e) {
            LOG.debug("Trace sink failed: {}", e.getMessage());
        }
    }

    private TraceEvent enrichTraceWithRunId(TraceEvent event) {
        String runId = activeRunId.get();
        if (runId == null || runId.isBlank() || event.attributes().containsKey("runId")) {
            return event;
        }
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>(event.attributes());
        attributes.put("runId", runId);
        return TraceEvent.of(event.type(), attributes);
    }

    private TraceEvent modelCallTrace(String status, String phase, String errorCode) {
        LinkedHashMap<String, String> attributes = orderedAttributes(
                "status", status,
                "phase", phase
        );
        if (!errorCode.isBlank()) {
            attributes.put("error", errorCode);
        }
        return TraceEvent.of(TraceEvent.Type.MODEL_CALL, attributes);
    }

    private TraceEvent toolCallTrace(AgentStep step) {
        Summary summary = summarizeArguments(step.toolArgumentsOrEmptyObject());
        return TraceEvent.of(
                TraceEvent.Type.TOOL_CALL,
                orderedAttributes(
                        "tool", step.toolNameOptional().orElse("unknown"),
                        "args", summary.text(),
                        "truncated", Boolean.toString(summary.truncated())
                )
        );
    }

    private TraceEvent toolResultTrace(AgentStep step) {
        if (step.type() == AgentStep.Type.ERROR) {
            return errorTrace("tool-error", step.content());
        }
        Summary summary = summarizeText(step.content());
        boolean permissionDenied = step.content().startsWith("permission denied:");
        boolean success = !permissionDenied && !step.content().startsWith("Tool " + step.toolNameOptional().orElse("") + " failed:");
        return TraceEvent.of(
                permissionDenied ? TraceEvent.Type.PERMISSION_DENIED : TraceEvent.Type.TOOL_RESULT,
                orderedAttributes(
                        "tool", step.toolNameOptional().orElse("unknown"),
                        "success", Boolean.toString(success),
                        "summary", summary.text(),
                        "truncated", Boolean.toString(summary.truncated())
                )
        );
    }

    private void emitCompressionTraces(List<TraceEvent> traceEvents, List<ContextCompressionEvent> events) {
        for (ContextCompressionEvent event : events) {
            emitTrace(traceEvents, TraceEvent.of(
                    TraceEvent.Type.COMPRESSION,
                    orderedAttributes(
                            "stage", event.stage().name(),
                            "reason", event.reason(),
                            "before_tokens", Integer.toString(event.estimatedTokensBefore()),
                            "after_tokens", Integer.toString(event.estimatedTokensAfter())
                    )
            ));
        }
    }

    private void emitRecoveryTraces(List<TraceEvent> traceEvents, List<AgentRecoveryEvent> events) {
        for (AgentRecoveryEvent event : events) {
            emitTrace(traceEvents, TraceEvent.of(
                    TraceEvent.Type.RECOVERY,
                    orderedAttributes(
                            "code", event.errorCode().name(),
                            "action", event.action(),
                            "attempt", Integer.toString(event.attempt()),
                            "recovered", Boolean.toString(event.recovered())
                    )
            ));
        }
    }

    private TraceEvent errorTrace(String reason, String message) {
        Summary summary = summarizeText(message);
        return TraceEvent.of(
                TraceEvent.Type.ERROR,
                orderedAttributes(
                        "reason", reason,
                        "summary", summary.text(),
                        "truncated", Boolean.toString(summary.truncated())
                )
        );
    }

    private TraceEvent finalAnswerTrace(boolean completed) {
        return TraceEvent.of(
                TraceEvent.Type.FINAL_ANSWER,
                orderedAttributes("completed", Boolean.toString(completed))
        );
    }

    private Summary summarizeArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return summarizeText(arguments == null ? "" : arguments.toString());
        }
        List<String> parts = new ArrayList<>();
        arguments.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String value = isSensitiveKey(key) ? "[masked]" : argumentValue(entry.getValue());
            parts.add(key + "=" + value);
        });
        return summarizeText(String.join(", ", parts));
    }

    private String argumentValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return value.toString();
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("private_key")
                || normalized.equals("content")
                || normalized.equals("newtext")
                || normalized.equals("oldtext");
    }

    private Summary summarizeText(String value) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (normalized.length() <= 500) {
            return new Summary(normalized, false);
        }
        return new Summary(normalized.substring(0, 500), true);
    }

    private LinkedHashMap<String, String> orderedAttributes(String... pairs) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        if (pairs == null) {
            return attributes;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            attributes.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return attributes;
    }

    private record Summary(String text, boolean truncated) {
    }

    private AgentResult finish(AgentRequest request, List<AgentStep> steps, AgentResult result, String reason, RunAuditRun auditRun) {
        try {
            HookResult<StopContext> stopResult = hookRegistry.triggerHooks(
                    HookEvent.STOP,
                    new StopContext(request, steps, result, reason)
            );
            if (stopResult.outcome() == HookResult.Outcome.BLOCK) {
                return auditRun.complete(new AgentResult("Stop hook blocked result: " + stopResult.reason(), steps, false, result.compressionEvents(), result.recoveryEvents(), result.traceEvents()), "stop-hook-blocked");
            }
            return auditRun.complete(stopResult.context().result(), reason);
        } catch (RuntimeException e) {
            return auditRun.complete(new AgentResult("Stop hook failed: " + e.getMessage(), steps, false, result.compressionEvents(), result.recoveryEvents(), result.traceEvents()), "stop-hook-error");
        }
    }

    private static final class RunAuditRun {

        static final String RUN_ID_METADATA_KEY = "agent.run.id";
        static final String PARENT_RUN_ID_METADATA_KEY = "agent.parentRunId";
        static final String CORRELATION_ID_METADATA_KEY = "agent.correlationId";
        static final String ACTOR_METADATA_KEY = "agent.actor";

        private final String runId;
        private final String parentRunId;
        private final String correlationId;
        private final String actor;
        private final String trigger;
        private final String profileName;
        private final String policyName;
        private final String startedAt;
        private final RunAuditSink sink;
        private final AtomicLong sequence = new AtomicLong();

        private RunAuditRun(
                String runId,
                String parentRunId,
                String correlationId,
                String actor,
                String trigger,
                String profileName,
                String policyName,
                String startedAt,
                RunAuditSink sink
        ) {
            this.runId = runId;
            this.parentRunId = parentRunId;
            this.correlationId = correlationId;
            this.actor = actor;
            this.trigger = trigger;
            this.profileName = profileName;
            this.policyName = policyName;
            this.startedAt = startedAt;
            this.sink = sink;
        }

        static RunAuditRun from(AgentRequest request, RunAuditSink sink) {
            Map<String, String> metadata = request.metadata();
            String runId = firstNonBlank(metadata.get(RUN_ID_METADATA_KEY), "run_" + UUID.randomUUID().toString().replace("-", ""));
            String actor = firstNonBlank(metadata.get(ACTOR_METADATA_KEY), metadata.getOrDefault("agent.name", "agent"));
            return new RunAuditRun(
                    runId,
                    metadata.getOrDefault(PARENT_RUN_ID_METADATA_KEY, ""),
                    metadata.getOrDefault(CORRELATION_ID_METADATA_KEY, ""),
                    actor,
                    metadata.getOrDefault("agent.trigger", "user"),
                    metadata.getOrDefault("agent.profile.name", ""),
                    metadata.getOrDefault("agent.policy.name", ""),
                    java.time.Instant.now().toString(),
                    sink
            );
        }

        String runId() {
            return runId;
        }

        AgentResult complete(AgentResult result, String reason) {
            ArrayList<AuditEvent> auditEvents = new ArrayList<>();
            LinkedHashMap<String, String> startAttributes = ordered(
                    "trigger", trigger,
                    "reason", "start"
            );
            if (!profileName.isBlank()) {
                startAttributes.put("profile", profileName);
            }
            if (!policyName.isBlank()) {
                startAttributes.put("policy", policyName);
            }
            emit(auditEvents, AuditEvent.Type.RUN_START, "", startAttributes);
            for (TraceEvent traceEvent : result.traceEvents()) {
                LinkedHashMap<String, String> attributes = new LinkedHashMap<>(traceEvent.attributes());
                String phase = attributes.remove("phase");
                attributes.remove("runId");
                if (traceEvent.type() == TraceEvent.Type.PERMISSION_DENIED) {
                    attributes.put("action", "deny");
                }
                emit(auditEvents, toAuditType(traceEvent.type()), phase == null ? "" : phase, attributes);
            }
            emit(auditEvents, AuditEvent.Type.RUN_END, "", ordered(
                    "status", result.completed() ? "completed" : "failed",
                    "reason", reason,
                    "summary", summarize(result.output())
            ));
            RunAuditSummary summary = new RunAuditSummary(
                    runId,
                    parentRunId,
                    actor,
                    trigger,
                    startedAt,
                    java.time.Instant.now().toString(),
                    result.completed() ? "completed" : "failed",
                    summarize(result.output())
            );
            try {
                sink.closeRun(summary);
            } catch (RuntimeException e) {
                addAuditSinkFailure(auditEvents, "close-run", e);
            }
            return new AgentResult(
                    result.output(),
                    result.steps(),
                    result.completed(),
                    result.compressionEvents(),
                    result.recoveryEvents(),
                    result.traceEvents(),
                    runId,
                    auditEvents
            );
        }

        private void emit(List<AuditEvent> auditEvents, AuditEvent.Type type, String phase, Map<String, String> attributes) {
            AuditEvent event = AuditEvent.of(
                    runId,
                    sequence.incrementAndGet(),
                    type,
                    actor,
                    phase,
                    parentRunId,
                    correlationId,
                    attributes
            );
            auditEvents.add(event);
            try {
                sink.emit(event);
            } catch (RuntimeException e) {
                addAuditSinkFailure(auditEvents, "emit", e);
            }
        }

        private void addAuditSinkFailure(List<AuditEvent> auditEvents, String operation, RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            System.err.println("[audit] failed to write run audit " + operation + ": " + message);
            auditEvents.add(AuditEvent.of(
                    runId,
                    sequence.incrementAndGet(),
                    AuditEvent.Type.ERROR,
                    actor,
                    "audit",
                    parentRunId,
                    correlationId,
                    ordered(
                            "reason", "audit-write-failed",
                            "operation", operation,
                            "message", summarize(message)
                    )
            ));
        }

        private static AuditEvent.Type toAuditType(TraceEvent.Type type) {
            return switch (type) {
                case MODEL_CALL -> AuditEvent.Type.MODEL_CALL;
                case TOOL_CALL -> AuditEvent.Type.TOOL_CALL;
                case TOOL_RESULT -> AuditEvent.Type.TOOL_RESULT;
                case TASK_NOTIFICATION -> AuditEvent.Type.TASK_NOTIFICATION;
                case COMPRESSION -> AuditEvent.Type.COMPRESSION;
                case RECOVERY -> AuditEvent.Type.RECOVERY;
                case PERMISSION_DENIED -> AuditEvent.Type.PERMISSION_DENIED;
                case ERROR -> AuditEvent.Type.ERROR;
                case FINAL_ANSWER -> AuditEvent.Type.FINAL_ANSWER;
            };
        }

        private static String firstNonBlank(String first, String fallback) {
            return first == null || first.isBlank() ? fallback : first.strip();
        }

        private static LinkedHashMap<String, String> ordered(String... pairs) {
            LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
            for (int i = 0; pairs != null && i + 1 < pairs.length; i += 2) {
                attributes.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
            }
            return attributes;
        }

        private static String summarize(String output) {
            String normalized = output == null ? "" : output.strip().replaceAll("\\s+", " ");
            return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
        }
    }

    private record ModelCallResult(
            AgentStep step,
            AgentRequest request,
            boolean failed,
            String failureMessage,
            String reason
    ) {

        static ModelCallResult ok(AgentStep step, AgentRequest request) {
            return new ModelCallResult(step, request, false, "", "");
        }

        static ModelCallResult failed(String message, String reason) {
            return new ModelCallResult(null, null, true, message, reason);
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
