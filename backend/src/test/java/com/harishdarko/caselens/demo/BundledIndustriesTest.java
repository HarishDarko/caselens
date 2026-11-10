package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The frontend ships the industry list in its bundle, so the launch button works before a cold API answers. That copy
 * must be the list this API serves. To refresh it after a pack change, run this test with -Dcaselens.industries.write=true.
 */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:bundled-industries;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BundledIndustriesTest {
    private static final Path BUNDLED = Path.of("..", "frontend", "src", "industries.json");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void theFrontendBundleHasTheSameIndustryListAsTheApi() throws Exception {
        String served = mvc.perform(get("/api/demo/session")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode api = objectMapper.readTree(served);
        if (Boolean.getBoolean("caselens.industries.write")) {
            Files.writeString(BUNDLED, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(api) + "\n");
        }

        JsonNode bundled = objectMapper.readTree(Files.readString(BUNDLED, StandardCharsets.UTF_8));

        assertThat(bundled).isEqualTo(api);
    }
}
