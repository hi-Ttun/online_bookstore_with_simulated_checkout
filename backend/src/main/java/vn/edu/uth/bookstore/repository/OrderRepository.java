package vn.edu.uth.bookstore.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.uth.bookstore.dto.OrderReceipt;
import vn.edu.uth.bookstore.dto.OrderSummary;
import vn.edu.uth.bookstore.model.OrderStatus;
import vn.edu.uth.bookstore.model.PaymentScenario;

/** Truy cập bảng orders và order_line; đọc payment_attempt để lập biên nhận. */
@Repository
public class OrderRepository {

    public record PriceRow(long id, String title, long priceMinor, String currency) {
    }

    public record ExistingOrder(UUID id, String requestHash) {
    }

    public record PaymentInfo(UUID id, OrderStatus status, PaymentScenario paymentScenario, long totalMinor,
            String currency) {
    }

    public record LineQuantity(long productId, int quantity) {
    }

    private record Header(UUID id, OrderStatus status, PaymentScenario paymentScenario, long totalMinor,
            String currency, OffsetDateTime createdAt) {
    }

    private static final String SELECT_HEADER = """
            SELECT id, status, payment_scenario, total_minor, currency, created_at
            FROM orders WHERE id = :id
            """;

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Giá hiện hành theo productId; sách không tồn tại sẽ không có trong kết quả. */
    public Map<Long, PriceRow> findPrices(Collection<Long> productIds) {
        return jdbc.sql("SELECT id, title, price_minor, currency FROM product WHERE id IN (:ids)")
                .param("ids", productIds)
                .query(PriceRow.class)
                .list().stream()
                .collect(Collectors.toMap(PriceRow::id, Function.identity()));
    }

    /**
     * Tạo đơn mới ở trạng thái PENDING_PAYMENT. Trả về empty nếu cặp (customer_id, idempotency_key) đã tồn tại.
     * Khi hai request cùng key chạy đồng thời, PostgreSQL buộc request sau chờ request trước commit/rollback
     * rồi mới quyết định, nên chỉ một đơn được tạo.
     */
    public Optional<UUID> insertIfAbsent(String customerId, String idempotencyKey, String requestHash,
            PaymentScenario scenario, long totalMinor, String currency, String correlationId) {
        return jdbc.sql("""
                INSERT INTO orders (customer_id, idempotency_key, request_hash, status, payment_scenario,
                                    total_minor, currency, correlation_id)
                VALUES (:customerId, :key, :hash, 'PENDING_PAYMENT', :scenario, :total, :currency, :correlationId)
                ON CONFLICT (customer_id, idempotency_key) DO NOTHING
                RETURNING id
                """)
                .param("customerId", customerId)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("scenario", scenario.name())
                .param("total", totalMinor)
                .param("currency", currency)
                .param("correlationId", correlationId)
                .query(UUID.class)
                .optional();
    }

    public ExistingOrder findByIdempotencyKey(String customerId, String idempotencyKey) {
        return jdbc.sql("SELECT id, request_hash FROM orders WHERE customer_id = :customerId AND idempotency_key = :key")
                .param("customerId", customerId)
                .param("key", idempotencyKey)
                .query(ExistingOrder.class)
                .single();
    }

    public void insertLine(UUID orderId, long productId, int quantity, long unitPriceMinor) {
        jdbc.sql("""
                INSERT INTO order_line (order_id, product_id, quantity, unit_price_minor, line_total_minor)
                VALUES (:orderId, :productId, :qty, :unit, :total)
                """)
                .param("orderId", orderId)
                .param("productId", productId)
                .param("qty", quantity)
                .param("unit", unitPriceMinor)
                .param("total", Math.multiplyExact(unitPriceMinor, quantity))
                .update();
    }

    /** Biên nhận của đơn thuộc về customerId; đơn của khách khác trả về empty. */
    public Optional<OrderReceipt> findReceipt(UUID orderId, String customerId) {
        return toReceipt(jdbc.sql(SELECT_HEADER + " AND customer_id = :customerId")
                .param("id", orderId)
                .param("customerId", customerId)
                .query(Header.class)
                .optional());
    }

    public Optional<OrderReceipt> findReceiptForOperator(UUID orderId) {
        return toReceipt(jdbc.sql(SELECT_HEADER).param("id", orderId).query(Header.class).optional());
    }

    public List<OrderSummary> findByCustomer(String customerId) {
        return jdbc.sql("""
                SELECT id AS order_id, status, total_minor, currency, created_at
                FROM orders WHERE customer_id = :customerId
                ORDER BY created_at DESC LIMIT 100
                """)
                .param("customerId", customerId)
                .query(OrderSummary.class)
                .list();
    }

    public Optional<PaymentInfo> findPaymentInfo(UUID orderId) {
        return jdbc.sql("SELECT id, status, payment_scenario, total_minor, currency FROM orders WHERE id = :id")
                .param("id", orderId)
                .query(PaymentInfo.class)
                .optional();
    }

    /**
     * Chuyển trạng thái có điều kiện: chỉ cập nhật khi trạng thái hiện tại thuộc {@code from}.
     * Trả false nếu đơn đã ở trạng thái khác, nhờ đó sự kiện lặp lại hoặc đến muộn không gây tác dụng phụ.
     */
    public boolean transition(UUID orderId, Collection<OrderStatus> from, OrderStatus to) {
        return jdbc.sql("""
                UPDATE orders SET status = :to, updated_at = now()
                WHERE id = :id AND status IN (:from)
                """)
                .param("id", orderId)
                .param("from", from.stream().map(Enum::name).toList())
                .param("to", to.name())
                .update() == 1;
    }

    /** PAID -> FULFILLED. Trả false nếu đơn không ở trạng thái PAID. */
    public boolean markFulfilled(UUID orderId) {
        return jdbc.sql("""
                UPDATE orders SET status = 'FULFILLED', fulfilled_at = now(), updated_at = now()
                WHERE id = :id AND status = 'PAID'
                """)
                .param("id", orderId)
                .update() == 1;
    }

    /** Các dòng của đơn, sắp theo productId để khóa tồn kho cùng thứ tự với checkout (tránh deadlock). */
    public List<LineQuantity> findLineQuantities(UUID orderId) {
        return jdbc.sql("SELECT product_id, quantity FROM order_line WHERE order_id = :id ORDER BY product_id")
                .param("id", orderId)
                .query(LineQuantity.class)
                .list();
    }

    private Optional<OrderReceipt> toReceipt(Optional<Header> header) {
        return header.map(h -> new OrderReceipt(h.id(), h.status(), h.paymentScenario(), h.totalMinor(),
                h.currency(), h.createdAt(), findLines(h.id()), findPaymentAttempts(h.id())));
    }

    private List<OrderReceipt.Line> findLines(UUID orderId) {
        return jdbc.sql("""
                SELECT l.product_id, p.title, l.quantity, l.unit_price_minor, l.line_total_minor
                FROM order_line l JOIN product p ON p.id = l.product_id
                WHERE l.order_id = :orderId ORDER BY l.product_id
                """)
                .param("orderId", orderId)
                .query(OrderReceipt.Line.class)
                .list();
    }

    private List<OrderReceipt.PaymentAttemptView> findPaymentAttempts(UUID orderId) {
        return jdbc.sql("""
                SELECT status, created_at, resolved_at FROM payment_attempt
                WHERE order_id = :orderId ORDER BY created_at
                """)
                .param("orderId", orderId)
                .query(OrderReceipt.PaymentAttemptView.class)
                .list();
    }
}
