package vn.edu.uth.bookstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ba bài kiểm thử nghiệm thu của đề bài (Test 1, 2, 3) và kịch bản timeout - đối soát.
 * Yêu cầu Docker để Testcontainers khởi tạo PostgreSQL.
 */
class AcceptanceTest extends IntegrationTestBase {

    @Test
    @DisplayName("Test 1: nhiều người mua cuốn cuối cùng -> tối đa 1 đơn thành công")
    void test1_lastCopy_atMostOneSale() throws Exception {
        int buyers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Resp>> results = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String user = "buyer-" + i;
            auth(user); // lấy token trước để các request khởi chạy gần như đồng thời
            results.add(pool.submit(() -> {
                start.await();
                return checkout(user, UUID.randomUUID().toString(), 1, 1, "SUCCESS");
            }));
        }
        start.countDown();
        List<Resp> responses = new ArrayList<>();
        for (Future<Resp> f : results) {
            responses.add(f.get());
        }
        pool.shutdown();

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 409 && r.code().equals("OUT_OF_STOCK")).hasSize(buyers - 1);
        assertThat(stock(1)).containsExactly(1, 1); // giữ đúng 1 cuốn
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event")).as("đơn thất bại không để lại sự kiện").isEqualTo(1);

        deliverPendingEvents();
        String orderId = responses.stream().filter(r -> r.status() == 201).findFirst().orElseThrow()
                .body().get("orderId").asString();
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(stock(1)).as("bán xong: on_hand 0, reserved 0").containsExactly(0, 0);
    }

    @Test
    @DisplayName("Test 2: gửi lại cùng key + cùng nội dung -> 1 đơn; cùng key khác nội dung -> 409")
    void test2_idempotentReplay_andConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        Resp first = checkout("alice", key, 2, 1, "SUCCESS");
        Resp replay = checkout("alice", key, 2, 1, "SUCCESS");

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().get("orderId").asString()).isEqualTo(first.body().get("orderId").asString());

        Resp conflict = checkout("alice", key, 2, 2, "SUCCESS"); // đổi số lượng
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        // 5 request cùng key gửi đồng thời
        String key2 = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(pool.submit(() -> checkout("alice", key2, 5, 1, "SUCCESS")));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Resp> f : futures) {
            statuses.add(f.get().status());
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(201, 200, 200, 200, 200);

        assertThat(count("SELECT count(*) FROM orders")).as("tổng cộng đúng 2 đơn").isEqualTo(2);
        assertThat(stock(2)).containsExactly(5, 1);
        assertThat(stock(5)).containsExactly(20, 1);
    }

    @Test
    @DisplayName("Test 3: thanh toán lỗi -> hoàn kho đúng 1 lần; phát lại sự kiện lỗi -> kho vẫn đúng")
    void test3_paymentFailure_releasesOnce_evenOnReplay() {
        Resp order = checkout("bob", UUID.randomUUID().toString(), 3, 2, "FAIL");
        String orderId = order.body().get("orderId").asString();
        assertThat(stock(3)).containsExactly(3, 2);

        deliverPendingEvents(); // order.created -> payment.failed -> trả lại tồn kho
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(stock(3)).containsExactly(3, 0);
        assertThat(movements(orderId, "RELEASE")).isEqualTo(1);

        String failedEvent = outboxEventId(orderId, "payment.failed");
        for (int i = 0; i < 3; i++) {
            assertThat(push(failedEvent, "payment.failed", orderId)).isEqualTo(204); // Pub/Sub gửi lại message cũ
        }
        assertThat(push(UUID.randomUUID().toString(), "payment.failed", orderId)).isEqualTo(204); // sự kiện mới
        assertThat(push(UUID.randomUUID().toString(), "payment.succeeded", orderId)).isEqualTo(204); // đến muộn

        assertThat(stock(3)).as("kho vẫn đúng sau mọi lần phát lại").containsExactly(3, 0);
        assertThat(movements(orderId, "RELEASE")).isEqualTo(1);
        assertThat(movements(orderId, "COMMIT")).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_FAILED");

        // Phát lại order.created không tạo lần thanh toán thứ hai.
        assertThat(push(outboxEventId(orderId, "order.created"), "order.created", orderId)).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM payment_attempt")).isEqualTo(1);
    }

    @Test
    @DisplayName("Timeout: PAYMENT_UNKNOWN (không hủy), đối soát -> PAID")
    void timeout_isUnknownUntilReconciled() {
        String orderId = checkout("carol", UUID.randomUUID().toString(), 4, 1, "TIMEOUT").body().get("orderId").asString();
        deliverPendingEvents();
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(stock(4)).as("kho vẫn giữ cho khách").containsExactly(10, 1);

        // Chưa đủ thời gian chờ tối thiểu (10 giây) nên chưa được đối soát.
        assertThat(call("POST", "/api/admin/reconcile", null, auth("operator")).body().get("resolved").asInt()).isZero();

        // Lùi created_at thay vì chờ thực tế.
        jdbc.sql("UPDATE payment_attempt SET created_at = now() - interval '1 minute'").update();
        assertThat(call("POST", "/api/admin/reconcile", null, auth("operator")).body().get("resolved").asInt()).isEqualTo(1);

        deliverPendingEvents(); // payment.succeeded do reconciler phát ra
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(stock(4)).containsExactly(9, 0);
    }
}
