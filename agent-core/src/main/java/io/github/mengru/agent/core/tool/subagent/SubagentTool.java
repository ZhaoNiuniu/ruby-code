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
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.tool.ToolRegistry;

import java.util.Map;
import java.util.Objects;

public final class SubagentTool implements Tool {

    public static final String NAME = "subagent";
    public static final String PARENT_SYSTEM_PROMPT_METADATA_KEY = "agent.systemPrompt";

    static final int DEFAULT_MAX_STEPS = 6;
    static final int MAX_STEPS_LIMIT = 12;

    private static final String SUB_SYSTEM = """
            You are a focused investigation subagent running in an isolated context.
            Complete the delegated investigation directly and do not delegate it again.
            You may use only the tools provided to you. You must not modify files.
            Return only a concise final report with exactly these sections:
            Summary:
            Evidence:
            Recommended next step:
            """.strip();

    private final ModelClient modelClient;
    private final UserApprover userApprover;
    private final SkillCatalog skillCatalog;

    public SubagentTool(ModelClient modelClient, UserApprover userApprover) {
        this(modelClient, userApprover, SkillCatalog.empty());
    }

    public SubagentTool(ModelClient modelClient, UserApprover userApprover, SkillCatalog skillCatalog) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.userApprover = Objects.requireNonNull(userApprover, "userApprover must not be null");
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog must not be null");
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
        String systemPrompt = buildSystemPrompt(request);
        ToolRegistry childTools = ToolRegistry.investigationTools(skillCatalog);
        DefaultAgent childAgent = new DefaultAgent(
                modelClient,
                childTools,
                HookRegistry.defaultsFor(childTools, userApprover)
        );
        AgentResult result = childAgent.run(new AgentRequest(description, maxSteps, Map.of(), systemPrompt));
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

    private String buildSystemPrompt(ToolRequest request) {
        StringBuilder prompt = new StringBuilder(SUB_SYSTEM);
        String expectedOutput = request.stringArgument("expectedOutput").strip();
        if (!expectedOutput.isBlank()) {
            prompt.append("\n\nExpected output:\n").append(expectedOutput);
        }
        String parentSystemPrompt = request.metadata()
                .getOrDefault(PARENT_SYSTEM_PROMPT_METADATA_KEY, "")
                .strip();
        if (!parentSystemPrompt.isBlank()) {
            prompt.append("\n\nParent agent system prompt:\n").append(parentSystemPrompt);
        }
        return prompt.toString();
    }
}
