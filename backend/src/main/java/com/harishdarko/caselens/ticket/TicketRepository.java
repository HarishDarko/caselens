package com.harishdarko.caselens.ticket;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {
    Optional<Ticket> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    long deleteByWorkspaceId(UUID workspaceId);
}
