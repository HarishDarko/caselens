package com.harishdarko.caselens.triage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The recorded results of each industry, from {@code packs/<id>/recorded-triage.json}: an object keyed by scenario
 * key. A pack without the file has none. A file that cannot be read fails at load, so a broken fixture stops the
 * application from starting instead of failing when a visitor opens a workspace.
 */
public final class RecordedTriageCatalog {
    private final Map<String, Map<String, RecordedTriage>> byIndustry;

    private RecordedTriageCatalog(Map<String, Map<String, RecordedTriage>> byIndustry) {
        this.byIndustry = byIndustry;
    }

    public static RecordedTriageCatalog load(PackCatalog packs, ObjectMapper mapper) {
        Map<String, Map<String, RecordedTriage>> byIndustry = new LinkedHashMap<>();
        for (String id : packs.ids()) byIndustry.put(id, loadPack(id, packs.require(id), mapper));
        return new RecordedTriageCatalog(byIndustry);
    }

    private static Map<String, RecordedTriage> loadPack(String id, PackRules pack, ObjectMapper mapper) {
        String resource = "/packs/" + id + "/recorded-triage.json";
        try (InputStream input = RecordedTriageCatalog.class.getResourceAsStream(resource)) {
            if (input == null) return Map.of();
            JsonNode root = mapper.readTree(input);
            TriageResponseSchema schema = new TriageResponseSchema(mapper, pack);
            Map<String, RecordedTriage> recorded = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                JsonNode node = entry.getValue();
                ProviderTriageResult answer = schema.parse(mapper.writeValueAsString(node),
                        node.path("modelVersion").asText(), node.path("promptVersion").asText());
                recorded.put(entry.getKey(), new RecordedTriage(entry.getKey(), node.path("scenarioSha256").asText(),
                        Instant.parse(node.path("capturedAt").asText()), answer,
                        ReliabilitySignal.valueOf(node.path("reliabilitySignal").asText())));
            }
            return java.util.Collections.unmodifiableMap(recorded);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Recorded results could not be read: " + resource, exception);
        }
    }

    /** The industry's recorded results by scenario key; empty for an industry without any. */
    public Map<String, RecordedTriage> forIndustry(String industry) {
        return byIndustry.getOrDefault(industry, Map.of());
    }

    public Optional<RecordedTriage> find(String industry, String scenarioKey) {
        return Optional.ofNullable(forIndustry(industry).get(scenarioKey));
    }
}
