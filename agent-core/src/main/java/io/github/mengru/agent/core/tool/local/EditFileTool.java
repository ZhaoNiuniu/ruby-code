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

public final class EditFileTool implements Tool {

    private final Path workspaceRoot;

    public EditFileTool() {
        this(LocalToolSupport.defaultWorkspaceRoot());
    }

    public EditFileTool(Path workspaceRoot) {
        this.workspaceRoot = LocalToolSupport.normalizeWorkspace(workspaceRoot);
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Replace text in a workspace file exactly once.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = LocalToolSupport.objectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("path", LocalToolSupport.stringProperty("Workspace-relative path to edit."));
        properties.set("oldText", LocalToolSupport.stringProperty("Existing text that must appear exactly once."));
        properties.set("newText", LocalToolSupport.stringProperty("Replacement text."));
        schema.putArray("required").add("path").add("oldText").add("newText");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            Path path = LocalToolSupport.resolveWorkspacePath(workspaceRoot, request.stringArgument("path"));
            if (!Files.isRegularFile(path)) {
                return ToolResult.failure("file does not exist or is not a regular file: " + request.stringArgument("path"));
            }
            String oldText = request.stringArgument("oldText");
            if (oldText.isEmpty()) {
                return ToolResult.failure("oldText must not be empty");
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int occurrences = occurrences(content, oldText);
            if (occurrences != 1) {
                return ToolResult.failure("oldText must match exactly once, but matched " + occurrences + " times");
            }
            String updated = content.replace(oldText, request.stringArgument("newText"));
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            return ToolResult.success("edited " + LocalToolSupport.relativePath(workspaceRoot, path));
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private static int occurrences(String content, String needle) {
        int count = 0;
        int index = 0;
        while (true) {
            int next = content.indexOf(needle, index);
            if (next < 0) {
                return count;
            }
            count++;
            index = next + needle.length();
        }
    }
}
