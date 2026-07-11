package com.harishdarko.caselens.triage;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelInvocationRepository extends JpaRepository<ModelInvocation, UUID> {}
