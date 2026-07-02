package io.github.mengru.agent.core.team;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.ModelOptions;
import io.github.mengru.agent.core.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TeamInboxPoller implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TeamInboxPoller.class);

    private final TeamRuntime runtime;
    private final AgentSession session;
    private final Supplier<Map<String, String>> metadataSupplier;
    private final String systemPrompt;
    private final int maxSteps;
    private final ModelOptions modelOptions;
    private final Consumer<TeamExecutionResult> resultConsumer;
    private final Duration pollInterval;
    private final Duration debounceInterval;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public TeamInboxPoller(
            TeamRuntime runtime,
            AgentSession session,
            Supplier<Map<String, String>> metadataSupplier,
            String systemPrompt,
            int maxSteps,
            ModelOptions modelOptions,
            Consumer<TeamExecutionResult> resultConsumer
    ) {
        this(runtime, session, metadataSupplier, systemPrompt, maxSteps, modelOptions, resultConsumer, Duration.ofSeconds(1), Duration.ofMillis(500));
    }

    TeamInboxPoller(
            TeamRuntime runtime,
            AgentSession session,
            Supplier<Map<String, String>> metadataSupplier,
            String systemPrompt,
            int maxSteps,
            ModelOptions modelOptions,
            Consumer<TeamExecutionResult> resultConsumer,
            Duration pollInterval
    ) {
        this(runtime, session, metadataSupplier, systemPrompt, maxSteps, modelOptions, resultConsumer, pollInterval, Duration.ZERO);
    }

    TeamInboxPoller(
            TeamRuntime runtime,
            AgentSession session,
            Supplier<Map<String, String>> metadataSupplier,
            String systemPrompt,
            int maxSteps,
            ModelOptions modelOptions,
            Consumer<TeamExecutionResult> resultConsumer,
            Duration pollInterval,
            Duration debounceInterval
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.metadataSupplier = Objects.requireNonNull(metadataSupplier, "metadataSupplier must not be null");
        this.systemPrompt = systemPrompt;
        this.maxSteps = maxSteps;
        this.modelOptions = modelOptions == null ? ModelOptions.defaults() : modelOptions;
        this.resultConsumer = Objects.requireNonNull(resultConsumer, "resultConsumer must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        this.debounceInterval = Objects.requireNonNull(debounceInterval, "debounceInterval must not be null");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::runLoop, "agent-team-inbox-poller");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        running.set(false);
    }

    private void runLoop() {
        while (running.get()) {
            try {
                List<TeamMessage> messages = runtime.consumeLeadMessages();
                List<TeamMessage> modelVisibleMessages = collectModelVisibleMessages(messages);
                if (!modelVisibleMessages.isEmpty()) {
                    AgentResult result = session.run(new AgentRequest(
                            runtime.renderLeadInbox(modelVisibleMessages),
                            maxSteps,
                            teamMetadata(),
                            systemPrompt
                    ).withModelOptions(modelOptions));
                    resultConsumer.accept(new TeamExecutionResult(modelVisibleMessages, result, true, ""));
                }
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
            } catch (RuntimeException e) {
                LOG.debug("Team inbox poller failed: {}", e.getMessage());
                resultConsumer.accept(new TeamExecutionResult(List.of(), null, false, e.getMessage()));
            }
        }
    }

    private List<TeamMessage> collectModelVisibleMessages(List<TeamMessage> messages) throws InterruptedException {
        ArrayList<TeamMessage> visibleMessages = new ArrayList<>(modelVisible(messages));
        if (visibleMessages.isEmpty()) {
            return List.of();
        }
        if (!debounceInterval.isZero() && !debounceInterval.isNegative()) {
            Thread.sleep(debounceInterval.toMillis());
            visibleMessages.addAll(modelVisible(runtime.consumeLeadMessages()));
        }
        return List.copyOf(visibleMessages);
    }

    private static List<TeamMessage> modelVisible(List<TeamMessage> messages) {
        return messages.stream()
                .filter(TeamInboxPoller::isModelVisible)
                .toList();
    }

    private static boolean isModelVisible(TeamMessage message) {
        return message.type() != TeamMessageType.PERMISSION_REQUEST
                && message.type() != TeamMessageType.PERMISSION_RESPONSE;
    }

    private Map<String, String> teamMetadata() {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(metadataSupplier.get());
        metadata.put("agent.trigger", "team");
        metadata.put(TeamRuntime.TEAM_ID_METADATA_KEY, runtime.teamId());
        return Map.copyOf(metadata);
    }
}
