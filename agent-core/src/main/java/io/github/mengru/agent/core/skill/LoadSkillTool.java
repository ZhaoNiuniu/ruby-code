package io.github.mengru.agent.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.tool.ToolOutputSupport;

import java.util.Objects;

public final class LoadSkillTool implements Tool {

    public static final String NAME = "load_skill";

    private final SkillCatalog skillCatalog;

    public LoadSkillTool(SkillCatalog skillCatalog) {
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Load the full SKILL.md content for a project skill by name. Read-only and limited to the startup skill catalog.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("name")
                .put("type", "string")
                .put("description", "Skill name from the project skill catalog.");
        schema.putArray("required").add("name");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String name = request.stringArgument("name").strip();
        if (name.isBlank()) {
            return ToolResult.failure("name must not be blank");
        }
        return skillCatalog.findByName(name)
                .map(skill -> ToolResult.success(ToolOutputSupport.truncate(skill.content())))
                .orElseGet(() -> ToolResult.failure("unknown skill: " + name));
    }

    public SkillCatalog skillCatalog() {
        return skillCatalog;
    }
}
