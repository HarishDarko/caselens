package com.harishdarko.caselens.triage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

public final class GeminiTriageProvider implements TriageProvider {
    private static final String PROMPT_VERSION = "triage-v1";
    private static final int MAX_RESPONSE_CHARS = 65_536;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;
    private final IntSupplier retryDelayMillis;
    private final PolicyCatalog policyCatalog;
    private final PromptBuilder promptBuilder = new PromptBuilder(PROMPT_VERSION);

    public GeminiTriageProvider(URI endpoint, String apiKey, String model, HttpClient client,
            ObjectMapper mapper, Duration requestTimeout, IntSupplier retryDelayMillis) {
        this(endpoint, apiKey, model, client, mapper, requestTimeout, retryDelayMillis, PolicyCatalog.defaultCatalog());
    }

    public GeminiTriageProvider(URI endpoint, String apiKey, String model, HttpClient client,
            ObjectMapper mapper, Duration requestTimeout, IntSupplier retryDelayMillis, PolicyCatalog policyCatalog) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.apiKey = required(apiKey, "Gemini API key");
        this.model = required(model, "Gemini model");
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.retryDelayMillis = Objects.requireNonNull(retryDelayMillis);
        this.policyCatalog = Objects.requireNonNull(policyCatalog);
    }

    @Override
    public ProviderCall analyze(TriageRequest request, RedactedTicket redactedTicket) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(redactedTicket);
        String body = requestBody(request, redactedTicket);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = sendWithOneTransientRetry(httpRequest);
        if (response.body().length() > MAX_RESPONSE_CHARS) {
            throw new ProviderCallException("Gemini provider response was too large", "RESPONSE_TOO_LARGE");
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            String output = outputText(root);
            ProviderTriageResult result = parseResult(output);
            JsonNode usage = root.path("usage");
            Long inputTokens = usageLong(usage, "total_input_tokens", "input_tokens");
            Long outputTokens = usageLong(usage, "total_output_tokens", "output_tokens");
            return new ProviderCall(result, inputTokens, outputTokens);
        } catch (ProviderCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderCallException("Gemini provider returned invalid structured output", "SCHEMA_FAILURE", exception);
        }
    }

    private HttpResponse<String> sendWithOneTransientRetry(HttpRequest request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) return response;
                if (attempt == 0 && (status == 429 || status >= 500)) {
                    pauseBeforeRetry();
                    continue;
                }
                if (status == 401 || status == 403) {
                    throw new ProviderCallException("Gemini provider request was rejected", "AUTHENTICATION_FAILURE");
                }
                throw new ProviderCallException("Gemini provider request failed", "HTTP_" + status);
            } catch (ProviderCallException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new ProviderCallException("Gemini provider request failed", "PROVIDER_IO", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ProviderCallException("Gemini provider request was interrupted", "INTERRUPTED", exception);
            }
        }
        throw new ProviderCallException("Gemini provider request failed", "TRANSIENT_FAILURE");
    }

    private void pauseBeforeRetry() {
        int delay = Math.max(0, Math.min(250, retryDelayMillis.getAsInt()));
        try {
            if (delay > 0) Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderCallException("Gemini provider request was interrupted", "INTERRUPTED", exception);
        }
    }

    private String requestBody(TriageRequest request, RedactedTicket redactedTicket) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("input", promptBuilder.build(request, redactedTicket));
        root.put("system_instruction", promptBuilder.systemInstruction()
                + " Use only policy identifiers permitted by the response schema; return an empty policyIds array when none apply.");
        root.put("store", false);
        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "text");
        responseFormat.put("mime_type", "application/json");
        responseFormat.set("schema", schema());
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new ProviderCallException("Gemini provider request could not be encoded", "REQUEST_ENCODING", exception);
        }
    }

    private ObjectNode schema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        enumProperty(properties, "category", Category.values());
        enumProperty(properties, "urgency", Urgency.values());
        enumProperty(properties, "slaRisk", SlaRisk.values());
        enumProperty(properties, "sentiment", Sentiment.values());
        properties.putObject("summary").put("type", "string").put("minLength", 20).put("maxLength", 300);
        ObjectNode evidence = properties.putObject("evidence").put("type", "array").put("minItems", 1).put("maxItems", 5);
        ObjectNode evidenceItem = evidence.putObject("items").put("type", "object").put("additionalProperties", false);
        ObjectNode evidenceProperties = evidenceItem.putObject("properties");
        evidenceProperties.putObject("quote").put("type", "string").put("minLength", 3).put("maxLength", 400);
        evidenceProperties.putObject("meaning").put("type", "string").put("minLength", 3).put("maxLength", 240);
        required(evidenceItem, "quote", "meaning");
        ObjectNode policyIds = properties.putObject("policyIds").put("type", "array").put("maxItems", 3);
        ObjectNode policyId = policyIds.putObject("items").put("type", "string");
        ArrayNode allowedPolicyIds = policyId.putArray("enum");
        policyCatalog.ids().stream().sorted().forEach(allowedPolicyIds::add);
        properties.putObject("explanation").put("type", "string").put("minLength", 30).put("maxLength", 600);
        properties.putObject("recommendedActions").put("type", "array").put("minItems", 1).put("maxItems", 5)
                .putObject("items").put("type", "string").put("minLength", 3).put("maxLength", 240);
        properties.putObject("suggestedReply").put("type", "string").put("minLength", 20).put("maxLength", 1000);
        properties.putObject("warnings").put("type", "array").put("maxItems", 5).putObject("items").put("type", "string");
        required(schema, "category", "urgency", "slaRisk", "sentiment", "summary", "evidence", "policyIds",
                "explanation", "recommendedActions", "suggestedReply", "warnings");
        return schema;
    }

    private void enumProperty(ObjectNode properties, String name, Enum<?>[] values) {
        ObjectNode property = properties.putObject(name).put("type", "string");
        ArrayNode allowed = property.putArray("enum");
        for (Enum<?> value : values) allowed.add(value.name());
    }

    private void required(ObjectNode node, String... names) {
        ArrayNode required = node.putArray("required");
        for (String name : names) required.add(name);
    }

    private String outputText(JsonNode root) {
        if (root.hasNonNull("output_text")) return root.get("output_text").asText();
        for (JsonNode step : root.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) continue;
            for (JsonNode content : step.path("content")) {
                if (content.hasNonNull("text")) return content.get("text").asText();
            }
        }
        throw new ProviderCallException("Gemini provider returned no model output", "SCHEMA_FAILURE");
    }

    private ProviderTriageResult parseResult(String text) throws IOException {
        JsonNode node = mapper.readTree(text);
        if (!node.isObject()) throw new IOException("Model output was not an object");
        List<EvidenceItem> evidence = new ArrayList<>();
        for (JsonNode item : node.path("evidence")) {
            evidence.add(new EvidenceItem(item.path("quote").asText(), item.path("meaning").asText()));
        }
        return new ProviderTriageResult(
                Category.valueOf(node.path("category").asText()),
                Urgency.valueOf(node.path("urgency").asText()),
                SlaRisk.valueOf(node.path("slaRisk").asText()),
                Sentiment.valueOf(node.path("sentiment").asText()),
                node.path("summary").asText(), evidence, strings(node.path("policyIds")),
                node.path("explanation").asText(), strings(node.path("recommendedActions")),
                node.path("suggestedReply").asText(), strings(node.path("warnings")), model, PROMPT_VERSION);
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(item.asText());
        return values;
    }

    private Long usageLong(JsonNode usage, String currentName, String legacyName) {
        if (usage.has(currentName)) return usage.get(currentName).asLong();
        return usage.has(legacyName) ? usage.get(legacyName).asLong() : null;
    }

    @Override public String providerName() { return "gemini"; }
    @Override public String modelVersion() { return model; }
    @Override public String promptVersion() { return PROMPT_VERSION; }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
