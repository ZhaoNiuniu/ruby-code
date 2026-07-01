package io.github.mengru.agent.core.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WriteFileTool implements Tool {

    private final Path workspaceRoot;

    public WriteFileTool() {
        this(LocalToolSupport.defaultWorkspaceRoot());
    }

    public WriteFileTool(Path workspaceRoot) {
        this.workspaceRoot = LocalToolSupport.normalizeWorkspace(workspaceRoot);
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Write UTF-8 text content to a file within the workspace.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = LocalToolSupport.objectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("path", LocalToolSupport.stringProperty("Workspace-relative path to write."));
        properties.set("content", LocalToolSupport.stringProperty("UTF-8 text content to write."));
        properties.set("overwrite", LocalToolSupport.booleanProperty("Whether to overwrite an existing file. Defaults to true."));
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            Path path = LocalToolSupport.resolveWorkspacePath(workspaceRoot, request.stringArgument("path"));
            boolean overwrite = LocalToolSupport.booleanArgument(request.arguments(), "overwrite", true);
            if (Files.exists(path) && !overwrite) {
                return ToolResult.failure("file already exists and overwrite=false: " + request.stringArgument("path"));
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (overwrite) {
                Files.writeString(path, request.stringArgument("content"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } else {
                Files.writeString(path, request.stringArgument("content"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            return ToolResult.success("wrote " + request.stringArgument("content").length()
                    + " characters to " + LocalToolSupport.relativePath(workspaceRoot, path));
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
