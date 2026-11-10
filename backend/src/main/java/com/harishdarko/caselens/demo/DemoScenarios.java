package com.harishdarko.caselens.demo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The demo tickets of each industry. EV charging keeps its versioned file (demo/scenarios-v1.json); every other
 * industry has packs/&lt;id&gt;/scenarios.json. The retry demonstration is not about any industry, so every
 * workspace gets it.
 */
public final class DemoScenarios {
    static final String SHARED_RETRY_SCENARIO = "provider-retry-demo";
    private static final String EV_SCENARIOS = "demo/scenarios-v1.json";

    private final Map<String, List<DemoScenario>> byIndustry;
    private final DemoWorkspaceAccess workspaces;

    DemoScenarios(Map<String, List<DemoScenario>> byIndustry, DemoWorkspaceAccess workspaces) {
        this.byIndustry = Map.copyOf(byIndustry);
        this.workspaces = Objects.requireNonNull(workspaces);
        Set<String> keys = new HashSet<>();
        byIndustry.values().forEach(list -> list.stream().map(DemoScenario::key)
                .filter(key -> !key.equals(SHARED_RETRY_SCENARIO))
                .forEach(key -> { if (!keys.add(key)) throw new IllegalStateException("Demo scenario key is used twice: " + key); }));
    }

    /** Loads the scenario files of the given industries, in each file's order. */
    static Map<String, List<DemoScenario>> fromClasspath(Collection<String> industries) {
        List<DemoScenario> ev = ScenarioCatalog.fromClasspath(EV_SCENARIOS).ordered();
        DemoScenario retry = ev.stream().filter(scenario -> scenario.key().equals(SHARED_RETRY_SCENARIO)).findFirst()
                .orElseThrow(() -> new IllegalStateException("The retry demo scenario is missing"));
        Map<String, List<DemoScenario>> result = new LinkedHashMap<>();
        for (String industry : industries) {
            if (industry.equals(DemoWorkspace.DEFAULT_INDUSTRY)) {
                result.put(industry, ev);
            } else {
                List<DemoScenario> scenarios = new ArrayList<>(
                        ScenarioCatalog.fromClasspath("packs/" + industry + "/scenarios.json").ordered());
                scenarios.add(retry);
                result.put(industry, List.copyOf(scenarios));
            }
        }
        return result;
    }

    public List<DemoScenario> forIndustry(String industry) {
        List<DemoScenario> scenarios = byIndustry.get(industry);
        if (scenarios == null) throw new IllegalStateException("No demo scenarios for industry: " + industry);
        return scenarios;
    }

    public List<DemoScenario> forWorkspace(UUID workspaceId) {
        return forIndustry(workspaces.industryOf(workspaceId));
    }

    /** A scenario of the workspace's industry; the same error as before for a key the workspace does not have. */
    public DemoScenario require(UUID workspaceId, String key) {
        return forWorkspace(workspaceId).stream().filter(scenario -> scenario.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown demo scenario"));
    }

    /** The scenario the inbox offers first: the first critical one in the file, or else the first one. */
    public DemoScenario featured(String industry) {
        List<DemoScenario> scenarios = forIndustry(industry);
        return scenarios.stream().filter(scenario -> "CRITICAL".equals(scenario.expectedUrgency())).findFirst()
                .orElse(scenarios.get(0));
    }
}
