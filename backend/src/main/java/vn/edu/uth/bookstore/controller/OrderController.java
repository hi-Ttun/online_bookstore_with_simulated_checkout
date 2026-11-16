package vn.edu.uth.bookstore.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vn.edu.uth.bookstore.dto.CheckoutRequest;
import vn.edu.uth.bookstore.dto.OrderReceipt;
import vn.edu.uth.bookstore.dto.OrderSummary;
import vn.edu.uth.bookstore.exception.ApiException;
import vn.edu.uth.bookstore.repository.OrderRepository;
import vn.edu.uth.bookstore.service.CheckoutService;

/** Checkout và tra cứu đơn của khách hàng. Mã khách hàng lấy từ claim {@code sub} của JWT. */
@Tag(name = "Orders")
@RestController
@RequestMapping("/api")
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderRepository orders;

    public OrderController(CheckoutService checkoutService, OrderRepository orders) {
        this.checkoutService = checkoutService;
        this.orders = orders;
    }

    @Operation(summary = "Đặt hàng (idempotent)", description = """
            Idempotency-Key: sinh một lần cho mỗi lượt thanh toán và giữ nguyên khi gửi lại.
            Gửi lại cùng key và cùng nội dung trả về đơn cũ (200, header Idempotent-Replayed: true).
            Thanh toán xử lý bất đồng bộ; theo dõi trạng thái qua GET /api/orders/{id}.""")
    @ApiResponse(responseCode = "201", description = "Tạo đơn mới, status = PENDING_PAYMENT")
    @ApiResponse(responseCode = "200", description = "Request lặp lại cùng key và cùng nội dung: trả biên nhận cũ")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = """
            IDEMPOTENCY_KEY_REUSED: cùng key nhưng khác nội dung (sách, số lượng hoặc kịch bản thanh toán).
            OUT_OF_STOCK: không đủ hàng (details.productId); không có đơn nào được tạo.""")
    @PostMapping("/checkout")
    public ResponseEntity<OrderReceipt> checkout(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @AuthenticationPrincipal Jwt user,
            @Valid @RequestBody CheckoutRequest request) {
        CheckoutService.Result result = checkoutService.checkout(user.getSubject(), idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.receipt());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.receipt());
    }

    @Operation(summary = "Đơn hàng của tôi (100 đơn gần nhất)")
    @GetMapping("/orders")
    public List<OrderSummary> myOrders(@AuthenticationPrincipal Jwt user) {
        return orders.findByCustomer(user.getSubject());
    }

    @Operation(summary = "Biên nhận một đơn của tôi (đơn của người khác trả 404)")
    @GetMapping("/orders/{id}")
    public OrderReceipt getOrder(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        return orders.findReceipt(id, user.getSubject())
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
    }
}
