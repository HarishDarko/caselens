package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harishdarko.caselens.triage.QueueMessage;
import com.harishdarko.caselens.triage.TriageWorker;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A visitor picks an industry, gets that industry's demo tickets, and those tickets are triaged with its pack. */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:demo-industry-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoIndustryApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DemoTokenService tokens;
    @Autowired TriageWorker worker;

    @Test
    void listsTheIndustriesWithoutATokenAndEvChargingFirst() throws Exception {
        mvc.perform(get("/api/demo/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultIndustry").value("ev-charging"))
                .andExpect(jsonPath("$.industries.length()").value(3))
                .andExpect(jsonPath("$.industries[0].id").value("ev-charging"))
                .andExpect(jsonPath("$.industries[0].name").value("EV charging"))
                .andExpect(jsonPath("$.industries[0].featuredScenario.key").value("charger-offline-site-wide"))
                .andExpect(jsonPath("$.industries[0].scenarios.length()").value(9))
                .andExpect(jsonPath("$.industries[1].id").value("e-commerce"))
                .andExpect(jsonPath("$.industries[1].featuredScenario.key").value("shop-checkout-down"))
                .andExpect(jsonPath("$.industries[2].id").value("saas-support"))
                .andExpect(jsonPath("$.industries[2].featuredScenario.key").value("saas-sso-lockout"))
                .andExpect(jsonPath("$.industries[2].scenarios.length()").value(9));
    }

    @Test
    void aSessionWithoutABodyIsStillEvCharging() throws Exception {
        mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.industry").value("ev-charging"));
    }

    @Test
    void anUnknownIndustryIsABadRequest() throws Exception {
        mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON).content("{\"industry\":\"banking\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSaasWorkspaceGetsSaasTicketsAndTheyAreTriagedWithTheSaasPack() throws Exception {
        String token = session("saas-support");

        mvc.perform(post("/api/demo/reset").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seeded").value(9));
        String page = mvc.perform(get("/api/tickets?page=0&size=50").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Whole team locked out after an SSO change", "Synthetic provider timeout for retry review")
                .doesNotContain("Payment accepted but charging did not start");

        mvc.perform(post("/api/demo/scenarios/charger-offline-site-wide").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        String ticket = mvc.perform(post("/api/demo/scenarios/saas-sso-lockout").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String ticketId = objectMapper.readTree(ticket).get("id").asText();

        mvc.perform(post("/api/tickets/{id}/triage", ticketId).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        JsonNode job = objectMapper.readTree(mvc.perform(get("/api/tickets/{id}/processing", ticketId)
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString()).get("job");
        worker.process(new QueueMessage(UUID.fromString(job.get("eventId").asText()), tokens.verify(token).workspaceId(),
                UUID.fromString(ticketId), "industry-test", 1));

        mvc.perform(get("/api/tickets/{id}/processing", ticketId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.category").value("LOGIN_ACCESS"))
                .andExpect(jsonPath("$.result.urgency").value("CRITICAL"))
                .andExpect(jsonPath("$.result.policyIds[0]").value("SSO_LOCKOUT"));
    }

    private String session(String industry) throws Exception {
        String body = mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industry\":\"" + industry + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.industry").value(industry))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }
}
