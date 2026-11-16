package vn.edu.uth.bookstore.repository;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.uth.bookstore.model.MovementType;

/**
 * Thay đổi tồn kho và ghi lịch sử biến động.
 * Còn bán được = on_hand - reserved. Mọi thay đổi là một câu UPDATE có điều kiện, không đọc rồi mới ghi.
 */
@Repository
public class InventoryRepository {

    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Giữ chỗ {@code quantity} cuốn; trả false nếu không đủ hàng.
     * PostgreSQL khóa dòng khi UPDATE nên các giao dịch mua cùng sách được tuần tự hóa: giao dịch sau
     * thấy điều kiện không còn thỏa và nhận 0 dòng.
     */
    public boolean reserve(long productId, int quantity) {
        return jdbc.sql("""
                UPDATE inventory SET reserved = reserved + :qty, updated_at = now()
                WHERE product_id = :productId AND on_hand - reserved >= :qty
                """)
                .param("productId", productId)
                .param("qty", quantity)
                .update() == 1;
    }

    /** Thanh toán thành công: xuất kho phần đã giữ (on_hand và reserved cùng giảm). */
    public void commit(long productId, int quantity) {
        requireOneRow(jdbc.sql("""
                UPDATE inventory SET on_hand = on_hand - :qty, reserved = reserved - :qty, updated_at = now()
                WHERE product_id = :productId AND reserved >= :qty
                """)
                .param("productId", productId)
                .param("qty", quantity)
                .update(), productId);
    }

    /** Thanh toán thất bại: trả lại phần đã giữ. */
    public void release(long productId, int quantity) {
        requireOneRow(jdbc.sql("""
                UPDATE inventory SET reserved = reserved - :qty, updated_at = now()
                WHERE product_id = :productId AND reserved >= :qty
                """)
                .param("productId", productId)
                .param("qty", quantity)
                .update(), productId);
    }

    /** Ràng buộc UNIQUE(order_id, product_id, kind) bảo đảm mỗi loại biến động chỉ ghi một lần cho mỗi đơn. */
    public void insertMovement(long productId, UUID orderId, MovementType kind, int quantity) {
        jdbc.sql("""
                INSERT INTO inventory_movement (product_id, order_id, kind, quantity)
                VALUES (:productId, :orderId, :kind, :qty)
                """)
                .param("productId", productId)
                .param("orderId", orderId)
                .param("kind", kind.name())
                .param("qty", quantity)
                .update();
    }

    // reserved < quantity là dữ liệu sai lệch: rollback để message được gửi lại và cuối cùng vào dead-letter,
    // thay vì để tồn kho âm.
    private static void requireOneRow(int rows, long productId) {
        if (rows != 1) {
            throw new IllegalStateException("Inventory inconsistent for productId=" + productId);
        }
    }
}
