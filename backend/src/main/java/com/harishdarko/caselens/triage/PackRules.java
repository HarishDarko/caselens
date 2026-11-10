package com.harishdarko.caselens.triage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The industry vocabulary of a pack (keyword rules, actions, evidence support and priority indicators), read from
 * {@code packs/<id>/pack.json}. Ordered lists keep their file order: for rules, the first match wins.
 */
final class PackRules {
    private static final PackRules EV_CHARGING = load("/packs/ev-charging/pack.json");

    private final List<Category> categories;
    private final Category generalCategory;
    private final List<TermRule<Category>> categoryRules;
    private final List<TermRule<Urgency>> urgencyRules;
    private final List<TermRule<SlaRisk>> slaRiskRules;
    private final List<TermRule<Sentiment>> sentimentRules;
    private final List<PolicyRule> policyRules;
    private final Map<Category, List<String>> actions;
    private final Map<Category, List<String>> categorySupport;
    private final Map<Urgency, List<String>> urgencySupport;
    private final Map<String, PriorityIndicator> priority;

    private PackRules(JsonNode root) {
        List<Category> declared = new ArrayList<>();
        for (JsonNode name : required(root, "categories")) declared.add(Category.of(name.asText()));
        categories = List.copyOf(declared);
        if (categories.isEmpty() || Set.copyOf(categories).size() != categories.size()) {
            throw new IllegalStateException("Pack categories must be a non-empty list without repeats");
        }
        generalCategory = requireCategory(required(root, "generalCategory").asText());
        JsonNode rules = required(root, "rules");
        categoryRules = termRules(required(rules, "category"), this::requireCategory);
        urgencyRules = termRules(required(rules, "urgency"), Urgency::valueOf);
        slaRiskRules = termRules(required(rules, "slaRisk"), SlaRisk::valueOf);
        sentimentRules = termRules(required(rules, "sentiment"), Sentiment::valueOf);
        List<PolicyRule> policies = new ArrayList<>();
        for (JsonNode rule : required(rules, "policies")) {
            List<List<String>> allOf = new ArrayList<>();
            for (JsonNode group : required(rule, "allOf")) allOf.add(strings(group));
            policies.add(new PolicyRule(required(rule, "id").asText(),
                    rule.hasNonNull("category") ? requireCategory(rule.get("category").asText()) : null, List.copyOf(allOf)));
        }
        policyRules = List.copyOf(policies);
        actions = byCategory(required(rules, "actions"));
        JsonNode support = required(root, "evidenceSupport");
        categorySupport = byCategory(required(support, "category"));
        urgencySupport = byEnum(Urgency.values(), required(support, "urgency"));
        Map<String, PriorityIndicator> indicators = new LinkedHashMap<>();
        for (String code : List.of("PAYMENT_IMPACT", "SERVICE_BLOCKED", "BROAD_IMPACT", "POSITIVE_GENERAL_REQUEST")) {
            JsonNode indicator = required(required(root, "priority"), code);
            indicators.put(code, new PriorityIndicator(required(indicator, "explanation").asText(),
                    strings(required(indicator, "terms"))));
        }
        priority = Map.copyOf(indicators);
    }

    static PackRules evCharging() {
        return EV_CHARGING;
    }

    static PackRules load(String resource) {
        try (InputStream input = PackRules.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Pack file is missing: " + resource);
            return new PackRules(new ObjectMapper().readTree(input));
        } catch (IOException exception) {
            throw new IllegalStateException("Pack file could not be read: " + resource, exception);
        }
    }

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
        for (JsonNode rule : node) rules.add(new TermRule<>(parse.apply(required(rule, "value").asText()), strings(required(rule, "terms"))));
        return List.copyOf(rules);
    }

    private Map<Category, List<String>> byCategory(JsonNode node) {
        Map<Category, List<String>> result = new LinkedHashMap<>();
        for (Category category : categories) result.put(category, strings(required(node, category.name())));
        return Map.copyOf(result);
    }

    private static <E extends Enum<E>> Map<E, List<String>> byEnum(E[] values, JsonNode node) {
        Map<E, List<String>> result = new EnumMap<>(values[0].getDeclaringClass());
        for (E value : values) result.put(value, strings(required(node, value.name())));
        return result;
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
