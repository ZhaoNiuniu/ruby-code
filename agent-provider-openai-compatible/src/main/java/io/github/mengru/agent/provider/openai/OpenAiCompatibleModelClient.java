package io.github.mengru.agent.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ConversationMessage;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.PromptTooLongException;
import io.github.mengru.agent.api.Tool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class OpenAiCompatibleModelClient implements ModelClient {

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final String model;
    private final String apiKey;
    private final URI chatCompletionsUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public OpenAiCompatibleModelClient(String model, String apiKey, URI baseUrl) {
        this(model, apiKey, baseUrl, HttpClient.newHttpClient(), new ObjectMapper(), DEFAULT_TIMEOUT);
    }

    public OpenAiCompatibleModelClient(
            String model,
            String apiKey,
            URI baseUrl,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration timeout
    ) {
        this.model = requireNonBlank(model, "model");
        this.apiKey = requireNonBlank(apiKey, "apiKey");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.chatCompletionsUri = URI.create(stripTrailingSlash(baseUrl.toString()) + "/chat/completions");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    public static OpenAiCompatibleModelClient fromEnvironment(String model) {
        return fromEnvironment(model, System.getenv());
    }

    public static OpenAiCompatibleModelClient fromEnvironment(String model, Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        String apiKey = environment.get("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new OpenAiCompatibleException("OPENAI_API_KEY is required for provider openai-compatible.");
        }
        String baseUrl = environment.getOrDefault("OPENAI_BASE_URL", DEFAULT_BASE_URL);
        HttpClient httpClient = httpClientFromEnvironment(environment);
        return new OpenAiCompatibleModelClient(model, apiKey, URI.create(baseUrl), httpClient, new ObjectMapper(), DEFAULT_TIMEOUT);
    }

    @Override
    public AgentStep nextStep(AgentRequest request, List<AgentStep> previousSteps, List<Tool> tools) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(previousSteps, "previousSteps must not be null");
        Objects.requireNonNull(tools, "tools must not be null");

        ObjectNode requestBody = buildRequestBody(request, previousSteps, tools);
        String body = writeJson(requestBody, "Failed to serialize OpenAI-compatible request.");
        HttpRequest httpRequest = HttpRequest.newBuilder(chatCompletionsUri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new OpenAiCompatibleException("OpenAI-compatible request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAiCompatibleException("OpenAI-compatible request was interrupted.", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (isPromptTooLong(response.statusCode(), response.body())) {
                throw new PromptTooLongException("OpenAI-compatible request prompt is too long: HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            throw new OpenAiCompatibleException("OpenAI-compatible request failed with HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return parseResponse(response.body());
    }

    private ObjectNode buildRequestBody(AgentRequest request, List<AgentStep> previousSteps, List<Tool> tools) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.set("messages", buildMessages(request, previousSteps));
        if (!tools.isEmpty()) {
            root.set("tools", buildTools(tools));
            root.put("tool_choice", "auto");
            root.put("parallel_tool_calls", false);
        }
        return root;
    }

    private ArrayNode buildMessages(AgentRequest request, List<AgentStep> previousSteps) {
        ArrayNode messages = objectMapper.createArrayNode();
        String systemContent = systemContent(request);
        if (!systemContent.isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemContent);
        }

        for (ConversationMessage historyMessage : request.conversationHistory()) {
            messages.add(conversationMessage(historyMessage));
        }

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.task());

        for (AgentStep step : previousSteps) {
            switch (step.type()) {
                case TOOL_CALL -> messages.add(toolCallMessage(step));
                case TOOL_RESULT -> messages.add(toolResultMessage(step));
                case FINAL_ANSWER, THOUGHT, ERROR -> messages.add(assistantMessage(step.content()));
            }
        }
        return messages;
    }

    private String systemContent(AgentRequest request) {
        return request.systemPrompt();
    }

    private ObjectNode conversationMessage(ConversationMessage message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", switch (message.role()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
        });
        node.put("content", message.content());
        return node;
    }

    private ObjectNode toolCallMessage(AgentStep step) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode toolCall = toolCalls.addObject();
        toolCall.put("id", step.toolCallIdOptional().orElse("call_unknown"));
        toolCall.put("type", "function");
        ObjectNode function = toolCall.putObject("function");
        function.put("name", step.toolNameOptional().orElse(""));
        function.put("arguments", writeJson(step.toolArgumentsOrEmptyObject(), "Failed to serialize tool arguments."));
        return message;
    }

    private ObjectNode toolResultMessage(AgentStep step) {
        if (step.toolCallIdOptional().isEmpty()) {
            return assistantMessage("Tool result from " + step.toolNameOptional().orElse("unknown") + ": " + step.content());
        }
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "tool");
        message.put("tool_call_id", step.toolCallId());
        message.put("content", step.content());
        return message;
    }

    private ObjectNode assistantMessage(String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content == null ? "" : content);
        return message;
    }

    private ArrayNode buildTools(List<Tool> tools) {
        ArrayNode toolDefinitions = objectMapper.createArrayNode();
        for (Tool tool : tools) {
            ObjectNode toolDefinition = toolDefinitions.addObject();
            toolDefinition.put("type", "function");
            ObjectNode function = toolDefinition.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.parametersSchema());
        }
        return toolDefinitions;
    }

    private AgentStep parseResponse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new OpenAiCompatibleException("Failed to parse OpenAI-compatible response JSON.", e);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new OpenAiCompatibleException("OpenAI-compatible response did not include choices.");
        }

        JsonNode message = choices.get(0).path("message");
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            JsonNode toolCall = toolCalls.get(0);
            String id = textValue(toolCall, "id");
            JsonNode function = toolCall.path("function");
            String name = textValue(function, "name");
            JsonNode arguments = parseToolArguments(function.path("arguments").asText("{}"));
            return AgentStep.toolCall(id, name, arguments);
        }

        return AgentStep.finalAnswer(message.path("content").asText(""));
    }

    private JsonNode parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(arguments);
        } catch (JsonProcessingException e) {
            throw new OpenAiCompatibleException("Failed to parse tool call arguments JSON.", e);
        }
    }

    private String writeJson(JsonNode node, String errorMessage) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new OpenAiCompatibleException(errorMessage, e);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static HttpClient httpClientFromEnvironment(Map<String, String> environment) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        proxyAddressFromEnvironment(environment).ifPresent(address -> builder.proxy(ProxySelector.of(address)));
        return builder.build();
    }

    static Optional<InetSocketAddress> proxyAddressFromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        String proxy = firstNonBlank(
                environment.get("HTTPS_PROXY"),
                environment.get("https_proxy"),
                environment.get("HTTP_PROXY"),
                environment.get("http_proxy")
        );
        if (proxy == null) {
            return Optional.empty();
        }
        return Optional.of(parseProxyAddress(proxy));
    }

    private static InetSocketAddress parseProxyAddress(String proxy) {
        String normalized = proxy.contains("://") ? proxy : "http://" + proxy;
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new OpenAiCompatibleException("Invalid proxy URL: " + proxy, e);
        }
        if (uri.getUserInfo() != null) {
            throw new OpenAiCompatibleException("Proxy URLs with user info are not supported: " + proxy);
        }
        if (uri.getHost() == null || uri.getPort() < 0) {
            throw new OpenAiCompatibleException("Invalid proxy URL: " + proxy + ". Expected http://host:port.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new OpenAiCompatibleException("Unsupported proxy scheme in " + proxy + ". Expected http or https.");
        }
        return InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private boolean isPromptTooLong(int statusCode, String body) {
        if (statusCode == 413) {
            return true;
        }
        String normalized = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("context_length_exceeded")
                || normalized.contains("prompt_too_long")
                || normalized.contains("context length")
                || normalized.contains("maximum context");
    }

    private static String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.asText().isBlank()) {
            throw new OpenAiCompatibleException("OpenAI-compatible response is missing field: " + field);
        }
        return value.asText();
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
