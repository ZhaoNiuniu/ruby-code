package io.github.mengru.agent.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class PolicyLoader {

    public static final Path POLICY_DIR = Path.of(".agent", "policies");

    private final ObjectMapper objectMapper;

    public PolicyLoader() {
        this(new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true));
    }

    PolicyLoader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public Path policyPath(Path workspaceRoot, String policyName) {
        return workspaceRoot.toAbsolutePath().normalize()
                .resolve(POLICY_DIR)
                .resolve(policyName + ".json")
                .normalize();
    }

    public PolicyConfig load(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        try {
            return objectMapper.readValue(Files.readString(path), PolicyConfig.class);
        } catch (IOException e) {
            throw new RuntimeProfileException("Failed to read policy " + path + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeProfileException("Invalid policy " + path + ": " + e.getMessage(), e);
        }
    }
}
