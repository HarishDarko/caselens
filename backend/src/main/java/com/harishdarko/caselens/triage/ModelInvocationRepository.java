package com.harishdarko.caselens.triage;

import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelInvocationRepository extends JpaRepository<ModelInvocation, UUID> {
    @Query("select invocation from ModelInvocation invocation where invocation.ticketId in :ticketIds")
    List<ModelInvocation> findByTicketIds(@Param("ticketIds") List<UUID> ticketIds);
}
