package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harishdarko.caselens.triage.Category;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** Categories keep their JSON shape, and feedback may only use a category of the workspace's industry pack. */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:pack-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PackApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void aCategoryIsWrittenAndReadAsAPlainString() {
        assertThat(objectMapper.writeValueAsString(Category.of("BILLING"))).isEqualTo("\"BILLING\"");
        assertThat(objectMapper.readValue("\"CONNECTIVITY\"", Category.class)).isEqualTo(Category.of("CONNECTIVITY"));
        assertThatThrownBy(() -> objectMapper.readValue("\"billing\"", Category.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void feedbackWithACategoryOutsideTheWorkspacePackIsABadRequest() throws Exception {
        String token = session();
        String ticketId = scenario(token);

        for (String category : new String[] {"LOGIN", "billing", "NOT A CATEGORY"}) {
            mvc.perform(post("/api/tickets/{id}/feedback", ticketId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"category\":\"" + category + "\",\"note\":\"Category check\"}"))
                    .andExpect(status().isBadRequest());
        }
        // Control: an EV category passes the check and fails later, because the ticket has no triage result yet.
        mvc.perform(post("/api/tickets/{id}/feedback", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"BILLING\",\"note\":\"Category check\"}"))
                .andExpect(status().isConflict());
    }

    private String session() throws Exception {
        String body = mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String scenario(String token) throws Exception {
        String body = mvc.perform(post("/api/demo/scenarios/payment-captured-session-not-started")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
