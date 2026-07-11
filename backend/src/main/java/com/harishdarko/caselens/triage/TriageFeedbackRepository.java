package com.harishdarko.caselens.triage;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TriageFeedbackRepository extends JpaRepository<TriageFeedback, UUID> {
    List<TriageFeedback> findByWorkspaceIdOrderByCreatedAtAscIdAsc(UUID workspaceId);
    List<TriageFeedback> findByTicketIdAndWorkspaceIdOrderByCreatedAtAscIdAsc(UUID ticketId, UUID workspaceId);
}
