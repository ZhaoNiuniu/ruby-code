package io.github.mengru.agent.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RuntimeProfileLoader {

    public static final Path PROFILE_DIR = Path.of(".agent", "profiles");

    private final ObjectMapper objectMapper;

    public RuntimeProfileLoader() {
        this(new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true));
    }

    RuntimeProfileLoader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public Path profilePath(Path workspaceRoot, String profileName) {
        return workspaceRoot.toAbsolutePath().normalize()
                .resolve(PROFILE_DIR)
                .resolve(profileName + ".json")
                .normalize();
    }

    public AgentRuntimeProfile load(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        try {
            return objectMapper.readValue(Files.readString(path), AgentRuntimeProfile.class);
        } catch (IOException e) {
            throw new RuntimeProfileException("Failed to read runtime profile " + path + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeProfileException("Invalid runtime profile " + path + ": " + e.getMessage(), e);
        }
    }
}
