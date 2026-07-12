package com.harishdarko.caselens.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Supplier;

public final class ApiHandler implements RequestStreamHandler {
    private final ApiTransport transport;
    private final Supplier<String> baseUrl;
    private final ObjectMapper mapper;

    public ApiHandler() {
        this(new DefaultApiTransport(), LambdaSpringContext::baseUrl, LambdaSpringContext::mapper);
    }

    ApiHandler(ApiTransport transport, Supplier<String> baseUrl) {
        this(transport, baseUrl, ObjectMapper::new);
    }

    ApiHandler(ApiTransport transport, Supplier<String> baseUrl, Supplier<ObjectMapper> mapperSupplier) {
        this.transport = transport;
        this.baseUrl = baseUrl;
        this.mapper = mapperSupplier.get();
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
        ApiExchange response = transport.exchange(method,
                URI.create(baseUrl.get() + path + (query.isBlank() ? "" : "?" + query)), headers, body(event));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statusCode", response.statusCode());
        result.put("headers", response.headers().entrySet().stream()
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), String.join(",", entry.getValue())), Map::putAll));
        result.put("body", response.body());
        result.put("isBase64Encoded", false);
        mapper.writeValue(output, result);
    }

    private static Map<String, String> forwardedHeaders(JsonNode event) {
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode source = event.get("headers");
        if (source != null && source.isObject()) source.fields().forEachRemaining(entry -> {
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

    private static final class DefaultApiTransport implements ApiTransport {
        @Override
        public ApiExchange exchange(String method, URI uri, Map<String, String> headers, String body) throws IOException {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri);
            headers.forEach(request::header);
            request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            try {
                HttpResponse<String> response = LambdaSpringContext.httpClient()
                        .send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new ApiExchange(response.statusCode(), response.headers().map(), response.body());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Lambda API invocation interrupted", exception);
            }
        }
    }

    private static final class SetHeaders {
        private static final java.util.Set<String> SKIPPED = java.util.Set.of(
                "host", "content-length", "connection", "x-caselens-client-ip");
    }
}
