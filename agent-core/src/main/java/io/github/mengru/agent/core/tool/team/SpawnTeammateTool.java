package io.github.mengru.agent.core.tool.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.team.TeamRuntime;
import io.github.mengru.agent.core.team.TeammateView;

import java.util.Objects;

public final class SpawnTeammateTool implements Tool {

    public static final String NAME = "spawn_teammate";

    private final TeamRuntime runtime;

    public SpawnTeammateTool(TeamRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Start a persistent teammate thread in agent chat and deliver its initial task automatically.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = TeamToolSupport.baseSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("name", TeamToolSupport.stringProperty("Optional teammate name. Uses teammate_1 style if omitted."));
        properties.set("role", TeamToolSupport.stringProperty("Teammate role, for example backend-investigator."));
        properties.set("task", TeamToolSupport.stringProperty("Initial task assignment for the teammate. It is delivered automatically when the teammate is spawned."));
        properties.set("instructions", TeamToolSupport.stringProperty("Optional extra instructions for the teammate."));
        schema.putArray("required").add("role").add("task");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            TeammateView teammate = runtime.spawn(
                    request.stringArgument("name"),
                    request.stringArgument("role"),
                    request.stringArgument("task"),
                    request.stringArgument("instructions")
            );
            ObjectNode output = JsonNodeFactory.instance.objectNode();
            output.put("message", "teammate spawned");
            output.put("teamId", runtime.teamId());
            output.put("name", teammate.name());
            output.put("role", teammate.role());
            output.put("status", teammate.status().name().toLowerCase(java.util.Locale.ROOT));
            output.put("initialTaskDelivered", true);
            return ToolResult.success(output.toPrettyString());
        } catch (RuntimeException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
