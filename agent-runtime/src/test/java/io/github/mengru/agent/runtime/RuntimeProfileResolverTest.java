package io.github.mengru.agent.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeProfileResolverTest {

    @TempDir
    Path workspace;

    private final RuntimeProfileResolver resolver = new RuntimeProfileResolver();

    @Test
    void missingProfileDefaultsToImplicitDev() {
        RuntimeProfileResolution resolution = resolver.resolve(workspace, null, RuntimeProfileOverrides.none(), Map.of());

        RuntimeSettings settings = resolution.settings();
        assertThat(settings.profileName()).isEqualTo("dev");
        assertThat(settings.profileSource()).isEqualTo("implicit-dev");
        assertThat(settings.provider()).isEqualTo("echo");
        assertThat(settings.policyName()).isEqualTo("dev");
        assertThat(settings.policySource()).isEqualTo("built-in");
        assertThat(settings.mcpTools()).isTrue();
    }

    @Test
    void resolvesBuiltInReadonly() {
        RuntimeSettings settings = resolver.resolve(workspace, "readonly", RuntimeProfileOverrides.none(), Map.of()).settings();

        assertThat(settings.profileSource()).isEqualTo("built-in");
        assertThat(settings.policyName()).isEqualTo("readonly");
        assertThat(settings.policySource()).isEqualTo("built-in");
        assertThat(settings.subagentTools()).isFalse();
        assertThat(settings.mcpTools()).isFalse();
    }

    @Test
    void projectProfileOverridesBuiltInProfile() throws IOException {
        writeProfile("dev", """
                {
                  "provider": "openai-compatible",
                  "model": "profile-model",
                  "tools": {
                    "mcp": false
                  },
                  "policy": {
                    "mode": "dev"
                  }
                }
                """);

        RuntimeSettings settings = resolver.resolve(workspace, "dev", RuntimeProfileOverrides.none(), Map.of()).settings();

        assertThat(settings.profileSource()).isEqualTo("built-in+project");
        assertThat(settings.provider()).isEqualTo("openai-compatible");
        assertThat(settings.model()).isEqualTo("profile-model");
        assertThat(settings.mcpTools()).isFalse();
        assertThat(settings.policyName()).isEqualTo("dev");
    }

    @Test
    void profileCanReferenceProjectPolicyByName() throws IOException {
        writeProfile("locked", """
                {
                  "provider": "echo",
                  "policy": {
                    "name": "locked"
                  }
                }
                """);
        writePolicy("locked", """
                {
                  "defaultAction": "deny",
                  "tools": {
                    "read_file": "allow"
                  }
                }
                """);

        RuntimeSettings settings = resolver.resolve(workspace, "locked", RuntimeProfileOverrides.none(), Map.of()).settings();

        assertThat(settings.policyName()).isEqualTo("locked");
        assertThat(settings.policySource()).isEqualTo("project");
        assertThat(settings.policyConfig().actionFor("read_file", java.util.Set.of())).isEqualTo(PolicyAction.ALLOW);
    }

    @Test
    void missingCustomPolicyFailsFast() throws IOException {
        writeProfile("locked", """
                {
                  "provider": "echo",
                  "policy": {
                    "name": "missing_policy"
                  }
                }
                """);

        assertThatThrownBy(() -> resolver.resolve(workspace, "locked", RuntimeProfileOverrides.none(), Map.of()))
                .isInstanceOf(RuntimeProfileException.class)
                .hasMessageContaining("Policy not found");
    }

    @Test
    void invalidProjectPolicyFailsFast() throws IOException {
        writeProfile("locked", """
                {
                  "provider": "echo",
                  "policy": {
                    "name": "locked"
                  }
                }
                """);
        writePolicy("locked", """
                {
                  "defaultAction": "explode"
                }
                """);

        assertThatThrownBy(() -> resolver.resolve(workspace, "locked", RuntimeProfileOverrides.none(), Map.of()))
                .isInstanceOf(RuntimeProfileException.class)
                .hasMessageContaining("Unknown policy action");
    }

    @Test
    void policyUnknownFieldsFailFast() throws IOException {
        writeProfile("locked", """
                {
                  "provider": "echo",
                  "policy": {
                    "name": "locked"
                  }
                }
                """);
        writePolicy("locked", """
                {
                  "defaultAction": "ask",
                  "surprise": true
                }
                """);

        assertThatThrownBy(() -> resolver.resolve(workspace, "locked", RuntimeProfileOverrides.none(), Map.of()))
                .isInstanceOf(RuntimeProfileException.class)
                .hasMessageContaining("Unrecognized field");
    }

    @Test
    void cliOverridesProjectProfileAndEnvironmentOnlyFallbacksWhenMissing() throws IOException {
        writeProfile("dev", """
                {
                  "provider": "openai-compatible",
                  "model": "profile-model",
                  "baseUrl": "https://profile.example/v1"
                }
                """);
        RuntimeProfileOverrides overrides = RuntimeProfileOverrides.builder()
                .model("cli-model")
                .build();

        RuntimeSettings settings = resolver.resolve(workspace, "dev", overrides, Map.of(
                "OPENAI_MODEL", "env-model",
                "OPENAI_BASE_URL", "https://env.example/v1"
        )).settings();

        assertThat(settings.model()).isEqualTo("cli-model");
        assertThat(settings.modelSource()).isEqualTo("cli");
        assertThat(settings.baseUrl()).isEqualTo("https://profile.example/v1");
        assertThat(settings.baseUrlSource()).isEqualTo("profile");
    }

    @Test
    void environmentModelAndBaseUrlAreFallbacks() {
        RuntimeProfileOverrides overrides = RuntimeProfileOverrides.builder()
                .provider("openai-compatible")
                .build();

        RuntimeSettings settings = resolver.resolve(workspace, "dev", overrides, Map.of(
                "OPENAI_MODEL", "env-model",
                "OPENAI_BASE_URL", "https://env.example/v1"
        )).settings();

        assertThat(settings.model()).isEqualTo("env-model");
        assertThat(settings.modelSource()).isEqualTo("env:OPENAI_MODEL");
        assertThat(settings.baseUrl()).isEqualTo("https://env.example/v1");
        assertThat(settings.baseUrlSource()).isEqualTo("env:OPENAI_BASE_URL");
    }

    @Test
    void openAiEnvironmentFallbacksDoNotApplyToEchoProvider() {
        RuntimeSettings settings = resolver.resolve(workspace, "dev", RuntimeProfileOverrides.none(), Map.of(
                "OPENAI_MODEL", "env-model",
                "OPENAI_BASE_URL", "https://env.example/v1"
        )).settings();

        assertThat(settings.provider()).isEqualTo("echo");
        assertThat(settings.model()).isNull();
        assertThat(settings.modelSource()).isEmpty();
        assertThat(settings.baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(settings.baseUrlSource()).isEqualTo("default");
    }

    @Test
    void customProfileMustExist() {
        assertThatThrownBy(() -> resolver.resolve(workspace, "team-dev", RuntimeProfileOverrides.none(), Map.of()))
                .isInstanceOf(RuntimeProfileException.class)
                .hasMessageContaining("Runtime profile not found");
    }

    @Test
    void unknownFieldsFailFast() throws IOException {
        writeProfile("bad", """
                {
                  "provider": "echo",
                  "surprise": true
                }
                """);

        assertThatThrownBy(() -> resolver.resolve(workspace, "bad", RuntimeProfileOverrides.none(), Map.of()))
                .isInstanceOf(RuntimeProfileException.class)
                .hasMessageContaining("Unrecognized field");
    }

    @Test
    void mcpConfigMustStayInsideWorkspace() throws IOException {
        writeProfile("bad", """
                {
                  "tools": {
                    "mcpConfig": "../outside.json"
                  }
                }
                """);

        assertThatThrownBy(() -> resolver.resolve(workspace, "bad", RuntimeProfileOverrides.none(), Map.of()))
                .isInstanceOf(RuntimeProfileException.class)
                .hasMessageContaining("MCP config path must stay within the workspace");
    }

    private void writeProfile(String name, String content) throws IOException {
        Path dir = workspace.resolve(".agent").resolve("profiles");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), content, StandardCharsets.UTF_8);
    }

    private void writePolicy(String name, String content) throws IOException {
        Path dir = workspace.resolve(".agent").resolve("policies");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), content, StandardCharsets.UTF_8);
    }
}
