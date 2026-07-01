package io.github.mengru.agent.core.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class GlobTool implements Tool {

    private final Path workspaceRoot;

    public GlobTool() {
        this(LocalToolSupport.defaultWorkspaceRoot());
    }

    public GlobTool(Path workspaceRoot) {
        this.workspaceRoot = LocalToolSupport.normalizeWorkspace(workspaceRoot);
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Find files in the workspace by a glob pattern.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = LocalToolSupport.objectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("pattern", LocalToolSupport.stringProperty("Glob pattern, such as **/*.java or pom.xml."));
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String pattern = request.stringArgument("pattern");
        if (pattern.isBlank()) {
            return ToolResult.failure("pattern must not be blank");
        }
        if (Path.of(pattern).isAbsolute() || pattern.contains("..")) {
            return ToolResult.failure("pattern must stay within the workspace");
        }

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("invalid glob pattern: " + e.getMessage());
        }

        try (Stream<Path> stream = Files.walk(workspaceRoot)) {
            List<String> matches = stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> workspaceRoot.relativize(path))
                    .filter(relative -> matcher.matches(relative)
                            || (relative.getFileName() != null && matcher.matches(relative.getFileName())))
                    .map(Path::toString)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            return ToolResult.success(LocalToolSupport.truncate(String.join(System.lineSeparator(), matches)));
        } catch (IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
