package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.triage.OutboxEventRepository;
import com.harishdarko.caselens.triage.TriageJobRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "CASELENS_DEMO_PASSCODE=reviewer",
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:triage-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TriageApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TriageJobRepository jobs;
    @Autowired OutboxEventRepository events;

    @Test
    void createsOneProcessingJobAndReturnsItsStatusWithoutDuplicatingTheRequest() throws Exception {
        String token = session();
        String ticket = createTicket(token);

        String first = mvc.perform(post("/api/tickets/{id}/triage", ticket)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Correlation-ID", "corr-api-1"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Correlation-ID", "corr-api-1"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readTree(first).get("jobId").asText();

        mvc.perform(post("/api/tickets/{id}/triage", ticket)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId));

        String processing = mvc.perform(get("/api/tickets/{id}/processing", ticket)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String eventId = objectMapper.readTree(processing).get("job").get("eventId").asText();
        assertThat(events.findById(UUID.fromString(eventId)).orElseThrow().getPublishedAt()).isNotNull();

        mvc.perform(get("/api/operations/failures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    private String session() throws Exception {
        String body = mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passcode\":\"reviewer\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String createTicket(String token) throws Exception {
        String body = mvc.perform(post("/api/tickets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Payment issue\",\"message\":\"Payment was captured but the session never started for this ticket.\",\"channel\":\"WEB\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("id").asText();
    }
}
