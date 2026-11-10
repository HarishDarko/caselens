package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Opening a workspace in one call: POST /api/demo/session?seed=true seeds the demo tickets and returns them with the
 * runtime summary, so the page does not need reset, the ticket list and the runtime call before it can show the inbox.
 * A client that does not ask for it gets the session exactly as before.
 */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:demo-workspace-open-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoWorkspaceOpenApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void aSeededSessionReturnsTheSameTicketsAndRuntimeAsTheSeparateCalls() throws Exception {
        JsonNode opened = json(mvc.perform(post("/api/demo/session?seed=true").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industry\":\"saas-support\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.industry").value("saas-support"))
                .andExpect(jsonPath("$.tickets.length()").value(9))
                .andReturn().getResponse().getContentAsString());
        String bearer = "Bearer " + opened.get("token").asText();

        JsonNode listed = json(mvc.perform(get("/api/tickets?page=0&size=50").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(opened.get("tickets")).isEqualTo(listed.get("content"));
        assertThat(opened.get("tickets").toString()).contains("Whole team locked out after an SSO change")
                .doesNotContain("Payment accepted but charging did not start");

        JsonNode runtime = json(mvc.perform(get("/api/demo/runtime").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(opened.get("runtime")).isEqualTo(runtime);
    }

    @Test
    void aSessionThatDoesNotAskForSeedingIsUnchanged() throws Exception {
        JsonNode session = json(mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industry\":\"ev-charging\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets").doesNotExist())
                .andExpect(jsonPath("$.runtime").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        assertThat(session.propertyNames()).containsExactly("token", "expiresAt", "industry");

        mvc.perform(get("/api/tickets?page=0&size=50").header("Authorization", "Bearer " + session.get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anOldClientStillOpensThroughResetAndAResetAfterASeededOpenDoesNotDuplicate() throws Exception {
        String oldClient = "Bearer " + json(mvc.perform(post("/api/demo/session")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/demo/reset").header("Authorization", oldClient))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seeded").value(9));
        mvc.perform(get("/api/tickets?page=0&size=50").header("Authorization", oldClient))
                .andExpect(jsonPath("$.totalElements").value(9));

        String seeded = "Bearer " + json(mvc.perform(post("/api/demo/session?seed=true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.industry").value("ev-charging"))
                .andExpect(jsonPath("$.tickets.length()").value(9))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/demo/reset").header("Authorization", seeded))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seeded").value(9));
        mvc.perform(get("/api/tickets?page=0&size=50").header("Authorization", seeded))
                .andExpect(jsonPath("$.totalElements").value(9));
    }

    @Test
    void anUnknownIndustryIsStillABadRequestAndSeedsNothing() throws Exception {
        mvc.perform(post("/api/demo/session?seed=true").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industry\":\"banking\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.tickets").doesNotExist());
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }
}
