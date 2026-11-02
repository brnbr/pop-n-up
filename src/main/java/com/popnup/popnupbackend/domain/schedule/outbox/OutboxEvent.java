package com.popnup.popnupbackend.domain.schedule.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "outbox_event",
    indexes = @Index(name = "idx_outbox_event_published", columnList = "published_at, id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

  public static final String SEAT_RELEASE = "SEAT_RELEASE";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_type", nullable = false, length = 40)
  private String eventType;

  @Lob
  @Column(nullable = false, length = 65_535)
  private String payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  @Column(length = 10)
  private String outcome;

  private OutboxEvent(String eventType, String payload, LocalDateTime createdAt) {
    this.eventType = eventType;
    this.payload = payload;
    this.createdAt = createdAt;
  }

  public static OutboxEvent of(String eventType, String payload, LocalDateTime createdAt) {
    return new OutboxEvent(eventType, payload, createdAt);
  }
}
