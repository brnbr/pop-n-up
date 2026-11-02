package com.popnup.popnupbackend.domain.schedule.outbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface OutboxEventRepositoryCustom {

  List<OutboxEvent> claimUnpublished(int limit);

  long markPublished(Collection<Long> ids, String outcome, LocalDateTime at);

  long countUnpublished();

  long deletePublishedBefore(LocalDateTime before);

  long deleteCreatedBefore(LocalDateTime before);
}
