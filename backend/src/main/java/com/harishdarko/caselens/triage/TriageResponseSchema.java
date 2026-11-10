package com.harishdarko.caselens.triage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The structured-output schema a model must follow, and the parser for its answer. Shared by the AI providers. */
final class TriageResponseSchema {
    private final ObjectMapper mapper;
    private final PolicyCatalog policyCatalog;
    private final PackRules pack = PackRules.evCharging();

    TriageResponseSchema(ObjectMapper mapper, PolicyCatalog policyCatalog) {
        this.mapper = Objects.requireNonNull(mapper);
        this.policyCatalog = Objects.requireNonNull(policyCatalog);
    }

    ObjectNode schema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode categories = properties.putObject("category").put("type", "string").putArray("enum");
        pack.categories().forEach(category -> categories.add(category.name()));
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

    ProviderTriageResult parse(String text, String model, String promptVersion) throws IOException {
        JsonNode node = mapper.readTree(text);
        if (!node.isObject()) throw new IOException("Model output was not an object");
        List<EvidenceItem> evidence = new ArrayList<>();
        for (JsonNode item : node.path("evidence")) {
            evidence.add(new EvidenceItem(item.path("quote").asText(), item.path("meaning").asText()));
        }
        return new ProviderTriageResult(
                pack.requireCategory(node.path("category").asText()),
                Urgency.valueOf(node.path("urgency").asText()),
                SlaRisk.valueOf(node.path("slaRisk").asText()),
                Sentiment.valueOf(node.path("sentiment").asText()),
                node.path("summary").asText(), evidence, strings(node.path("policyIds")),
                node.path("explanation").asText(), strings(node.path("recommendedActions")),
                node.path("suggestedReply").asText(), strings(node.path("warnings")), model, promptVersion);
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

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(item.asText());
        return values;
    }
}
