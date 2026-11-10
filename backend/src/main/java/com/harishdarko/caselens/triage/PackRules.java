package com.harishdarko.caselens.triage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One industry pack, read from {@code packs/<id>/pack.json}: its categories and policies, and the vocabulary the
 * rules, the validator and the scorer use (keyword rules, actions, evidence support, priority indicators). Ordered
 * lists keep their file order: for rules, the first match wins. Loading checks the file and fails with a clear
 * message, so a broken pack stops the application at startup instead of changing triage results.
 */
public final class PackRules {
    private final String id;
    private final String name;
    private final List<Category> categories;
    private final Category generalCategory;
    private final PolicyCatalog policies;
    private final List<TermRule<Category>> categoryRules;
    private final List<TermRule<Urgency>> urgencyRules;
    private final List<TermRule<SlaRisk>> slaRiskRules;
    private final List<TermRule<Sentiment>> sentimentRules;
    private final List<PolicyRule> policyRules;
    private final Map<Category, List<String>> actions;
    private final Map<Category, List<String>> categorySupport;
    private final Map<Urgency, List<String>> urgencySupport;
    private final Map<String, PriorityIndicator> priority;

    private PackRules(JsonNode root, String expectedId) {
        id = required(root, "id").asText();
        if (!id.equals(expectedId)) throw new IllegalStateException("Pack id '" + id + "' does not match its folder '" + expectedId + "'");
        name = required(root, "name").asText();
        List<Category> declared = new ArrayList<>();
        for (JsonNode category : required(root, "categories")) declared.add(Category.of(category.asText()));
        categories = List.copyOf(declared);
        if (categories.isEmpty() || Set.copyOf(categories).size() != categories.size()) {
            throw new IllegalStateException("Pack categories must be a non-empty list without repeats");
        }
        generalCategory = requireCategory(required(root, "generalCategory").asText());
        Map<String, String> guidance = new LinkedHashMap<>();
        for (JsonNode policy : required(root, "policies")) {
            guidance.put(required(policy, "id").asText(), required(policy, "guidance").asText());
        }
        policies = PolicyCatalog.of(required(root, "policyVersion").asText(), guidance);
        JsonNode rules = required(root, "rules");
        categoryRules = termRules(required(rules, "category"), this::requireCategory);
        urgencyRules = termRules(required(rules, "urgency"), Urgency::valueOf);
        slaRiskRules = termRules(required(rules, "slaRisk"), SlaRisk::valueOf);
        sentimentRules = termRules(required(rules, "sentiment"), Sentiment::valueOf);
        List<PolicyRule> policyList = new ArrayList<>();
        for (JsonNode rule : required(rules, "policies")) {
            String policyId = required(rule, "id").asText();
            if (!policies.contains(policyId)) throw new IllegalStateException("Pack rule uses an unknown policy: " + policyId);
            List<List<String>> allOf = new ArrayList<>();
            for (JsonNode group : required(rule, "allOf")) allOf.add(terms(group));
            policyList.add(new PolicyRule(policyId,
                    rule.hasNonNull("category") ? requireCategory(rule.get("category").asText()) : null, List.copyOf(allOf)));
        }
        policyRules = List.copyOf(policyList);
        actions = byCategory(required(rules, "actions"), PackRules::strings);
        JsonNode support = required(root, "evidenceSupport");
        categorySupport = byCategory(required(support, "category"), PackRules::terms);
        Map<Urgency, List<String>> urgencyTerms = new EnumMap<>(Urgency.class);
        for (Urgency urgency : Urgency.values()) urgencyTerms.put(urgency, terms(required(required(support, "urgency"), urgency.name())));
        urgencySupport = urgencyTerms;
        Map<String, PriorityIndicator> indicators = new LinkedHashMap<>();
        for (String code : PriorityScorer.INDICATORS) {
            JsonNode indicator = required(required(root, "priority"), code);
            indicators.put(code, new PriorityIndicator(required(indicator, "explanation").asText(), terms(required(indicator, "terms"))));
        }
        priority = Map.copyOf(indicators);
    }

