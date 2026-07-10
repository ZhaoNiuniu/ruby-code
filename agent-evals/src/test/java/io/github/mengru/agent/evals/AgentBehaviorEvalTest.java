package io.github.mengru.agent.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.AuditEvent;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.ModelErrorCode;
import io.github.mengru.agent.api.ModelException;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.DefaultAgent;
import io.github.mengru.agent.core.background.BackgroundTaskManager;
import io.github.mengru.agent.core.audit.RunAuditSink;
import io.github.mengru.agent.core.context.ContextManager;
import io.github.mengru.agent.core.hook.HookRegistry;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.recovery.ErrorRecoveryConfig;
import io.github.mengru.agent.core.recovery.ModelCallRecovery;
import io.github.mengru.agent.core.recovery.RetrySleeper;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.core.trace.TraceSink;
import io.github.mengru.agent.runtime.PolicyPermissionChecker;
import io.github.mengru.agent.runtime.RuntimeProfileOverrides;
import io.github.mengru.agent.runtime.RuntimeProfileResolution;
import io.github.mengru.agent.runtime.RuntimeProfileResolver;
import io.github.mengru.agent.runtime.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBehaviorEvalTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @TempDir
    Path tempRoot;

    @Test
    void deterministicEvalCasesPass() throws Exception {
        List<EvalCase> cases = loadEvalCases();
        assertThat(cases)
                .as("deterministic eval cases")
                .isNotEmpty();

        List<EvalRunReport> reports = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            EvalRunReport report = runCase(evalCase);
            reports.add(report);
            writeReport(report);
            if (!report.passed()) {
                failures.add(report.caseId() + ": " + String.join("; ", report.failures()));
            }
        }

        System.out.printf(
                Locale.ROOT,
                "agent-evals deterministic summary: passed=%d failed=%d%n",
                reports.stream().filter(EvalRunReport::passed).count(),
                reports.stream().filter(report -> !report.passed()).count()
        );
        assertThat(failures).isEmpty();
    }

    @Test
    void invalidCaseValidationFailsFast() {
        ObjectNode invalid = JsonNodeFactory.instance.objectNode();
        invalid.put("id", "invalid_case");
        invalid.put("category", "tool-calling");

        assertThatThrownBy(() -> EvalCase.from(invalid, Path.of("invalid.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");

        ObjectNode unknownStep = validCaseSkeleton();
        ObjectNode step = JsonNodeFactory.instance.objectNode();
        step.put("type", "unknown");
        unknownStep.withArray("modelSteps").add(step);
        assertThatThrownBy(() -> EvalCase.from(unknownStep, Path.of("unknown-step.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown model step type");

        ObjectNode unknownMatcher = validCaseSkeleton();
        ObjectNode finalStep = JsonNodeFactory.instance.objectNode();
        finalStep.put("type", "final_answer");
        finalStep.put("content", "ok");
        unknownMatcher.withArray("modelSteps").add(finalStep);
        unknownMatcher.with("expect").put("unknownMatcher", true);
        assertThatThrownBy(() -> EvalCase.from(unknownMatcher, Path.of("unknown-matcher.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown expect field");
    }

    @Test
    void matcherReportsMissingAndForbiddenEvents() {
        ObjectNode expectationJson = JSON.createObjectNode();
        expectationJson.put("completed", true);
        ObjectNode required = JSON.createObjectNode();
        required.put("type", "TOOL_CALL");
        required.set("attributes", JSON.createObjectNode().put("tool", "write_file"));
        expectationJson.set("requiredEvents", JSON.createArrayNode().add(required));
        expectationJson.set("forbiddenEvents", JSON.createArrayNode().add(JSON.createObjectNode().put("type", "ERROR")));
        EvalExpectation expectation = EvalExpectation.from(expectationJson);
        AgentResult result = new AgentResult(
                "ok",
                List.of(),
                true,
                List.of(),
                List.of(),
                List.of(),
                "run_eval_matcher",
                List.of(AuditEvent.of(
                        "run_eval_matcher",
                        1,
                        AuditEvent.Type.ERROR,
                        "lead:main",
                        "main",
                        "",
                        "",
                        Map.of("reason", "boom")
                ))
        );

        List<String> failures = expectation.evaluate(result);

        assertThat(failures)
                .anyMatch(message -> message.contains("missing required event"))
                .anyMatch(message -> message.contains("forbidden event matched"));
    }

    private EvalRunReport runCase(EvalCase evalCase) throws IOException {
        Path workspace = Files.createDirectories(tempRoot.resolve(evalCase.id()));
        RuntimeSettings settings = resolveSettings(workspace, evalCase.profile());
        ToolRegistry tools = fakeToolRegistry();
        UserApprover approver = "allow".equals(evalCase.approval()) ? UserApprover.allowAll() : UserApprover.denyAll();
        HookRegistry hooks = HookRegistry.defaultsFor(
                tools,
                approver,
                MemoryCatalog.empty(workspace),
                new PolicyPermissionChecker(settings, workspace)
        );
        DefaultAgent agent = new DefaultAgent(
                new ScriptedModelClient(evalCase.modelSteps()),
                tools,
                hooks,
                ContextManager.disabled(),
                noSleepRecovery(settings),
                BackgroundTaskManager.disabled(),
                TraceSink.noop(),
                RunAuditSink.noop()
        );

        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(evalCase.metadata());
        metadata.putIfAbsent("agent.run.id", "eval_" + evalCase.id());
        metadata.putIfAbsent("agent.actor", metadata.getOrDefault("agent.name", "lead:main"));
        metadata.putIfAbsent("agent.profile.name", settings.profileName());
        metadata.putIfAbsent("agent.policy.name", settings.policyName());

        AgentResult result = agent.run(new AgentRequest(evalCase.task(), evalCase.maxSteps(), metadata));
        List<String> failures = evalCase.expectation().evaluate(result);
        return EvalRunReport.from(evalCase, result, failures);
    }

    private RuntimeSettings resolveSettings(Path workspace, String profile) {
        RuntimeProfileResolution resolution = new RuntimeProfileResolver().resolve(
                workspace,
                profile,
                RuntimeProfileOverrides.builder()
                        .provider("echo")
                        .traceSink("none")
                        .contextCompressionEnabled(false)
                        .build(),
                Map.of()
        );
        return resolution.settings();
    }

    private ModelCallRecovery noSleepRecovery(RuntimeSettings settings) {
        return new ModelCallRecovery(
                new ErrorRecoveryConfig(
                        settings.errorRecoveryEnabled(),
                        settings.modelRetryAttempts(),
                        settings.recoveryMaxOutputTokens(),
                        Duration.ZERO,
                        false
                ),
                RetrySleeper.noSleep()
        );
    }

    private List<EvalCase> loadEvalCases() throws IOException {
        Path root = evalRoot();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .map(path -> {
                        try {
                            return EvalCase.from(JSON.readTree(path.toFile()), root.relativize(path));
                        } catch (IOException e) {
                            throw new IllegalArgumentException("failed to read eval case " + path + ": " + e.getMessage(), e);
                        }
                    })
                    .toList();
        }
    }

    private Path evalRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path sibling = cwd.resolve("evals");
        if (Files.isDirectory(sibling)) {
            return sibling;
        }
        return cwd.getParent().resolve("evals").normalize();
    }

    private void writeReport(EvalRunReport report) throws IOException {
        Path reportDir = Path.of("target", "eval-reports");
        Files.createDirectories(reportDir);
        Files.writeString(
                reportDir.resolve(report.caseId() + ".json"),
                JSON.writeValueAsString(report.toJsonMap()) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
    }

    private ToolRegistry fakeToolRegistry() {
        return ToolRegistry.of(List.of(
                new FakeTool("read_file", "Read file contents."),
                new FakeTool("bash", "Run a shell command."),
                new FakeTool("write_file", "Write content to file."),
                new FakeTool("mcp__demo__echo", "Fake MCP echo tool.")
        ));
    }

    private ObjectNode validCaseSkeleton() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", "valid");
        node.put("category", "tool-calling");
        node.put("profile", "dev");
        node.put("task", "do work");
        node.set("modelSteps", JsonNodeFactory.instance.arrayNode());
        node.set("expect", JsonNodeFactory.instance.objectNode().put("completed", true));
        return node;
    }

    private record EvalCase(
            String id,
            String category,
            String profile,
            String task,
            int maxSteps,
            String approval,
            Map<String, String> metadata,
            List<ModelStep> modelSteps,
            EvalExpectation expectation,
            String source
    ) {

        static EvalCase from(JsonNode root, Path source) {
            requireObject(root, source.toString());
            String id = requiredText(root, "id", source);
            String category = requiredText(root, "category", source);
            String profile = requiredText(root, "profile", source);
            String task = requiredText(root, "task", source);
            int maxSteps = root.path("maxSteps").isMissingNode() ? 8 : root.path("maxSteps").asInt();
            if (maxSteps < 1) {
                throw new IllegalArgumentException(source + ": maxSteps must be greater than zero");
            }
            String approval = root.path("approval").asText("deny").strip().toLowerCase(Locale.ROOT);
            if (!Set.of("allow", "deny").contains(approval)) {
                throw new IllegalArgumentException(source + ": approval must be allow or deny");
            }
            JsonNode steps = root.get("modelSteps");
            if (steps == null || !steps.isArray() || steps.isEmpty()) {
                throw new IllegalArgumentException(source + ": modelSteps must be a non-empty array");
            }
            ArrayList<ModelStep> modelSteps = new ArrayList<>();
            for (JsonNode step : steps) {
                modelSteps.add(ModelStep.from(step, source));
            }
            JsonNode expect = root.get("expect");
            if (expect == null || !expect.isObject()) {
                throw new IllegalArgumentException(source + ": expect must be an object");
            }
            return new EvalCase(
                    id,
                    category,
                    profile,
                    task,
                    maxSteps,
                    approval,
                    stringMap(root.path("metadata")),
                    List.copyOf(modelSteps),
                    EvalExpectation.from(expect),
                    source.toString()
            );
        }
    }

    private record ModelStep(
            String type,
            String toolCallId,
            String toolName,
            JsonNode arguments,
            String content,
            ModelErrorCode errorCode,
            String message
    ) {

        static ModelStep from(JsonNode node, Path source) {
            requireObject(node, source.toString());
            String type = requiredText(node, "type", source).toLowerCase(Locale.ROOT);
            return switch (type) {
                case "tool_call" -> new ModelStep(
                        type,
                        node.path("toolCallId").asText("call_" + System.nanoTime()),
                        requiredText(node, "toolName", source),
                        objectOrEmpty(node.path("arguments")),
                        "",
                        null,
                        ""
                );
                case "final_answer" -> new ModelStep(
                        type,
                        "",
                        "",
                        JsonNodeFactory.instance.objectNode(),
                        requiredText(node, "content", source),
                        null,
                        ""
                );
                case "error" -> new ModelStep(
                        type,
                        "",
                        "",
                        JsonNodeFactory.instance.objectNode(),
                        "",
                        ModelErrorCode.valueOf(requiredText(node, "errorCode", source)),
                        requiredText(node, "message", source)
                );
                default -> throw new IllegalArgumentException(source + ": unknown model step type: " + type);
            };
        }
    }

    private static final class ScriptedModelClient implements ModelClient {

        private final List<ModelStep> steps;
        private final AtomicInteger cursor = new AtomicInteger();

        private ScriptedModelClient(List<ModelStep> steps) {
            this.steps = List.copyOf(steps);
        }

        @Override
        public AgentStep nextStep(AgentRequest request, List<AgentStep> previousSteps, List<Tool> tools) {
            int index = cursor.getAndIncrement();
            if (index >= steps.size()) {
                throw new IllegalStateException("scripted model exhausted");
            }
            ModelStep step = steps.get(index);
            return switch (step.type()) {
                case "tool_call" -> AgentStep.toolCall(step.toolCallId(), step.toolName(), step.arguments());
                case "final_answer" -> AgentStep.finalAnswer(step.content());
                case "error" -> throw new ModelException(step.errorCode(), step.message());
                default -> throw new IllegalStateException("unknown model step type: " + step.type());
            };
        }
    }

    private record EvalExpectation(
            Boolean completed,
            String finalAnswerContains,
            List<EventPattern> auditSequence,
            List<EventPattern> requiredEvents,
            List<EventPattern> forbiddenEvents,
            List<String> forbiddenTools
    ) {

        static EvalExpectation from(JsonNode node) {
            requireObject(node, "expect");
            rejectUnknownFields(node, Set.of(
                    "completed",
                    "finalAnswerContains",
                    "auditSequence",
                    "requiredEvents",
                    "forbiddenEvents",
                    "forbiddenTools"
            ), "expect");
            return new EvalExpectation(
                    node.has("completed") ? node.path("completed").asBoolean() : null,
                    node.path("finalAnswerContains").asText(""),
                    patterns(node.path("auditSequence")),
                    patterns(node.path("requiredEvents")),
                    patterns(node.path("forbiddenEvents")),
                    strings(node.path("forbiddenTools"))
            );
        }

        List<String> evaluate(AgentResult result) {
            ArrayList<String> failures = new ArrayList<>();
            if (completed != null && result.completed() != completed) {
                failures.add("completed expected " + completed + " but was " + result.completed());
            }
            if (!finalAnswerContains.isBlank() && !result.output().contains(finalAnswerContains)) {
                failures.add("final answer did not contain: " + finalAnswerContains);
            }
            for (EventPattern required : requiredEvents) {
                if (result.auditEvents().stream().noneMatch(required::matches)) {
                    failures.add("missing required event: " + required.describe());
                }
            }
            if (!auditSequence.isEmpty()) {
                int cursor = 0;
                for (AuditEvent event : result.auditEvents()) {
                    if (auditSequence.get(cursor).matches(event)) {
                        cursor++;
                        if (cursor == auditSequence.size()) {
                            break;
                        }
                    }
                }
                if (cursor < auditSequence.size()) {
                    failures.add("missing audit sequence item: " + auditSequence.get(cursor).describe());
                }
            }
            for (EventPattern forbidden : forbiddenEvents) {
                if (result.auditEvents().stream().anyMatch(forbidden::matches)) {
                    failures.add("forbidden event matched: " + forbidden.describe());
                }
            }
            for (String tool : forbiddenTools) {
                boolean used = result.auditEvents().stream()
                        .filter(event -> event.type() == AuditEvent.Type.TOOL_CALL)
                        .anyMatch(event -> tool.equals(event.attributes().get("tool")));
                if (used) {
                    failures.add("forbidden tool was called: " + tool);
                }
            }
            return failures;
        }
    }

    private record EventPattern(
            AuditEvent.Type type,
            String actor,
            String phase,
            Map<String, String> attributes
    ) {

        static EventPattern from(JsonNode node) {
            requireObject(node, "event pattern");
            return new EventPattern(
                    AuditEvent.Type.valueOf(requiredText(node, "type", Path.of("event pattern"))),
                    node.path("actor").asText(""),
                    node.path("phase").asText(""),
                    stringMap(node.path("attributes"))
            );
        }

        boolean matches(AuditEvent event) {
            if (event.type() != type) {
                return false;
            }
            if (!actor.isBlank() && !actor.equals(event.actor())) {
                return false;
            }
            if (!phase.isBlank() && !phase.equals(event.phase())) {
                return false;
            }
            for (Map.Entry<String, String> expected : attributes.entrySet()) {
                if (!expected.getValue().equals(event.attributes().get(expected.getKey()))) {
                    return false;
                }
            }
            return true;
        }

        String describe() {
            return "type=" + type
                    + (actor.isBlank() ? "" : ", actor=" + actor)
                    + (phase.isBlank() ? "" : ", phase=" + phase)
                    + (attributes.isEmpty() ? "" : ", attributes=" + attributes);
        }
    }

    private record EvalRunReport(
            String caseId,
            String category,
            String source,
            boolean passed,
            List<String> failures,
            String runId,
            boolean completed,
            String finalAnswer,
            List<Map<String, Object>> auditSummary
    ) {

        static EvalRunReport from(EvalCase evalCase, AgentResult result, List<String> failures) {
            return new EvalRunReport(
                    evalCase.id(),
                    evalCase.category(),
                    evalCase.source(),
                    failures.isEmpty(),
                    List.copyOf(failures),
                    result.runId(),
                    result.completed(),
                    truncate(result.output(), 500),
                    result.auditEvents().stream()
                            .map(EvalRunReport::safeEvent)
                            .toList()
            );
        }

        Map<String, Object> toJsonMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("caseId", caseId);
            map.put("category", category);
            map.put("source", source);
            map.put("passed", passed);
            map.put("failures", failures);
            map.put("runId", runId);
            map.put("completed", completed);
            map.put("finalAnswer", finalAnswer);
            map.put("auditSummary", auditSummary);
            return map;
        }

        private static Map<String, Object> safeEvent(AuditEvent event) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("type", event.type().name());
            map.put("actor", event.actor());
            map.put("phase", event.phase());
            map.put("attributes", event.attributes());
            return map;
        }
    }

    private static final class FakeTool implements Tool {

        private final String name;
        private final String description;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public JsonNode parametersSchema() {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("type", "object");
            return schema;
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            calls.incrementAndGet();
            return ToolResult.success(name + " output: " + truncate(request.arguments().toString(), 200));
        }
    }

    private static List<EventPattern> patterns(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("event patterns must be arrays");
        }
        ArrayList<EventPattern> patterns = new ArrayList<>();
        for (JsonNode item : node) {
            patterns.add(EventPattern.from(item));
        }
        return List.copyOf(patterns);
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("expected string array");
        }
        ArrayList<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("expected object map");
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(values);
    }

    private static JsonNode objectOrEmpty(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull()
                ? JsonNodeFactory.instance.objectNode()
                : node;
    }

    private static void requireObject(JsonNode node, String source) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(source + ": expected JSON object");
        }
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String source) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(source + ": unknown expect field: " + field);
            }
        });
    }

    private static String requiredText(JsonNode node, String field, Path source) {
        return requiredText(node, field, source.toString());
    }

    private static String requiredText(JsonNode node, String field, String source) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException(source + ": missing required field: " + field);
        }
        return value.asText().strip();
    }

    private static String truncate(String value, int maxLength) {
        String actual = Objects.toString(value, "");
        return actual.length() <= maxLength ? actual : actual.substring(0, maxLength);
    }
}
