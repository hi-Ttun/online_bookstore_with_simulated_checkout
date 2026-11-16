package vn.edu.uth.bookstore.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.edu.uth.bookstore.dto.AdminOrderRow;
import vn.edu.uth.bookstore.dto.InventoryMovementRow;
import vn.edu.uth.bookstore.dto.InventoryRow;
import vn.edu.uth.bookstore.dto.OpsSummary;
import vn.edu.uth.bookstore.dto.OrderReceipt;
import vn.edu.uth.bookstore.dto.ReconcileResult;
import vn.edu.uth.bookstore.exception.ApiException;
import vn.edu.uth.bookstore.model.OrderStatus;
import vn.edu.uth.bookstore.repository.AdminQueryRepository;
import vn.edu.uth.bookstore.repository.OrderRepository;
import vn.edu.uth.bookstore.repository.OutboxRepository;
import vn.edu.uth.bookstore.repository.PaymentAttemptRepository;
import vn.edu.uth.bookstore.service.ReconciliationService;

/** API cho Store Operator; yêu cầu role OPERATOR (cấu hình trong SecurityConfig). */
@Tag(name = "Store operator")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminQueryRepository adminQueries;
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final PaymentAttemptRepository payments;
    private final ReconciliationService reconciliation;

    public AdminController(AdminQueryRepository adminQueries, OrderRepository orders, OutboxRepository outbox,
            PaymentAttemptRepository payments, ReconciliationService reconciliation) {
        this.adminQueries = adminQueries;
        this.orders = orders;
        this.outbox = outbox;
        this.payments = payments;
        this.reconciliation = reconciliation;
    }

    @Operation(summary = "Danh sách đơn (200 đơn mới nhất); status=PAID là danh sách cần giao")
    @GetMapping("/orders")
    public List<AdminOrderRow> listOrders(@RequestParam(required = false) OrderStatus status) {
        return adminQueries.findOrders(status);
    }

    @Operation(summary = "Biên nhận của một đơn bất kỳ")
    @GetMapping("/orders/{id}")
    public OrderReceipt getOrder(@PathVariable UUID id) {
        return orders.findReceiptForOperator(id).orElseThrow(AdminController::orderNotFound);
    }

    @Operation(summary = "Đánh dấu đã giao: PAID -> FULFILLED",
            description = "Gọi lại trên đơn đã FULFILLED vẫn trả 200.")
    @ApiResponse(responseCode = "200", description = "Biên nhận với status FULFILLED")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: đơn chưa thanh toán thành công (details.status)")
    @PostMapping("/orders/{id}/fulfill")
    public OrderReceipt fulfill(@PathVariable UUID id) {
        orders.markFulfilled(id);
        // Đọc lại để xử lý idempotent: FULFILLED (vừa cập nhật hoặc từ trước) là thành công.
        OrderReceipt receipt = orders.findReceiptForOperator(id).orElseThrow(AdminController::orderNotFound);
        if (receipt.status() != OrderStatus.FULFILLED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "Chỉ giao được đơn đã thanh toán (PAID). Trạng thái hiện tại: " + receipt.status(),
                    Map.of("status", receipt.status().name()));
        }
        return receipt;
    }

    @Operation(summary = "Tồn kho: onHand, reserved, available")
    @GetMapping("/inventory")
    public List<InventoryRow> inventory() {
        return adminQueries.findInventory();
    }

    @Operation(summary = "Lịch sử biến động kho (200 dòng mới nhất)")
    @GetMapping("/inventory/movements")
    public List<InventoryMovementRow> movements(@RequestParam(required = false) Long productId) {
        return adminQueries.findMovements(productId);
    }

    @Operation(summary = "Tình trạng vận hành: outbox tồn đọng, thanh toán chờ đối soát, số đơn theo trạng thái")
    @GetMapping("/ops/summary")
    public OpsSummary opsSummary() {
        OutboxRepository.Stats stats = outbox.stats();
        return new OpsSummary(stats.pending(), stats.oldestAgeSeconds(), payments.countUnknown(),
                adminQueries.countOrdersByStatus());
    }

    @Operation(summary = "Đối soát ngay các thanh toán UNKNOWN đã chờ đủ thời gian tối thiểu")
    @PostMapping("/reconcile")
    public ReconcileResult reconcile() {
        return new ReconcileResult(reconciliation.reconcileNow());
    }

    private static ApiException orderNotFound() {
        return ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng");
    }
}
