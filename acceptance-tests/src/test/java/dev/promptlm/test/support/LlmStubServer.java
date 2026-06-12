/*
 * Copyright 2025 promptLM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.promptlm.test.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process OpenAI-compatible chat-completions stub server used by the
 * native acceptance harness.
 *
 * <p>Binds on {@code 127.0.0.1:0} (random free port). Three modes are
 * supported:
 * <ul>
 *     <li>{@link Mode#ECHO} — replies with the verbatim content of the last
 *         user message in the request body.</li>
 *     <li>{@link Mode#CANNED} — replies with a fixed content + token usage.</li>
 *     <li>{@link Mode#FAIL} — replies with a configurable error status.</li>
 * </ul>
 *
 * <p>Records every request so tests can assert what the webapp sent.
 *
 * <p>This stub avoids a WireMock dependency — Spring AI's OpenAI client only
 * needs a {@code POST /v1/chat/completions} endpoint returning the standard
 * payload shape, which the JDK's built-in {@link HttpServer} handles fine.
 */
public final class LlmStubServer implements AutoCloseable {

    /**
     * Reply mode of the stub.
     */
    public enum Mode {
        ECHO,
        CANNED,
        FAIL
    }

    /**
     * Canned response payload used in {@link Mode#CANNED}.
     */
    public record CannedReply(String content, int promptTokens, int completionTokens) {
    }

    /**
     * Configured failure response used in {@link Mode#FAIL}.
     */
    public record Failure(int status, String body) {
    }

    /**
     * Snapshot of one received chat-completions request.
     */
    public record RecordedRequest(String method, String path, String body) {
    }

    private final HttpServer server;
    private final int port;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ECHO);
    private final AtomicReference<CannedReply> canned =
            new AtomicReference<>(new CannedReply("ok", 0, 0));
    private final AtomicReference<Failure> failure =
            new AtomicReference<>(new Failure(500, "{\"error\":\"stub\"}"));
    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();

    private LlmStubServer(HttpServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * Starts the stub on a random local port.
     */
    public static LlmStubServer start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            LlmStubServer stub = new LlmStubServer(http, http.getAddress().getPort());
            http.createContext("/", stub::handle);
            http.start();
            return stub;
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to start LlmStubServer", e);
        }
    }

    /**
     * Base URL that should be configured on the webapp's OpenAI client
     * ({@code spring.ai.openai.base-url}).
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /**
     * Switches to {@link Mode#ECHO}.
     */
    public void echoMode() {
        mode.set(Mode.ECHO);
    }

    /**
     * Switches to {@link Mode#CANNED} with the given reply.
     */
    public void cannedMode(CannedReply reply) {
        canned.set(reply);
        mode.set(Mode.CANNED);
    }

    /**
     * Switches to {@link Mode#FAIL} with the given failure response.
     */
    public void failMode(Failure value) {
        failure.set(value);
        mode.set(Mode.FAIL);
    }

    /**
     * The most recently received request (or {@code null} if none).
     */
    public RecordedRequest lastRequest() {
        return received.isEmpty() ? null : received.get(received.size() - 1);
    }

    /**
     * All received requests, in order.
     */
    public List<RecordedRequest> recordedRequests() {
        return new ArrayList<>(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        String bodyText = new String(requestBody, StandardCharsets.UTF_8);
        received.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), bodyText));

        try {
            switch (mode.get()) {
                case ECHO -> respond(exchange, 200, buildChatCompletionResponse(extractLastUserMessage(bodyText), 0, 0));
                case CANNED -> {
                    CannedReply reply = canned.get();
                    respond(exchange, 200, buildChatCompletionResponse(reply.content(),
                            reply.promptTokens(), reply.completionTokens()));
                }
                case FAIL -> {
                    Failure value = failure.get();
                    respond(exchange, value.status(), value.body());
                }
            }
        }
        catch (RuntimeException e) {
            respond(exchange, 500, "{\"error\":\"stub-failure: " + e.getMessage() + "\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    private static String buildChatCompletionResponse(String content, int promptTokens, int completionTokens) {
        String id = "chatcmpl-" + UUID.randomUUID();
        String contentJson = jsonString(content);
        // Spring AI's OpenAI client wants the canonical chat.completion shape.
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"object\":\"chat.completion\","
                + "\"created\":" + System.currentTimeMillis() / 1000 + ","
                + "\"model\":\"stub\","
                + "\"choices\":[{"
                + "\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":" + contentJson + "},"
                + "\"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{"
                + "\"prompt_tokens\":" + promptTokens + ","
                + "\"completion_tokens\":" + completionTokens + ","
                + "\"total_tokens\":" + (promptTokens + completionTokens)
                + "}"
                + "}";
    }

    /**
     * Naive extraction of the last user-role message content from the
     * incoming chat-completions JSON. Sufficient for ECHO mode — the stub
     * does not need a real JSON parser because the input shape is fixed.
     */
    private static String extractLastUserMessage(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        int searchFrom = 0;
        String lastContent = "";
        while (true) {
            int roleIdx = lower.indexOf("\"role\"", searchFrom);
            if (roleIdx < 0) {
                break;
            }
            int colonIdx = lower.indexOf(':', roleIdx);
            int quoteStart = lower.indexOf('"', colonIdx + 1);
            int quoteEnd = lower.indexOf('"', quoteStart + 1);
            if (quoteStart < 0 || quoteEnd < 0) {
                break;
            }
            String role = lower.substring(quoteStart + 1, quoteEnd);
            int contentIdx = body.indexOf("\"content\"", quoteEnd);
            if (contentIdx < 0) {
                break;
            }
            int contentColon = body.indexOf(':', contentIdx);
            int contentQuoteStart = body.indexOf('"', contentColon + 1);
            int contentQuoteEnd = contentQuoteStart < 0 ? -1 : findClosingQuote(body, contentQuoteStart + 1);
            if (contentQuoteStart < 0 || contentQuoteEnd < 0) {
                break;
            }
            String content = unescapeJsonString(body.substring(contentQuoteStart + 1, contentQuoteEnd));
            if ("user".equals(role)) {
                lastContent = content;
            }
            searchFrom = contentQuoteEnd + 1;
        }
        return lastContent;
    }

    private static int findClosingQuote(String body, int from) {
        for (int i = from; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                i++;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescapeJsonString(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    default -> {
                        sb.append(c);
                        sb.append(next);
                    }
                }
                i++;
            }
            else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

}
