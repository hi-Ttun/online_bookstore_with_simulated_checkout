package vn.edu.uth.bookstore.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.uth.bookstore.model.PaymentScenario;
import vn.edu.uth.bookstore.model.PaymentStatus;

/** Truy cập bảng payment_attempt. */
@Repository
public class PaymentAttemptRepository {

    /** Lần thanh toán UNKNOWN cần đối soát, kèm kịch bản và correlation ID của đơn. */
    public record UnknownAttempt(UUID id, UUID orderId, String operationKey, PaymentScenario paymentScenario,
            String correlationId) {
    }

    private final JdbcClient jdbc;

    public PaymentAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** resolved_at được ghi ngay nếu đã có kết quả, để trống nếu status là UNKNOWN. */
    public UUID insert(UUID orderId, String operationKey, long amountMinor, String currency, PaymentStatus status,
            String failureReason) {
        return jdbc.sql("""
                INSERT INTO payment_attempt (order_id, operation_key, amount_minor, currency, status, failure_reason,
                                             resolved_at)
                VALUES (:orderId, :opKey, :amount, :currency, :status, :reason,
                        CASE WHEN :status = 'UNKNOWN' THEN NULL ELSE now() END)
                RETURNING id
                """)
                .param("orderId", orderId)
                .param("opKey", operationKey)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("status", status.name())
                .param("reason", failureReason)
                .query(UUID.class)
                .single();
    }

    /** Lấy và khóa các lần thanh toán UNKNOWN đã chờ quá {@code minAgeSeconds}. */
    public List<UnknownAttempt> lockUnknownOlderThan(int minAgeSeconds, int limit) {
        return jdbc.sql("""
                SELECT pa.id, pa.order_id, pa.operation_key, o.payment_scenario, o.correlation_id
                FROM payment_attempt pa JOIN orders o ON o.id = pa.order_id
                WHERE pa.status = 'UNKNOWN' AND pa.created_at < now() - make_interval(secs => :minAge)
                ORDER BY pa.created_at
                LIMIT :limit
                FOR UPDATE OF pa SKIP LOCKED
                """)
                .param("minAge", minAgeSeconds)
                .param("limit", limit)
                .query(UnknownAttempt.class)
                .list();
    }

    /** Ghi kết quả đối soát; chỉ áp dụng cho bản ghi còn UNKNOWN nên không ghi đè kết quả đã có. */
    public boolean resolve(UUID attemptId, PaymentStatus status) {
        return jdbc.sql("""
                UPDATE payment_attempt SET status = :status, resolved_at = now(), updated_at = now()
                WHERE id = :id AND status = 'UNKNOWN'
                """)
                .param("id", attemptId)
                .param("status", status.name())
                .update() == 1;
    }

    public long countUnknown() {
        return jdbc.sql("SELECT count(*) FROM payment_attempt WHERE status = 'UNKNOWN'").query(Long.class).single();
    }
}
