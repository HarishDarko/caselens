package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CL2-05 through the real transaction: the triage request commits, Spring runs the afterCommit hook, and the
 * REQUIRES_NEW outbox persist fails there. Spring rethrows afterCommit exceptions to the caller, so without the
 * catch in publishAfterCommit the reviewer would get a 500 for a job that is already queued.
 */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:outbox-after-commit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxAfterCommitPersistFailureTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OutboxPublicationService publicationService;

    @Test
    void aPersistFailureInTheAfterCommitHookStillReturnsTheQueuedJob() throws Exception {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(publicationService).persist(any(OutboxEvent.class));
        String token = session();
        String ticketId = scenario(token);

        String body = mvc.perform(post("/api/tickets/{id}/triage", ticketId).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        verify(publicationService).persist(any(OutboxEvent.class));
        JsonNode job = objectMapper.readTree(body);
        assertThat(job.toString()).contains("QUEUED");
        // The job and its outbox row committed before the hook ran; the row is left for the relay to reconcile.
        assertThat(jdbc.queryForObject("select count(*) from triage_job where status = 'QUEUED'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where published_at is null", Integer.class)).isOne();
        JsonNode processing = objectMapper.readTree(mvc.perform(get("/api/tickets/{id}/processing", ticketId)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertThat(processing.get("job").get("status").asText()).isEqualTo("QUEUED");
    }

    private String session() throws Exception {
        String body = mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String scenario(String token) throws Exception {
        String ticket = mvc.perform(post("/api/demo/scenarios/charger-offline-site-wide").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(ticket).get("id").asText();
    }
}
