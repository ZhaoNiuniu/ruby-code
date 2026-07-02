package io.github.mengru.agent.core.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;

import java.nio.file.Path;

public final class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_BACKGROUND_TIMEOUT_SECONDS = 1_800;
    private static final int MAX_BACKGROUND_TIMEOUT_SECONDS = 1_800;

    private final BashCommandRunner runner;

    public BashTool() {
        this(LocalToolSupport.defaultWorkspaceRoot());
    }

    public BashTool(Path workspaceRoot) {
        this.runner = new BashCommandRunner(workspaceRoot);
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a non-interactive shell command from the workspace with timeout and guardrails.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = LocalToolSupport.objectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("command", LocalToolSupport.stringProperty("Shell command to run non-interactively."));
        properties.set("timeoutSeconds", LocalToolSupport.integerProperty("Timeout in seconds. Foreground defaults to 30 and maxes at 120; chat background defaults to 1800 and maxes at 1800.", 1, MAX_BACKGROUND_TIMEOUT_SECONDS));
        properties.set("run_in_background", LocalToolSupport.booleanProperty("In agent chat, run obvious long-running commands in the background and return a bg_id immediately. agent run ignores this and executes foreground."));
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        return runner.run(request.arguments(), DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
    }

    public ToolResult executeBackground(ToolRequest request) {
        return runner.run(request.arguments(), DEFAULT_BACKGROUND_TIMEOUT_SECONDS, MAX_BACKGROUND_TIMEOUT_SECONDS);
    }
}
