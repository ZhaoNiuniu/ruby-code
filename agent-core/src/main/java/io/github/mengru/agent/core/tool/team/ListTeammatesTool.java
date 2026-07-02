package io.github.mengru.agent.core.tool.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.team.TeamRuntime;
import io.github.mengru.agent.core.team.TeammateView;

import java.util.Objects;

public final class ListTeammatesTool implements Tool {

    public static final String NAME = "list_teammates";

    private final TeamRuntime runtime;

    public ListTeammatesTool(TeamRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "List active teammate threads in the current agent chat team.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = TeamToolSupport.baseSchema();
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        ArrayNode teammates = JsonNodeFactory.instance.arrayNode();
        for (TeammateView teammate : runtime.listTeammates()) {
            ObjectNode node = teammates.addObject();
            node.put("name", teammate.name());
            node.put("role", teammate.role());
            node.put("status", teammate.status().name().toLowerCase(java.util.Locale.ROOT));
            node.put("startedAt", teammate.startedAt().toString());
            node.put("lastMessage", teammate.lastMessage());
        }
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("teamId", runtime.teamId());
        output.put("lead", runtime.leadName());
        output.put("count", teammates.size());
        output.set("teammates", teammates);
        return ToolResult.success(output.toPrettyString());
    }
}
