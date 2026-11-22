package com.harishdarko.caselens.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ApiHandler implements RequestStreamHandler {
    private final ApiTransport transport;
    private final Supplier<String> baseUrl;
    private final ObjectMapper mapper;
    private final Supplier<Set<String>> allowedOrigins;

    public ApiHandler() {
        this(new DefaultApiTransport(), LambdaSpringContext::baseUrl, LambdaSpringContext::mapper,
                LambdaSpringContext::webOrigins);
    }

    ApiHandler(ApiTransport transport, Supplier<String> baseUrl) {
        this(transport, baseUrl, ObjectMapper::new);
    }

    ApiHandler(ApiTransport transport, Supplier<String> baseUrl, Supplier<ObjectMapper> mapperSupplier) {
        this(transport, baseUrl, mapperSupplier, Set::of);
    }

    ApiHandler(ApiTransport transport, Supplier<String> baseUrl, Supplier<ObjectMapper> mapperSupplier,
            Supplier<Set<String>> allowedOrigins) {
        this.transport = transport;
        this.baseUrl = baseUrl;
        this.mapper = mapperSupplier.get();
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        JsonNode event = mapper.readTree(input);
        String method = text(event.at("/requestContext/http/method"), "GET");
        String path = text(event.get("rawPath"), "/");
        String query = text(event.get("rawQueryString"), "");
        Map<String, String> headers = forwardedHeaders(event);
        String sourceIp = sourceIp(event);
        if (!sourceIp.isBlank()) headers.put("X-CaseLens-Client-Ip", sourceIp);
        ApiExchange response;
        try {
            response = transport.exchange(method,
                    URI.create(baseUrl.get() + path + (query.isBlank() ? "" : "?" + query)), headers, body(event));
        } catch (IllegalArgumentException malformed) {
            // URI.create rejects characters such as { | ^ and a bad %-escape; HttpRequest.Builder rejects bad header
            // names and values; Base64 rejects a bad body. All of these are the caller's fault, so answer 400
            // instead of failing the invocation, which would count as a Lambda error and trip the alarm.
            response = badRequest(header(event, "origin"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statusCode", response.statusCode());
        result.put("headers", response.headers().entrySet().stream()
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), String.join(",", entry.getValue())), Map::putAll));
        result.put("body", response.body());
        result.put("isBase64Encoded", false);
        mapper.writeValue(output, result);
    }

    static final String BAD_REQUEST_BODY = "{\"type\":\"about:blank\",\"title\":\"Bad request\",\"status\":400,"
            + "\"detail\":\"The request URI or headers are malformed\"}";

    /**
     * This answer never reaches Spring, so it adds the CORS headers Spring would add for an allowed origin itself.
     * Without them the browser hides the 400 and fetch rejects with a TypeError, which the page reports as a
     * network problem.
     */
    ApiExchange badRequest(String origin) {
        Map<String, java.util.List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", java.util.List.of("application/problem+json"));
        if (origin != null && allowedOrigins.get().contains(origin)) {
            headers.put("Access-Control-Allow-Origin", java.util.List.of(origin));
            headers.put("Access-Control-Expose-Headers", java.util.List.of("X-Correlation-ID", "Retry-After"));
            headers.put("Vary", java.util.List.of("Origin"));
        }
        return new ApiExchange(400, headers, BAD_REQUEST_BODY);
    }

    private static String header(JsonNode event, String name) {
        JsonNode source = event.get("headers");
        if (source == null || !source.isObject()) return null;
        for (var entry : source.properties()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue().asText();
        }
        return null;
    }

    static Map<String, String> forwardedHeaders(JsonNode event) {
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode source = event.get("headers");
        if (source != null && source.isObject()) source.properties().forEach(entry -> {
            if (!SetHeaders.SKIPPED.contains(entry.getKey().toLowerCase())) {
                headers.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return headers;
    }

    static String sourceIp(JsonNode event) {
        return text(event.at("/requestContext/http/sourceIp"), "");
    }

    static String body(JsonNode event) {
        String value = text(event.get("body"), "");
        return event.path("isBase64Encoded").asBoolean(false)
                ? new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8) : value;
    }

    private static String text(JsonNode node, String fallback) {
        return node == null || node.isMissingNode() || node.isNull() ? fallback : node.asText(fallback);
    }

    interface ApiTransport {
        ApiExchange exchange(String method, URI uri, Map<String, String> headers, String body) throws IOException;
    }

    record ApiExchange(int statusCode, Map<String, java.util.List<String>> headers, String body) {}

    /** Builds the loopback request; throws IllegalArgumentException for a header the JDK client refuses. */
    static HttpRequest request(String method, URI uri, Map<String, String> headers, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri);
        headers.forEach(request::header);
        request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return request.build();
    }

    private static final class DefaultApiTransport implements ApiTransport {
        @Override
        public ApiExchange exchange(String method, URI uri, Map<String, String> headers, String body) throws IOException {
            HttpRequest request = request(method, uri, headers, body);
            try {
                HttpResponse<String> response = LambdaSpringContext.httpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());
                return new ApiExchange(response.statusCode(), response.headers().map(), response.body());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Lambda API invocation interrupted", exception);
            }
        }
    }

    static final class SetHeaders {
        /**
         * Not forwarded to the loopback request: hop-by-hop headers (RFC 9110 section 7.6.1), the headers the JDK
         * HttpClient refuses to set (host, content-length, connection, expect, upgrade), and the client-IP header,
         * which only this handler may set.
         */
        static final java.util.Set<String> SKIPPED = java.util.Set.of(
                "host", "content-length", "connection", "expect", "upgrade", "keep-alive", "te", "trailer",
                "transfer-encoding", "proxy-connection", "x-caselens-client-ip");
    }
}
