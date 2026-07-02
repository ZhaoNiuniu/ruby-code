package io.github.mengru.agent.core.tool.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.DefaultAgent;
import io.github.mengru.agent.core.hook.HookRegistry;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.prompt.PromptAssembler;
import io.github.mengru.agent.core.prompt.PromptMode;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.task.TaskManager;
import io.github.mengru.agent.core.tool.ToolRegistry;

import java.util.Map;
import java.util.Objects;

public final class SubagentTool implements Tool {

    public static final String NAME = "subagent";
    public static final String PARENT_USER_INSTRUCTIONS_METADATA_KEY = PromptAssembler.USER_INSTRUCTIONS_METADATA_KEY;
    @Deprecated
    public static final String PARENT_SYSTEM_PROMPT_METADATA_KEY = PARENT_USER_INSTRUCTIONS_METADATA_KEY;

    static final int DEFAULT_MAX_STEPS = 6;
    static final int MAX_STEPS_LIMIT = 12;

    private final ModelClient modelClient;
    private final UserApprover userApprover;
    private final SkillCatalog skillCatalog;
    private final MemoryCatalog memoryCatalog;
    private final TaskManager taskManager;

    public SubagentTool(ModelClient modelClient, UserApprover userApprover) {
        this(modelClient, userApprover, SkillCatalog.empty());
    }

    public SubagentTool(ModelClient modelClient, UserApprover userApprover, SkillCatalog skillCatalog) {
        this(modelClient, userApprover, skillCatalog, MemoryCatalog.empty(java.nio.file.Path.of("")));
    }

    public SubagentTool(ModelClient modelClient, UserApprover userApprover, SkillCatalog skillCatalog, MemoryCatalog memoryCatalog) {
        this(modelClient, userApprover, skillCatalog, memoryCatalog, TaskManager.defaultManager());
    }

    public SubagentTool(
            ModelClient modelClient,
            UserApprover userApprover,
            SkillCatalog skillCatalog,
            MemoryCatalog memoryCatalog,
            TaskManager taskManager
    ) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.userApprover = Objects.requireNonNull(userApprover, "userApprover must not be null");
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog must not be null");
        this.memoryCatalog = Objects.requireNonNull(memoryCatalog, "memoryCatalog must not be null");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Start an isolated investigation subagent. The subagent returns only its final report and cannot spawn another subagent.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("description")
                .put("type", "string")
                .put("description", "Focused investigation task for the subagent to complete.");
        properties.putObject("expectedOutput")
                .put("type", "string")
                .put("description", "Optional guidance for what the final report should contain.");
        ObjectNode maxSteps = properties.putObject("maxSteps");
        maxSteps.put("type", "integer");
        maxSteps.put("description", "Maximum subagent loop steps. Defaults to 6 and is capped at 12.");
        maxSteps.put("minimum", 1);
        maxSteps.put("maximum", MAX_STEPS_LIMIT);

        schema.putArray("required").add("description");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String description = request.stringArgument("description").strip();
        if (description.isBlank()) {
            return ToolResult.failure("description must not be blank");
        }

        int maxSteps;
        try {
            maxSteps = resolveMaxSteps(request.arguments());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
        ToolRegistry childTools = ToolRegistry.investigationTools(skillCatalog, taskManager);
        DefaultAgent childAgent = new DefaultAgent(
                modelClient,
                childTools,
                HookRegistry.defaultsFor(childTools, userApprover, memoryCatalog, PromptMode.SUBAGENT)
        );
        AgentResult result = childAgent.run(new AgentRequest(
                description,
                maxSteps,
                childMetadata(request),
                parentUserInstructions(request)
        ));
        if (!result.completed()) {
            return ToolResult.failure("subagent did not complete: " + result.output());
        }
        return ToolResult.success(result.output());
    }

    private int resolveMaxSteps(JsonNode arguments) {
        JsonNode value = arguments.get("maxSteps");
        if (value == null || value.isNull()) {
            return DEFAULT_MAX_STEPS;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException("maxSteps must be an integer");
        }
        int requested = value.asInt();
        if (requested < 1) {
            throw new IllegalArgumentException("maxSteps must be greater than zero");
        }
        return Math.min(requested, MAX_STEPS_LIMIT);
    }

    private Map<String, String> childMetadata(ToolRequest request) {
        String expectedOutput = request.stringArgument("expectedOutput").strip();
        if (expectedOutput.isBlank()) {
            return Map.of();
        }
        return Map.of(PromptAssembler.SUBAGENT_EXPECTED_OUTPUT_METADATA_KEY, expectedOutput);
    }

    private String parentUserInstructions(ToolRequest request) {
        return request.metadata()
                .getOrDefault(PARENT_USER_INSTRUCTIONS_METADATA_KEY, "")
                .strip();
    }
}
