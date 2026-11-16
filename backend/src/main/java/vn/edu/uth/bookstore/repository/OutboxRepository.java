package vn.edu.uth.bookstore.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Bảng outbox_event (Transactional Outbox). {@link #insert} phải được gọi trong cùng transaction với thay đổi
 * nghiệp vụ; relay đọc các bản ghi chưa gửi và publish lên Pub/Sub sau đó.
 */
@Repository
public class OutboxRepository {

    public record PendingEvent(UUID id, String aggregateId, String eventType, String payload, String correlationId) {
    }

    /** Số sự kiện chưa gửi và tuổi (giây) của sự kiện cũ nhất. */
    public record Stats(long pending, long oldestAgeSeconds) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload,
            String correlationId) {
        jdbc.sql("""
                INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload, correlation_id)
                VALUES (:aggType, :aggId, :type, CAST(:payload AS jsonb), :correlationId)
                """)
                .param("aggType", aggregateType)
                .param("aggId", aggregateId)
                .param("type", eventType)
                .param("payload", json.writeValueAsString(payload))
                .param("correlationId", correlationId)
                .update();
    }

    /**
     * Lấy và khóa một lô sự kiện chưa gửi, cũ nhất trước. SKIP LOCKED cho phép nhiều relay chạy song song
     * mà không lấy trùng bản ghi.
     */
    public List<PendingEvent> lockUnpublishedBatch(int limit) {
        return jdbc.sql("""
                SELECT id, aggregate_id, event_type, payload::text AS payload, correlation_id
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query(PendingEvent.class)
                .list();
    }

    public void markPublished(UUID id) {
        jdbc.sql("""
                UPDATE outbox_event
                SET published_at = now(), publish_attempts = publish_attempts + 1, last_error = NULL
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    public void markFailed(UUID id, String error) {
        jdbc.sql("UPDATE outbox_event SET publish_attempts = publish_attempts + 1, last_error = :error WHERE id = :id")
                .param("id", id)
                .param("error", error.length() > 500 ? error.substring(0, 500) : error)
                .update();
    }

    public Stats stats() {
        return jdbc.sql("""
                SELECT count(*) AS pending,
                       COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::bigint AS oldest_age_seconds
                FROM outbox_event
                WHERE published_at IS NULL
                """)
                .query(Stats.class)
                .single();
    }
}
