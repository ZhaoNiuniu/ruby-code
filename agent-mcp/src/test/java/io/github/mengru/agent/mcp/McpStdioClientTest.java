package io.github.mengru.agent.mcp;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpStdioClientTest {

    @Test
    void listsToolsWithPaginationAndCallsTool() {
        try (McpStdioClient client = client("normal")) {
            List<McpToolDefinition> tools = client.listTools();

            assertThat(tools).extracting(McpToolDefinition::name)
                    .containsExactly("echo", "fail", "second");

            McpToolAdapter adapter = new McpToolAdapter("demo", client, tools.get(0));
            ToolResult result = adapter.execute(new ToolRequest(
                    adapter.name(),
                    JsonNodeFactory.instance.objectNode().put("text", "hello mcp"),
                    Map.of()
            ));

            assertThat(adapter.name()).isEqualTo("mcp__demo__echo");
            assertThat(adapter.parametersSchema().path("type").asText()).isEqualTo("object");
            assertThat(result.success()).isTrue();
            assertThat(result.output()).contains("hello mcp");
            assertThat(result.output()).contains("structuredContent");
        }
    }

    @Test
    void toolExecutionErrorBecomesFailure() {
        try (McpStdioClient client = client("normal")) {
            McpToolDefinition failTool = client.listTools().stream()
                    .filter(tool -> "fail".equals(tool.name()))
                    .findFirst()
                    .orElseThrow();
            McpToolAdapter adapter = new McpToolAdapter("demo", client, failTool);

            ToolResult result = adapter.execute(new ToolRequest(
                    adapter.name(),
                    JsonNodeFactory.instance.objectNode().put("text", "bad"),
                    Map.of()
            ));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("bad");
        }
    }

    @Test
    void jsonRpcErrorIsReportedClearly() {
        try (McpStdioClient client = client("jsonrpc_error")) {
            McpToolDefinition tool = client.listTools().get(0);
            McpToolAdapter adapter = new McpToolAdapter("demo", client, tool);

            ToolResult result = adapter.execute(new ToolRequest(adapter.name(), JsonNodeFactory.instance.objectNode(), Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("JSON-RPC error");
        }
    }

    @Test
    void malformedJsonFailsPendingRequest() {
        McpStdioClient client = newClient("malformed");
        assertThatThrownBy(client::start)
                .isInstanceOf(McpException.class)
                .hasMessageContaining("malformed JSON");
        client.close();
    }

    private static McpStdioClient client(String mode) {
        McpStdioClient client = newClient(mode);
        client.start();
        return client;
    }

    private static McpStdioClient newClient(String mode) {
        McpStdioClient client = new McpStdioClient(new McpServerConfig(
                "demo",
                javaBin(),
                List.of("-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName(), mode),
                Map.of()
        ), Duration.ofSeconds(5));
        return client;
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
