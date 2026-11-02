package com.popnup.popnupbackend.domain.schedule.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository
    extends JpaRepository<OutboxEvent, Long>, OutboxEventRepositoryCustom {}
