package com.harishdarko.caselens.triage;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TriageAttemptRepository extends JpaRepository<TriageAttempt, UUID> {
    List<TriageAttempt> findByJobIdOrderByAttemptNumberAsc(UUID jobId);
}
