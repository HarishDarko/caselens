package com.harishdarko.caselens.triage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PolicyCatalog {
    private final String version;
    private final Map<String, String> guidanceById;

    private PolicyCatalog(String version, Map<String, String> guidanceById) {
        this.version = Objects.requireNonNull(version);
        if (version.isBlank() || guidanceById.isEmpty()) throw new IllegalArgumentException("Policy catalog is incomplete");
        this.guidanceById = Map.copyOf(guidanceById);
    }

    public static PolicyCatalog of(Set<String> ids) {
        Objects.requireNonNull(ids);
        Map<String, String> values = new LinkedHashMap<>();
        ids.stream().sorted().forEach(id -> values.put(id, id));
        return new PolicyCatalog("test", values);
    }

    public static PolicyCatalog load(InputStream source) {
        Objects.requireNonNull(source);
        try {
            String text = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            String version = null;
            Map<String, String> values = new LinkedHashMap<>();
            String currentId = null;
            for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                String line = rawLine.trim();
                if (line.startsWith("version:")) version = line.substring("version:".length()).trim();
                else if (line.startsWith("- id:")) currentId = line.substring("- id:".length()).trim();
                else if (line.startsWith("guidance:") && currentId != null) {
                    values.put(currentId, line.substring("guidance:".length()).trim());
                    currentId = null;
                }
            }
            return new PolicyCatalog(version, values);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read policy catalog", exception);
        }
    }

    public static PolicyCatalog defaultCatalog() {
        InputStream resource = PolicyCatalog.class.getResourceAsStream("/triage/policies-v1.yml");
        if (resource == null) throw new IllegalStateException("Versioned policy catalog is missing");
        return load(resource);
    }

    public String version() { return version; }
    public boolean contains(String id) { return guidanceById.containsKey(id); }
    public String guidance(String id) { return guidanceById.get(id); }
    public Set<String> ids() { return guidanceById.keySet(); }
}
