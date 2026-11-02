package com.popnup.popnupbackend.domain.schedule.outbox;

import static com.popnup.popnupbackend.domain.schedule.outbox.QOutboxEvent.outboxEvent;

import com.popnup.popnupbackend.global.support.PessimisticLockSupport;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class OutboxEventRepositoryCustomImpl implements OutboxEventRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public List<OutboxEvent> claimUnpublished(int limit) {
    return PessimisticLockSupport.fetchForUpdateSkipLocked(
        queryFactory
            .selectFrom(outboxEvent)
            .where(outboxEvent.publishedAt.isNull())
            .orderBy(outboxEvent.id.asc())
            .limit(limit));
  }

  @Override
  public long markPublished(Collection<Long> ids, String outcome, LocalDateTime at) {
    return queryFactory
        .update(outboxEvent)
        .set(outboxEvent.publishedAt, at)
        .set(outboxEvent.outcome, outcome)
        .where(outboxEvent.id.in(ids), outboxEvent.publishedAt.isNull())
        .execute();
  }

  @Override
  public long countUnpublished() {
    Long count =
        queryFactory
            .select(outboxEvent.count())
            .from(outboxEvent)
            .where(outboxEvent.publishedAt.isNull())
            .fetchOne();
    return count == null ? 0 : count;
  }

  @Override
  public long deletePublishedBefore(LocalDateTime before) {
    return queryFactory.delete(outboxEvent).where(outboxEvent.publishedAt.lt(before)).execute();
  }

  @Override
  public long deleteCreatedBefore(LocalDateTime before) {
    return queryFactory.delete(outboxEvent).where(outboxEvent.createdAt.lt(before)).execute();
  }
}
