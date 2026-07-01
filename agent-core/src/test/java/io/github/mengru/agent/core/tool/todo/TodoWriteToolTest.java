package io.github.mengru.agent.core.tool.todo;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoWriteToolTest {

    private final TodoWriteTool tool = new TodoWriteTool();

    @Test
    void exposesJsonSchemaForTodoList() {
        assertThat(tool.parametersSchema().at("/properties/todos/type").asText()).isEqualTo("array");
        assertThat(tool.parametersSchema().at("/properties/todos/items/properties/status/enum/1").asText())
                .isEqualTo("in_progress");
    }

    @Test
    void acceptsCompleteTodoListAndEchoesNormalizedState() {
        ObjectNode arguments = args();
        arguments.putArray("todos")
                .addObject()
                .put("content", " inspect current code ")
                .put("status", "in_progress");

        ToolResult result = tool.execute(new ToolRequest(TodoWriteTool.NAME, arguments, Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("todo list updated");
        assertThat(result.output()).contains("\"count\" : 1");
        assertThat(result.output()).contains("\"content\" : \"inspect current code\"");
        assertThat(result.output()).contains("\"status\" : \"in_progress\"");
    }

    @Test
    void acceptsEmptyTodoListToClearPlanningState() {
        ObjectNode arguments = args();
        arguments.putArray("todos");

        ToolResult result = tool.execute(new ToolRequest(TodoWriteTool.NAME, arguments, Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("\"count\" : 0");
    }

    @Test
    void rejectsBlankTodoContent() {
        ObjectNode arguments = args();
        arguments.putArray("todos")
                .addObject()
                .put("content", " ")
                .put("status", "pending");

        ToolResult result = tool.execute(new ToolRequest(TodoWriteTool.NAME, arguments, Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("content must be a non-empty string");
    }

    @Test
    void rejectsInvalidStatus() {
        ObjectNode arguments = args();
        arguments.putArray("todos")
                .addObject()
                .put("content", "ship feature")
                .put("status", "blocked");

        ToolResult result = tool.execute(new ToolRequest(TodoWriteTool.NAME, arguments, Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("status must be one of pending, in_progress, completed");
    }

    @Test
    void rejectsMultipleInProgressTodos() {
        ObjectNode arguments = args();
        ArrayNode todos = arguments.putArray("todos");
        todos.addObject()
                .put("content", "first")
                .put("status", "in_progress");
        todos.addObject()
                .put("content", "second")
                .put("status", "in_progress");

        ToolResult result = tool.execute(new ToolRequest(TodoWriteTool.NAME, arguments, Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("at most one todo can be in_progress");
    }

    private static ObjectNode args() {
        return JsonNodeFactory.instance.objectNode();
    }
}
