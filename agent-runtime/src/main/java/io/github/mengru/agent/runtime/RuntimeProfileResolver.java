package io.github.mengru.agent.runtime;

import io.github.mengru.agent.provider.openai.OpenAiCompatibleModelClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RuntimeProfileResolver {

    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final RuntimeProfileLoader loader;
    private final PolicyLoader policyLoader;

    public RuntimeProfileResolver() {
        this(new RuntimeProfileLoader(), new PolicyLoader());
    }

    RuntimeProfileResolver(RuntimeProfileLoader loader) {
        this(loader, new PolicyLoader());
    }

    RuntimeProfileResolver(RuntimeProfileLoader loader, PolicyLoader policyLoader) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
        this.policyLoader = Objects.requireNonNull(policyLoader, "policyLoader must not be null");
    }

    public RuntimeProfileResolution resolve(
            Path workspaceRoot,
            String requestedProfile,
            RuntimeProfileOverrides overrides,
            Map<String, String> environment
    ) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Path workspace = workspaceRoot.toAbsolutePath().normalize();
        RuntimeProfileOverrides actualOverrides = overrides == null ? RuntimeProfileOverrides.none() : overrides;

        boolean implicit = requestedProfile == null || requestedProfile.isBlank();
        String profileName = implicit ? BuiltInRuntimeProfiles.DEV : requestedProfile.strip();
        validateProfileName(profileName);

        ArrayList<String> sources = new ArrayList<>();
        AgentRuntimeProfile merged = BuiltInRuntimeProfiles.defaults();
        sources.add("built-in defaults");

        if (BuiltInRuntimeProfiles.isBuiltIn(profileName)) {
            merged = merged.merge(BuiltInRuntimeProfiles.profile(profileName));
            sources.add("built-in profile:" + profileName);
        }

        Path projectProfile = loader.profilePath(workspace, profileName);
        boolean projectProfileExists = Files.exists(projectProfile);
        if (projectProfileExists) {
            merged = merged.merge(loader.load(projectProfile));
            sources.add("project profile:" + projectProfile);
        } else if (!BuiltInRuntimeProfiles.isBuiltIn(profileName)) {
            throw new RuntimeProfileException("Runtime profile not found: " + projectProfile);
        }

        merged = merged.merge(actualOverrides.toProfile());
        sources.add("CLI overrides");

        RuntimeSettings settings = toSettings(
                workspace,
                profileName,
                implicit ? "implicit-dev" : profileSource(projectProfileExists, profileName),
                merged,
                actualOverrides,
                environment
        );
        return new RuntimeProfileResolution(settings, List.copyOf(sources));
    }

    private RuntimeSettings toSettings(
            Path workspaceRoot,
            String profileName,
            String profileSource,
            AgentRuntimeProfile profile,
            RuntimeProfileOverrides overrides,
            Map<String, String> environment
    ) {
        String provider = requireNonBlank(profile.provider(), "provider");
        String providerSource = overrides.provider() == null || overrides.provider().isBlank() ? "profile" : "cli";
        boolean openAiCompatible = "openai-compatible".equals(provider);
        String model = openAiCompatible ? firstNonBlank(profile.model(), environment.get("OPENAI_MODEL")) : blankToNull(profile.model());
        String modelSource = openAiCompatible
                ? sourceFor(profile.model(), overrides.model(), environment.get("OPENAI_MODEL"), "profile", "cli", "env:OPENAI_MODEL")
                : sourceFor(profile.model(), overrides.model(), null, "profile", "cli", "");
        String baseUrl = openAiCompatible
                ? firstNonBlank(profile.baseUrl(), environment.get("OPENAI_BASE_URL"), OpenAiCompatibleModelClient.DEFAULT_BASE_URL)
                : firstNonBlank(profile.baseUrl(), OpenAiCompatibleModelClient.DEFAULT_BASE_URL);
        String baseUrlSource = openAiCompatible
                ? sourceFor(profile.baseUrl(), overrides.baseUrl(), environment.get("OPENAI_BASE_URL"), "profile", "cli", "env:OPENAI_BASE_URL")
                : sourceFor(profile.baseUrl(), overrides.baseUrl(), null, "profile", "cli", "default");
        if (OpenAiCompatibleModelClient.DEFAULT_BASE_URL.equals(baseUrl) && isBlank(profile.baseUrl()) && isBlank(overrides.baseUrl()) && (!openAiCompatible || isBlank(environment.get("OPENAI_BASE_URL")))) {
            baseUrlSource = "default";
        }

        String policyName = requirePolicyName(profile.policy().effectiveName());
        PolicyResolution policyResolution = resolvePolicy(workspaceRoot, policyName);
        Path mcpConfigPath = resolveWorkspacePath(workspaceRoot, profile.tools().mcpConfig());
        validatePositive(profile.contextCompression().contextWindowTokens(), "contextCompression.contextWindowTokens");
        validatePositive(profile.contextCompression().maxOutputTokens(), "contextCompression.maxOutputTokens");
        validatePositive(profile.contextCompression().reservedTokens(), "contextCompression.reservedTokens");
        validateNonNegative(profile.errorRecovery().modelRetryAttempts(), "errorRecovery.modelRetryAttempts");
        validatePositive(profile.errorRecovery().generationMaxOutputTokens(), "errorRecovery.generationMaxOutputTokens");
        validatePositive(profile.errorRecovery().recoveryMaxOutputTokens(), "errorRecovery.recoveryMaxOutputTokens");
        validateTraceSink(profile.trace().sink());

        return new RuntimeSettings(
                profileName,
                profileSource,
                provider,
                providerSource,
                model,
                modelSource,
                baseUrl,
                baseUrlSource,
                profile.system(),
                bool(profile.tools().local(), "tools.local"),
                bool(profile.tools().subagent(), "tools.subagent"),
                bool(profile.tools().team(), "tools.team"),
                bool(profile.tools().scheduler(), "tools.scheduler"),
                bool(profile.tools().mcp(), "tools.mcp"),
                mcpConfigPath,
                bool(profile.memory().persistent(), "memory.persistent"),
                bool(profile.memory().session(), "memory.session"),
                bool(profile.contextCompression().enabled(), "contextCompression.enabled"),
                requireInt(profile.contextCompression().contextWindowTokens(), "contextCompression.contextWindowTokens"),
                requireInt(profile.contextCompression().maxOutputTokens(), "contextCompression.maxOutputTokens"),
                requireInt(profile.contextCompression().reservedTokens(), "contextCompression.reservedTokens"),
                bool(profile.errorRecovery().enabled(), "errorRecovery.enabled"),
                requireInt(profile.errorRecovery().modelRetryAttempts(), "errorRecovery.modelRetryAttempts"),
                requireInt(profile.errorRecovery().generationMaxOutputTokens(), "errorRecovery.generationMaxOutputTokens"),
                requireInt(profile.errorRecovery().recoveryMaxOutputTokens(), "errorRecovery.recoveryMaxOutputTokens"),
                bool(profile.trace().enabled(), "trace.enabled"),
                requireNonBlank(profile.trace().sink(), "trace.sink"),
                policyName,
                policyResolution.source(),
                policyResolution.policy()
        );
    }

    private PolicyResolution resolvePolicy(Path workspaceRoot, String policyName) {
        Path projectPolicy = policyLoader.policyPath(workspaceRoot, policyName);
        boolean projectPolicyExists = Files.exists(projectPolicy);
        if (BuiltInPolicies.isBuiltIn(policyName)) {
            if (projectPolicyExists) {
                return new PolicyResolution(policyLoader.load(projectPolicy), "built-in+project");
            }
            return new PolicyResolution(BuiltInPolicies.policy(policyName), "built-in");
        }
        if (!projectPolicyExists) {
            throw new RuntimeProfileException("Policy not found: " + projectPolicy);
        }
        return new PolicyResolution(policyLoader.load(projectPolicy), "project");
    }

    private static String profileSource(boolean projectProfileExists, String profileName) {
        if (projectProfileExists && BuiltInRuntimeProfiles.isBuiltIn(profileName)) {
            return "built-in+project";
        }
        if (projectProfileExists) {
            return "project";
        }
        return "built-in";
    }

    private static void validateProfileName(String name) {
        if (!PROFILE_NAME.matcher(name).matches()) {
            throw new RuntimeProfileException("Runtime profile name must match [A-Za-z0-9_-]+: " + name);
        }
    }

    private static String requirePolicyName(String name) {
        if (name == null || name.isBlank()) {
            throw new RuntimeProfileException("Policy name must not be blank");
        }
        String normalized = name.strip();
        if (!PROFILE_NAME.matcher(normalized).matches()) {
            throw new RuntimeProfileException("Policy name must match [A-Za-z0-9_-]+: " + normalized);
        }
        return normalized;
    }

    private static Path resolveWorkspacePath(Path workspaceRoot, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path candidate = Path.of(value);
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : workspaceRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new RuntimeProfileException("MCP config path must stay within the workspace: " + value);
        }
        return resolved;
    }

    private static String sourceFor(String profileValue, String cliValue, String environmentValue, String profileSource, String cliSource, String environmentSource) {
        if (!isBlank(cliValue)) {
            return cliSource;
        }
        if (!isBlank(profileValue)) {
            return profileSource;
        }
        if (!isBlank(environmentValue)) {
            return environmentSource;
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.strip();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new RuntimeProfileException("Runtime profile field must not be blank: " + field);
        }
        return value.strip();
    }

    private static boolean bool(Boolean value, String field) {
        if (value == null) {
            throw new RuntimeProfileException("Runtime profile field is required: " + field);
        }
        return value;
    }

    private static int requireInt(Integer value, String field) {
        if (value == null) {
            throw new RuntimeProfileException("Runtime profile field is required: " + field);
        }
        return value;
    }

    private static void validatePositive(Integer value, String field) {
        if (value != null && value <= 0) {
            throw new RuntimeProfileException("Runtime profile field must be positive: " + field);
        }
    }

    private static void validateNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new RuntimeProfileException("Runtime profile field must be non-negative: " + field);
        }
    }

    private static void validateTraceSink(String value) {
        if (value == null) {
            return;
        }
        String normalized = value.strip();
        if (!"stderr".equals(normalized)
                && !"file".equals(normalized)
                && !"both".equals(normalized)
                && !"none".equals(normalized)) {
            throw new RuntimeProfileException("trace.sink must be one of stderr, file, both, none: " + value);
        }
    }

    private record PolicyResolution(PolicyConfig policy, String source) {
    }
}
