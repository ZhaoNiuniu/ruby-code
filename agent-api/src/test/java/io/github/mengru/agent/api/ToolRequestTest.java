package io.github.mengru.agent.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRequestTest {

    @Test
    void carriesStructuredArguments() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("input", "hello");

        ToolRequest request = new ToolRequest("echo", arguments, Map.of());

        assertThat(request.arguments()).isSameAs(arguments);
        assertThat(request.stringArgument("input")).isEqualTo("hello");
    }

    @Test
    void keepsStringInputConstructorCompatible() {
        ToolRequest request = new ToolRequest("echo", "hello", Map.of());

        assertThat(request.arguments().get("input").asText()).isEqualTo("hello");
    }
}
