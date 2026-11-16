package vn.edu.uth.bookstore.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.uth.bookstore.dto.AdminOrderRow;
import vn.edu.uth.bookstore.dto.InventoryMovementRow;
import vn.edu.uth.bookstore.dto.InventoryRow;
import vn.edu.uth.bookstore.model.OrderStatus;

/** Truy vấn chỉ đọc cho màn hình Store Operator. Danh sách giới hạn 200 dòng mới nhất. */
@Repository
public class AdminQueryRepository {

    private static final int MAX_ROWS = 200;

    private final JdbcClient jdbc;

    public AdminQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** status = null: lấy mọi trạng thái. */
    public List<AdminOrderRow> findOrders(OrderStatus status) {
        String sql = """
                SELECT o.id AS order_id, o.customer_id, o.status, o.payment_scenario, o.total_minor, o.currency,
                       (SELECT COALESCE(sum(l.quantity), 0) FROM order_line l WHERE l.order_id = o.id) AS item_count,
                       o.created_at, o.fulfilled_at
                FROM orders o
                """ + (status == null ? "" : "WHERE o.status = :status ") + """
                ORDER BY o.created_at DESC
                LIMIT :limit
                """;
        JdbcClient.StatementSpec query = jdbc.sql(sql).param("limit", MAX_ROWS);
        if (status != null) {
            query = query.param("status", status.name());
        }
        return query.query(AdminOrderRow.class).list();
    }

    public List<InventoryRow> findInventory() {
        return jdbc.sql("""
                SELECT p.id AS product_id, p.sku, p.title, i.on_hand, i.reserved,
                       i.on_hand - i.reserved AS available, i.updated_at
                FROM inventory i JOIN product p ON p.id = i.product_id
                ORDER BY p.id
                """)
                .query(InventoryRow.class)
                .list();
    }

    /** productId = null: lấy mọi sách. */
    public List<InventoryMovementRow> findMovements(Long productId) {
        String sql = """
                SELECT m.id, m.product_id, p.sku, m.order_id, m.kind, m.quantity, m.created_at
                FROM inventory_movement m JOIN product p ON p.id = m.product_id
                """ + (productId == null ? "" : "WHERE m.product_id = :productId ") + """
                ORDER BY m.id DESC
                LIMIT :limit
                """;
        JdbcClient.StatementSpec query = jdbc.sql(sql).param("limit", MAX_ROWS);
        if (productId != null) {
            query = query.param("productId", productId);
        }
        return query.query(InventoryMovementRow.class).list();
    }

    public Map<String, Long> countOrdersByStatus() {
        return jdbc.sql("SELECT status, count(*) AS n FROM orders GROUP BY status ORDER BY status")
                .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}
