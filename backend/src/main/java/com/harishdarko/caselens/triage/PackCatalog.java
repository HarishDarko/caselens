package com.harishdarko.caselens.triage;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The industry packs this build knows, keyed by pack id. The list of ids is {@code packs/packs.json}, so adding an
 * industry means adding a folder and one id there, with no Java change.
 */
public final class PackCatalog {
    /** Workspaces created before industries existed use this pack. */
    public static final String DEFAULT_PACK = "ev-charging";
    private static final String INDEX = "/packs/packs.json";
    private static PackCatalog builtIn;

    private final Map<String, PackRules> packs;

    PackCatalog(Map<String, PackRules> packs) {
        this.packs = Map.copyOf(packs);
        if (!this.packs.containsKey(DEFAULT_PACK)) throw new IllegalStateException("The default industry pack is missing: " + DEFAULT_PACK);
    }

    /** The packs on the classpath, loaded once. A missing or broken pack file fails here with a clear message. */
    public static synchronized PackCatalog builtIn() {
        if (builtIn == null) builtIn = load();
        return builtIn;
    }

    private static PackCatalog load() {
        JsonNode ids;
        try (InputStream input = PackCatalog.class.getResourceAsStream(INDEX)) {
            if (input == null) throw new IllegalStateException("Industry pack list is missing: " + INDEX);
            ids = new ObjectMapper().readTree(input);
        } catch (IOException | JacksonException exception) {
            throw new IllegalStateException("Industry pack list could not be read: " + INDEX, exception);
        }
        Map<String, PackRules> packs = new LinkedHashMap<>();
        for (JsonNode id : ids) packs.put(id.asText(), PackRules.load(id.asText()));
        return new PackCatalog(packs);
    }

    public PackRules require(String id) {
        PackRules pack = packs.get(id);
        if (pack == null) throw new IllegalArgumentException("Unknown industry pack");
        return pack;
    }

    public PackRules defaultPack() {
        return packs.get(DEFAULT_PACK);
    }

    public Set<String> ids() {
        return packs.keySet();
    }
}
