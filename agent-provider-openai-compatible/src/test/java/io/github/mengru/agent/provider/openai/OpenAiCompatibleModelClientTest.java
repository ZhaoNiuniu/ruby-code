package io.github.mengru.agent.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ConversationMessage;
import io.github.mengru.agent.core.DefaultAgent;
import io.github.mengru.agent.core.EchoTool;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleModelClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Queue<FakeResponse> responses = new ArrayDeque<>();
    private final List<JsonNode> requests = new ArrayList<>();

    @Test
    void parsesFinalAnswerResponse() {
        responses.add(new FakeResponse(200, """
                {"choices":[{"message":{"role":"assistant","content":"hello from model"}}]}
                """));
        OpenAiCompatibleModelClient client = client();

        AgentStep step = client.nextStep(AgentRequest.of("hello"), List.of(), List.of(new EchoTool()));

        assertThat(step.type()).isEqualTo(AgentStep.Type.FINAL_ANSWER);
        assertThat(step.content()).isEqualTo("hello from model");
        JsonNode request = requests.get(0);
        assertThat(request.get("model").asText()).isEqualTo("test-model");
        assertThat(request.get("tool_choice").asText()).isEqualTo("auto");
        assertThat(request.get("parallel_tool_calls").asBoolean()).isFalse();
        assertThat(request.at("/tools/0/function/name").asText()).isEqualTo("echo");
        assertThat(request.at("/tools/0/function/parameters/properties/input/type").asText()).isEqualTo("string");
    }

    @Test
    void drivesToolCallLoopThenFinalAnswer() {
        responses.add(new FakeResponse(200, """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"echo","arguments":"{\\"input\\":\\"hello tool\\"}"}}]}}]}
                """));
        responses.add(new FakeResponse(200, """
                {"choices":[{"message":{"role":"assistant","content":"done"}}]}
                """));
        DefaultAgent agent = new DefaultAgent(client(), List.of(new EchoTool()));

        AgentResult result = agent.run(AgentRequest.of("hello tool"));

        assertThat(result.completed()).isTrue();
        assertThat(result.output()).isEqualTo("done");
        assertThat(result.steps())
                .extracting(AgentStep::type)
                .containsExactly(AgentStep.Type.TOOL_CALL, AgentStep.Type.TOOL_RESULT, AgentStep.Type.FINAL_ANSWER);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).at("/messages/2/tool_calls/0/id").asText()).isEqualTo("call_1");
        assertThat(requests.get(1).at("/messages/3/role").asText()).isEqualTo("tool");
        assertThat(requests.get(1).at("/messages/3/tool_call_id").asText()).isEqualTo("call_1");
        assertThat(requests.get(1).at("/messages/3/content").asText()).isEqualTo("echo: hello tool");
    }

    @Test
    void sendsConversationHistoryBeforeCurrentUserAndCurrentSteps() {
        responses.add(new FakeResponse(200, """
                {"choices":[{"message":{"role":"assistant","content":"done"}}]}
                """));
        com.fasterxml.jackson.databind.node.ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("input", "hello");

        client().nextStep(
                new AgentRequest(
                        "current question",
                        8,
                        Map.of(),
                        "system prompt",
                        List.of(
                                ConversationMessage.user("previous question"),
                                ConversationMessage.assistant("previous answer")
                        )
                ),
                List.of(
                        AgentStep.toolCall("call-history", "echo", arguments),
                        AgentStep.toolResult("call-history", "echo", "tool output")
                ),
                List.of()
        );

        JsonNode messages = requests.get(0).get("messages");
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("system prompt");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText()).isEqualTo("previous question");
        assertThat(messages.get(2).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).get("content").asText()).isEqualTo("previous answer");
        assertThat(messages.get(3).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(3).get("content").asText()).isEqualTo("current question");
        assertThat(messages.get(4).at("/tool_calls/0/id").asText()).isEqualTo("call-history");
        assertThat(messages.get(5).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(5).get("content").asText()).isEqualTo("tool output");
    }

    @Test
    void requiresApiKeyFromEnvironment() {
        assertThatThrownBy(() -> OpenAiCompatibleModelClient.fromEnvironment("model", Map.of()))
                .isInstanceOf(OpenAiCompatibleException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void readsHttpsProxyBeforeHttpProxyFromEnvironment() {
        InetSocketAddress address = OpenAiCompatibleModelClient.proxyAddressFromEnvironment(Map.of(
                "HTTPS_PROXY", "http://127.0.0.1:7890",
                "HTTP_PROXY", "http://127.0.0.1:8888"
        )).orElseThrow();

        assertThat(address.getHostString()).isEqualTo("127.0.0.1");
        assertThat(address.getPort()).isEqualTo(7890);
    }

    @Test
    void acceptsProxyWithoutScheme() {
        InetSocketAddress address = OpenAiCompatibleModelClient.proxyAddressFromEnvironment(Map.of(
                "HTTPS_PROXY", "127.0.0.1:7890"
        )).orElseThrow();

        assertThat(address.getHostString()).isEqualTo("127.0.0.1");
        assertThat(address.getPort()).isEqualTo(7890);
    }

    @Test
    void returnsEmptyProxyWhenEnvironmentDoesNotConfigureProxy() {
        assertThat(OpenAiCompatibleModelClient.proxyAddressFromEnvironment(Map.of())).isEmpty();
    }

    @Test
    void rejectsInvalidProxyEnvironmentValue() {
        assertThatThrownBy(() -> OpenAiCompatibleModelClient.proxyAddressFromEnvironment(Map.of(
                "HTTPS_PROXY", "http://127.0.0.1"
        )))
                .isInstanceOf(OpenAiCompatibleException.class)
                .hasMessageContaining("Invalid proxy URL");
    }

    @Test
    void reportsNonSuccessHttpResponse() {
        responses.add(new FakeResponse(401, "{\"error\":{\"message\":\"bad key\"}}"));

        assertThatThrownBy(() -> client().nextStep(AgentRequest.of("hello"), List.of(), List.of()))
                .isInstanceOf(OpenAiCompatibleException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void reportsMalformedJsonResponse() {
        responses.add(new FakeResponse(200, "{ nope"));

        assertThatThrownBy(() -> client().nextStep(AgentRequest.of("hello"), List.of(), List.of()))
                .isInstanceOf(OpenAiCompatibleException.class)
                .hasMessageContaining("response JSON");
    }

    private OpenAiCompatibleModelClient client() {
        return new OpenAiCompatibleModelClient(
                "test-model",
                "test-key",
                URI.create("https://example.test/v1"),
                new FakeHttpClient(),
                objectMapper,
                Duration.ofSeconds(5)
        );
    }

    private final class FakeHttpClient extends HttpClient {

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            try {
                requests.add(objectMapper.readTree(bodyString(request)));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            FakeResponse response = responses.remove();
            @SuppressWarnings("unchecked")
            HttpResponse<T> cast = (HttpResponse<T>) new FakeHttpResponse(request, response.statusCode(), response.body());
            return cast;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Redirect followRedirects() {
            return HttpClient.Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        private String bodyString(HttpRequest request) throws IOException {
            BodyCollector collector = new BodyCollector();
            request.bodyPublisher().orElseThrow().subscribe(collector);
            return collector.body();
        }
    }

    private record FakeHttpResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {

        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<String> completed = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            output.writeBytes(bytes);
        }

        @Override
        public void onError(Throwable throwable) {
            completed.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completed.complete(output.toString(StandardCharsets.UTF_8));
        }

        String body() throws IOException {
            try {
                return completed.join();
            } catch (RuntimeException e) {
                throw new IOException(e);
            }
        }
    }

    private record FakeResponse(int statusCode, String body) {
    }
}