    /** The EV charging pack, the default for workspaces created before packs existed. */
    static PackRules evCharging() {
        return PackCatalog.builtIn().defaultPack();
    }

    static PackRules load(String id) {
        String resource = "/packs/" + id + "/pack.json";
        try (InputStream input = PackRules.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Pack file is missing: " + resource);
            return new PackRules(new ObjectMapper().readTree(input), id);
        } catch (IOException | JacksonException exception) {
            throw new IllegalStateException("Pack file could not be read: " + resource, exception);
        }
    }

    static PackRules parse(String json, String expectedId) {
        return new PackRules(new ObjectMapper().readTree(json), expectedId);
    }

    public String id() { return id; }
    String name() { return name; }
    PolicyCatalog policies() { return policies; }

    /** The categories of this pack, in file order. */
    List<Category> categories() { return categories; }

    /** The category used when no rule matches; the scorer and the rules treat it as the general, low-urgency case. */
    Category generalCategory() { return generalCategory; }

    /** The named category, which must belong to this pack. */
    Category requireCategory(String name) {
        Category category = Category.of(name);
        if (!categories.contains(category)) throw new IllegalArgumentException("Category is not part of this pack");
        return category;
    }

    Optional<Category> category(String text) { return firstMatch(categoryRules, text); }
    Optional<Urgency> urgency(String text) { return firstMatch(urgencyRules, text); }
    Optional<SlaRisk> slaRisk(String text) { return firstMatch(slaRiskRules, text); }
    Optional<Sentiment> sentiment(String text) { return firstMatch(sentimentRules, text); }

    List<String> policyIds(String text, Category category) {
        List<String> ids = new ArrayList<>();
        for (PolicyRule rule : policyRules) if (rule.matches(text, category)) ids.add(rule.id());
        return ids;
    }

    List<String> actions(Category category) { return actions.get(category); }

    boolean evidenceSupports(Category category, String evidenceText) {
        return containsAny(evidenceText, categorySupport.get(category));
    }

    boolean evidenceSupports(Urgency urgency, String evidenceText) {
        return containsAny(evidenceText, urgencySupport.get(urgency));
    }

    PriorityIndicator priority(String code) { return priority.get(code); }

    static boolean containsAny(String text, List<String> terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static <T> Optional<T> firstMatch(List<TermRule<T>> rules, String text) {
        for (TermRule<T> rule : rules) if (containsAny(text, rule.terms())) return Optional.of(rule.value());
        return Optional.empty();
    }

    private static <T> List<TermRule<T>> termRules(JsonNode node, Function<String, T> parse) {
        List<TermRule<T>> rules = new ArrayList<>();
        for (JsonNode rule : node) rules.add(new TermRule<>(parse.apply(required(rule, "value").asText()), terms(required(rule, "terms"))));
        return List.copyOf(rules);
    }

    private Map<Category, List<String>> byCategory(JsonNode node, Function<JsonNode, List<String>> read) {
        Map<Category, List<String>> result = new LinkedHashMap<>();
        for (Category category : categories) result.put(category, read.apply(required(node, category.name())));
        return Map.copyOf(result);
    }

    /** Match terms are compared with lower-cased ticket text, so they must be lower case and not blank. */
    private static List<String> terms(JsonNode node) {
        List<String> terms = strings(node);
        for (String term : terms) {
            if (term.isBlank()) throw new IllegalStateException("Pack match terms must not be blank");
            if (!term.equals(term.toLowerCase(Locale.ROOT))) throw new IllegalStateException("Pack match terms must be lower case: " + term);
        }
        return terms;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalStateException("Pack file field must be a list");
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(item.asText());
        return List.copyOf(values);
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) throw new IllegalStateException("Pack file is missing field: " + field);
        return value;
    }

    record TermRule<T>(T value, List<String> terms) {}

    record PolicyRule(String id, Category category, List<List<String>> allOf) {
        boolean matches(String text, Category actual) {
            if (category != null && !category.equals(actual)) return false;
            for (List<String> group : allOf) if (!containsAny(text, group)) return false;
            return true;
        }
    }

    record PriorityIndicator(String explanation, List<String> terms) {}
}
