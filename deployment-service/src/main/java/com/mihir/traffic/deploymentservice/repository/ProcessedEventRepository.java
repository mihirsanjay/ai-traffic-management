package com.mihir.traffic.deploymentservice.repository;

import com.mihir.traffic.deploymentservice.domain.ProcessedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ProcessedEvent}. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {}
