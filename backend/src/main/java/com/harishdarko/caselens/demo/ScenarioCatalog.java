package com.harishdarko.caselens.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ScenarioCatalog {
    private final Map<String, DemoScenario> scenarios;

    private ScenarioCatalog(List<DemoScenario> scenarios) {
        this.scenarios = scenarios.stream().collect(Collectors.toUnmodifiableMap(DemoScenario::key, Function.identity()));
    }

    public static ScenarioCatalog fromClasspath(String path) {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Scenario catalog not found");
            return new ScenarioCatalog(new ObjectMapper().readValue(input, new TypeReference<>() {}));
        } catch (IOException exception) {
            throw new IllegalStateException("Scenario catalog could not be loaded", exception);
        }
    }

    public List<DemoScenario> all() { return List.copyOf(scenarios.values()); }

    public DemoScenario require(String key) {
        DemoScenario scenario = scenarios.get(key);
        if (scenario == null) throw new IllegalArgumentException("Unknown demo scenario");
        return scenario;
    }
}
