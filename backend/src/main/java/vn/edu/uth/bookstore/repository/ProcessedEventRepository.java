package vn.edu.uth.bookstore.repository;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Khóa chống trùng của consumer: ghi nhận event_id đã xử lý trong cùng transaction với thay đổi dữ liệu. */
@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Trả true nếu đây là lần đầu consumer xử lý eventId, false nếu sự kiện đã được xử lý trước đó. */
    public boolean markProcessed(UUID eventId, String consumer) {
        return jdbc.sql("""
                INSERT INTO processed_event (event_id, consumer) VALUES (:eventId, :consumer)
                ON CONFLICT DO NOTHING
                """)
                .param("eventId", eventId)
                .param("consumer", consumer)
                .update() == 1;
    }
}
