package io.github.mengru.agent.core.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.core.tool.ToolOutputSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

final class LocalToolSupport {

    private LocalToolSupport() {
    }

    static Path defaultWorkspaceRoot() {
        return Path.of("").toAbsolutePath().normalize();
    }

    static Path normalizeWorkspace(Path workspaceRoot) {
        return Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
                .toAbsolutePath()
                .normalize();
    }

    static Path resolveWorkspacePath(Path workspaceRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Path root = normalizeWorkspace(workspaceRoot);
        Path candidate = Path.of(rawPath);
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + rawPath);
        }
        ensureNoSymlinkEscape(root, resolved, rawPath);
        return resolved;
    }

    static String relativePath(Path workspaceRoot, Path path) {
        return normalizeWorkspace(workspaceRoot).relativize(path.toAbsolutePath().normalize()).toString();
    }

    static String truncate(String value) {
        return ToolOutputSupport.truncate(value);
    }

    static ObjectNode objectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.set("properties", JsonNodeFactory.instance.objectNode());
        schema.put("additionalProperties", false);
        return schema;
    }

    static ObjectNode stringProperty(String description) {
        ObjectNode property = JsonNodeFactory.instance.objectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    static ObjectNode integerProperty(String description, int minimum, int maximum) {
        ObjectNode property = JsonNodeFactory.instance.objectNode();
        property.put("type", "integer");
        property.put("description", description);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        return property;
    }

    static ObjectNode booleanProperty(String description) {
        ObjectNode property = JsonNodeFactory.instance.objectNode();
        property.put("type", "boolean");
        property.put("description", description);
        return property;
    }

    static int intArgument(JsonNode arguments, String name, int defaultValue) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asInt(defaultValue);
    }

    static boolean booleanArgument(JsonNode arguments, String name, boolean defaultValue) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asBoolean(defaultValue);
    }

    private static void ensureNoSymlinkEscape(Path root, Path resolved, String rawPath) {
        try {
            Path rootReal = root.toRealPath();
            Path nearestExisting = resolved;
            while (nearestExisting != null && !Files.exists(nearestExisting, LinkOption.NOFOLLOW_LINKS)) {
                nearestExisting = nearestExisting.getParent();
            }
            if (nearestExisting == null) {
                throw new IllegalArgumentException("path has no existing parent: " + rawPath);
            }
            Path real = nearestExisting.toRealPath();
            if (!real.startsWith(rootReal)) {
                throw new IllegalArgumentException("path escapes workspace through a symbolic link: " + rawPath);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to validate workspace path: " + rawPath + ": " + e.getMessage(), e);
        }
    }
}
