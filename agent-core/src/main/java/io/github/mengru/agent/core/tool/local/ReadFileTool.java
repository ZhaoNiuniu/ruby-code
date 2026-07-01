package io.github.mengru.agent.core.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReadFileTool implements Tool {

    private final Path workspaceRoot;

    public ReadFileTool() {
        this(LocalToolSupport.defaultWorkspaceRoot());
    }

    public ReadFileTool(Path workspaceRoot) {
        this.workspaceRoot = LocalToolSupport.normalizeWorkspace(workspaceRoot);
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read UTF-8 text file contents from within the workspace.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = LocalToolSupport.objectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("path", LocalToolSupport.stringProperty("Workspace-relative path to read."));
        properties.set("startLine", LocalToolSupport.integerProperty("1-based line number to start reading from.", 1, Integer.MAX_VALUE));
        properties.set("maxLines", LocalToolSupport.integerProperty("Maximum number of lines to return.", 1, Integer.MAX_VALUE));
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            Path path = LocalToolSupport.resolveWorkspacePath(workspaceRoot, request.stringArgument("path"));
            if (!Files.isRegularFile(path)) {
                return ToolResult.failure("file does not exist or is not a regular file: " + request.stringArgument("path"));
            }

            int startLine = LocalToolSupport.intArgument(request.arguments(), "startLine", 1);
            int maxLines = LocalToolSupport.intArgument(request.arguments(), "maxLines", Integer.MAX_VALUE);
            if (startLine < 1) {
                return ToolResult.failure("startLine must be >= 1");
            }
            if (maxLines < 1) {
                return ToolResult.failure("maxLines must be >= 1");
            }

            List<String> lines = Files.readAllLines(path);
            if (startLine > lines.size()) {
                return ToolResult.success("");
            }
            int from = startLine - 1;
            int to = Math.min(lines.size(), from + maxLines);
            return ToolResult.success(LocalToolSupport.truncate(String.join(System.lineSeparator(), lines.subList(from, to))));
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
