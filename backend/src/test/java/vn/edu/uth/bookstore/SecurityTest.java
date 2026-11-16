package vn.edu.uth.bookstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Kiểm thử phân quyền bằng cách gọi trực tiếp API được phép và bị cấm. */
class SecurityTest extends IntegrationTestBase {

    @Test
    @DisplayName("Danh mục sách công khai")
    void catalogIsPublic() {
        assertThat(call("GET", "/api/products", null, Map.of()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Không token -> 401 JSON có correlationId")
    void missingToken_401() {
        Resp r = call("GET", "/api/orders", null, Map.of("X-Correlation-Id", "test-401"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHORIZED");
        assertThat(r.body().path("error").path("correlationId").asString()).isEqualTo("test-401");
    }

    @Test
    @DisplayName("Token hết hạn -> 401")
    void expiredToken_401() {
        String expired = call("POST", "/dev/token?user=alice&expiresInSeconds=-3600", null, Map.of())
                .body().get("accessToken").asString();
        assertThat(call("GET", "/api/orders", null, Map.of("Authorization", "Bearer " + expired)).status())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("Khách hàng gọi API operator -> 403; operator -> 200")
    void adminRequiresOperator() {
        assertThat(call("GET", "/api/admin/orders", null, auth("alice")).status()).isEqualTo(403);
        assertThat(call("POST", "/api/admin/reconcile", null, auth("alice")).status()).isEqualTo(403);
        assertThat(call("GET", "/api/admin/orders", null, auth("operator")).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Khách hàng không xem được đơn của người khác")
    void ordersArePrivate() {
        String orderId = checkout("alice", UUID.randomUUID().toString(), 6, 1, "SUCCESS").body().get("orderId").asString();
        assertThat(call("GET", "/api/orders/" + orderId, null, auth("alice")).status()).isEqualTo(200);
        assertThat(call("GET", "/api/orders/" + orderId, null, auth("bob")).status()).isEqualTo(404);
    }
}
