package io.github.mengru.agent.core.skill;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.tool.ToolOutputSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoadSkillToolTest {

    @Test
    void loadsSkillContentByName() {
        SkillCatalog catalog = SkillCatalog.of(List.of(new SkillDefinition(
                "java-agent",
                "Java agent guidance",
                Path.of("skills/java-agent/SKILL.md"),
                "full skill content"
        )));
        LoadSkillTool tool = new LoadSkillTool(catalog);

        ToolResult result = tool.execute(request("java-agent"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("full skill content");
    }

    @Test
    void unknownSkillReturnsToolFailure() {
        LoadSkillTool tool = new LoadSkillTool(SkillCatalog.empty());

        ToolResult result = tool.execute(request("missing"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("unknown skill: missing");
    }

    @Test
    void truncatesLargeSkillContent() {
        String content = "a".repeat(ToolOutputSupport.OUTPUT_LIMIT + 100);
        SkillCatalog catalog = SkillCatalog.of(List.of(new SkillDefinition(
                "large",
                "Large skill",
                Path.of("skills/large/SKILL.md"),
                content
        )));
        LoadSkillTool tool = new LoadSkillTool(catalog);

        ToolResult result = tool.execute(request("large"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("[tool output truncated:");
        assertThat(result.output().length()).isLessThan(content.length());
    }

    private static ToolRequest request(String name) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("name", name);
        return new ToolRequest(LoadSkillTool.NAME, arguments, Map.of());
    }
}
