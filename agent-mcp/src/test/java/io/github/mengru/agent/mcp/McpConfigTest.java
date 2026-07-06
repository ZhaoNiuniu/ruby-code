package io.github.mengru.agent.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpConfigTest {

    @TempDir
    Path workspace;

    @Test
    void missingConfigReturnsEmptyCatalog() {
        McpConfig config = McpConfig.loadDefault(workspace);

        assertThat(config.isEmpty()).isTrue();
        assertThat(config.servers()).isEmpty();
    }

    @Test
    void parsesClaudeStyleMcpServersConfig() throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "demo_server": {
                      "command": "node",
                      "args": ["server.js", "--flag"],
                      "env": {
                        "TOKEN": "secret"
                      }
                    }
                  }
                }
                """);

        McpConfig config = McpConfig.loadDefault(workspace);

        assertThat(config.servers()).hasSize(1);
        McpServerConfig server = config.servers().get(0);
        assertThat(server.name()).isEqualTo("demo_server");
        assertThat(server.command()).isEqualTo("node");
        assertThat(server.args()).containsExactly("server.js", "--flag");
        assertThat(server.env()).containsEntry("TOKEN", "secret");
    }

    @Test
    void invalidJsonFailsFast() throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"), "{bad");

        assertThatThrownBy(() -> McpConfig.loadDefault(workspace))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("Failed to read MCP config");
    }

    @Test
    void invalidServerNameFailsFast() throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "bad.name": {
                      "command": "node"
                    }
                  }
                }
                """);

        assertThatThrownBy(() -> McpConfig.loadDefault(workspace))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("MCP server name must match");
    }

    @Test
    void blankCommandFailsFast() throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "demo": {
                      "command": ""
                    }
                  }
                }
                """);

        assertThatThrownBy(() -> McpConfig.loadDefault(workspace))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("command must not be blank");
    }

    @Test
    void configPathMustStayInsideWorkspace() {
        assertThatThrownBy(() -> McpConfig.load(workspace, workspace.resolve("..").resolve("outside.json")))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("must stay within the workspace");
    }
}
