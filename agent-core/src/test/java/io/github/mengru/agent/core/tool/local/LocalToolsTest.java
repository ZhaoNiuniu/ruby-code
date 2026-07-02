package io.github.mengru.agent.core.tool.local;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.api.ToolRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalToolsTest {

    @TempDir
    Path workspace;

    @Test
    void writeReadAndEditFileWithinWorkspace() {
        WriteFileTool writer = new WriteFileTool(workspace);
        ReadFileTool reader = new ReadFileTool(workspace);
        EditFileTool editor = new EditFileTool(workspace);

        ToolResult writeResult = writer.execute(request("write_file", args()
                .put("path", "notes/todo.txt")
                .put("content", "hello agent")));

        assertThat(writeResult.success()).isTrue();

        ToolResult readResult = reader.execute(request("read_file", args()
                .put("path", "notes/todo.txt")));

        assertThat(readResult.success()).isTrue();
        assertThat(readResult.output()).isEqualTo("hello agent");

        ToolResult editResult = editor.execute(request("edit_file", args()
                .put("path", "notes/todo.txt")
                .put("oldText", "agent")
                .put("newText", "tools")));

        assertThat(editResult.success()).isTrue();
        assertThat(reader.execute(request("read_file", args().put("path", "notes/todo.txt"))).output())
                .isEqualTo("hello tools");
    }

    @Test
    void pathToolsRejectEscapingWorkspace() {
        WriteFileTool writer = new WriteFileTool(workspace);
        ReadFileTool reader = new ReadFileTool(workspace);

        assertThat(writer.execute(request("write_file", args()
                .put("path", "../outside.txt")
                .put("content", "nope"))).success()).isFalse();
        assertThat(reader.execute(request("read_file", args()
                .put("path", workspace.getParent().resolve("outside.txt").toString()))).success()).isFalse();
    }

    @Test
    void pathToolsRejectSymlinkEscapingWorkspace() throws Exception {
        Path outside = Files.createTempDirectory(workspace.getParent(), "outside");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(workspace.resolve("secret-link.txt"), outside.resolve("secret.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symbolic links are not available: " + e.getMessage());
        }

        ToolResult result = new ReadFileTool(workspace).execute(request("read_file", args()
                .put("path", "secret-link.txt")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("symbolic link");
    }

    @Test
    void editFileRequiresExactlyOneMatch() {
        WriteFileTool writer = new WriteFileTool(workspace);
        EditFileTool editor = new EditFileTool(workspace);
        writer.execute(request("write_file", args()
                .put("path", "repeat.txt")
                .put("content", "same same")));

        ToolResult result = editor.execute(request("edit_file", args()
                .put("path", "repeat.txt")
                .put("oldText", "same")
                .put("newText", "once")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("matched 2 times");
    }

    @Test
    void globReturnsWorkspaceRelativeMatches() {
        WriteFileTool writer = new WriteFileTool(workspace);
        writer.execute(request("write_file", args().put("path", "src/Main.java").put("content", "class Main {}")));
        writer.execute(request("write_file", args().put("path", "README.md").put("content", "# test")));

        ToolResult result = new GlobTool(workspace).execute(request("glob", args().put("pattern", "**/*.java")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("src/Main.java");
    }

    @Test
    void toolOutputIsTruncated() {
        WriteFileTool writer = new WriteFileTool(workspace);
        ReadFileTool reader = new ReadFileTool(workspace);
        writer.execute(request("write_file", args()
                .put("path", "large.txt")
                .put("content", "x".repeat(21_000))));

        ToolResult result = reader.execute(request("read_file", args().put("path", "large.txt")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("[tool output truncated:");
        assertThat(result.output().length()).isLessThan(20_500);
    }

    @Test
    void bashReturnsStdoutAndExitCode() {
        ToolResult result = new BashTool(workspace).execute(request("bash", args()
                .put("command", "printf hello")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("exitCode: 0");
        assertThat(result.output()).contains("hello");
    }

    @Test
    void bashSchemaIncludesBackgroundFlag() {
        assertThat(new BashTool(workspace).parametersSchema()
                .at("/properties/run_in_background/type")
                .asText()).isEqualTo("boolean");
    }

    @Test
    void bashNonZeroExitIsToolFailureWithOutput() {
        ToolResult result = new BashTool(workspace).execute(request("bash", args()
                .put("command", "printf bad >&2; exit 7")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exitCode: 7");
        assertThat(result.error()).contains("bad");
    }

    @Test
    void bashRejectsTimeoutInteractiveAndDangerousCommands() {
        BashTool bash = new BashTool(workspace);

        assertThat(bash.execute(request("bash", args()
                .put("command", "sleep 2")
                .put("timeoutSeconds", 1))).success()).isFalse();
        assertThat(bash.execute(request("bash", args()
                .put("command", "read name"))).error()).contains("interactive");
        assertThat(bash.execute(request("bash", args()
                .put("command", "rm -rf target"))).error()).contains("safety guard");
    }

    private static ToolRequest request(String toolName, ObjectNode arguments) {
        return new ToolRequest(toolName, arguments, Map.of());
    }

    private static ObjectNode args() {
        return JsonNodeFactory.instance.objectNode();
    }
}
