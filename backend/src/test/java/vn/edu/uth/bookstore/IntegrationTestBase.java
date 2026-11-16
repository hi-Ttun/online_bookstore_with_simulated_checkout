package vn.edu.uth.bookstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lớp nền cho integration test: ứng dụng đầy đủ chạy trên PostgreSQL do Testcontainers khởi tạo.
 * <ul>
 * <li>Profile local để dùng token phát triển (POST /dev/token); datasource lấy từ container qua @ServiceConnection.</li>
 * <li>Tắt relay, đối soát định kỳ và thống kê outbox để test điều khiển từng bước, không phụ thuộc thời gian.
 * {@link #deliverPendingEvents()} thay vai trò relay + Pub/Sub bằng cách gọi trực tiếp endpoint của worker.</li>
 * <li>Trước mỗi test chạy lại db/seed.sql để dữ liệu luôn ở trạng thái chuẩn.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.relay.enabled=false",
        "app.reconciler.enabled=false",
        "app.outbox-stats.enabled=false",
        "app.worker.enabled=true" })
@ActiveProfiles("local")
public abstract class IntegrationTestBase {

    // Container dùng chung cho mọi lớp test, dừng khi JVM kết thúc.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static {
        // Test không đi qua BookstoreApplication.main() nên cần tự chuẩn hóa múi giờ về UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        POSTGRES.start();
    }

    public record Resp(int status, JsonNode body, Map<String, List<String>> headers) {
        public String code() {
            return body.path("error").path("code").asString();
        }

        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    @BeforeEach
    void reseed() {
        new ResourceDatabasePopulator(new FileSystemResource("db/seed.sql")).execute(dataSource);
    }

    // ---------- HTTP ----------

    protected Resp call(String method, String path, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            headers.forEach(req::header);
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode parsed = res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
            return new Resp(res.statusCode(), parsed, res.headers().map());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Map<String, String> auth(String user) {
        String token = tokens.computeIfAbsent(user,
                u -> call("POST", "/dev/token?user=" + u, null, Map.of()).body().get("accessToken").asString());
        return Map.of("Authorization", "Bearer " + token);
    }

    protected Resp checkout(String user, String idempotencyKey, long productId, int quantity, String scenario) {
        Map<String, String> headers = new java.util.HashMap<>(auth(user));
        headers.put("Idempotency-Key", idempotencyKey);
        return call("POST", "/api/checkout", Map.of(
                "items", List.of(Map.of("productId", productId, "quantity", quantity)),
                "paymentScenario", scenario), headers);
    }

    protected String orderStatus(String orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = CAST(:id AS uuid)").param("id", orderId)
                .query(String.class).single();
    }

    // ---------- Mô phỏng relay + Pub/Sub ----------

    /** Chuyển mọi sự kiện chưa publish tới worker; lặp lại vì worker sinh thêm sự kiện mới (payment.*). */
    protected void deliverPendingEvents() {
        for (int round = 0; round < 10; round++) {
            List<Map<String, Object>> pending = jdbc.sql("""
                    SELECT id, event_type, aggregate_id, correlation_id FROM outbox_event
                    WHERE published_at IS NULL ORDER BY created_at
                    """).query().listOfRows();
            if (pending.isEmpty()) {
                return;
            }
            for (Map<String, Object> e : pending) {
                jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE id = :id").param("id", e.get("id")).update();
                assertThat(push(e.get("id").toString(), (String) e.get("event_type"), (String) e.get("aggregate_id")))
                        .as("worker phải ack sự kiện %s", e.get("event_type")).isEqualTo(204);
            }
        }
        throw new AssertionError("Outbox không cạn sau 10 vòng");
    }

    /** Gửi một message theo định dạng Pub/Sub push tới worker (dùng cho cả gửi mới và gửi lại). */
    protected int push(String eventId, String eventType, String orderId) {
        Map<String, Object> envelope = Map.of(
                "message", Map.of(
                        "data", Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)),
                        "messageId", UUID.randomUUID().toString(),
                        "attributes", Map.of("event_id", eventId, "event_type", eventType, "aggregate_id", orderId)),
                "subscription", "projects/test/subscriptions/order-events-worker");
        return call("POST", "/internal/pubsub/push", envelope, Map.of()).status();
    }

    protected String outboxEventId(String orderId, String eventType) {
        return jdbc.sql("SELECT id::text FROM outbox_event WHERE aggregate_id = :orderId AND event_type = :type")
                .param("orderId", orderId).param("type", eventType).query(String.class).single();
    }

    // ---------- Truy vấn tồn kho ----------

    /** Trả về [on_hand, reserved]. */
    protected int[] stock(long productId) {
        return jdbc.sql("SELECT on_hand, reserved FROM inventory WHERE product_id = :id").param("id", productId)
                .query((rs, i) -> new int[] { rs.getInt(1), rs.getInt(2) }).single();
    }

    protected long movements(String orderId, String kind) {
        return jdbc.sql("SELECT count(*) FROM inventory_movement WHERE order_id = CAST(:id AS uuid) AND kind = :kind")
                .param("id", orderId).param("kind", kind).query(Long.class).single();
    }

    protected long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
