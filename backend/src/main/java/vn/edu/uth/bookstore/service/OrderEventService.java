package vn.edu.uth.bookstore.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.uth.bookstore.logging.CorrelationIdFilter;
import vn.edu.uth.bookstore.model.EventType;
import vn.edu.uth.bookstore.model.MovementType;
import vn.edu.uth.bookstore.model.OrderStatus;
import vn.edu.uth.bookstore.model.PaymentStatus;
import vn.edu.uth.bookstore.repository.InventoryRepository;
import vn.edu.uth.bookstore.repository.OrderRepository;
import vn.edu.uth.bookstore.repository.OutboxRepository;
import vn.edu.uth.bookstore.repository.PaymentAttemptRepository;
import vn.edu.uth.bookstore.repository.ProcessedEventRepository;

/**
 * Xử lý sự kiện đơn hàng nhận từ Pub/Sub, mỗi sự kiện trong một transaction.
 *
 * <p>Message có thể được gửi lại nhiều lần, nên việc xử lý được bảo vệ bởi ba lớp:
 * <ol>
 * <li>{@code processed_event}: event_id đã xử lý thì bỏ qua.</li>
 * <li>Chuyển trạng thái có điều kiện: đơn không còn ở trạng thái chờ thì không thay đổi gì.</li>
 * <li>Ràng buộc UNIQUE trên {@code inventory_movement}: không thể COMMIT/RELEASE hai lần cho cùng đơn.</li>
 * </ol>
 */
@Service
public class OrderEventService {

    private static final Logger log = LoggerFactory.getLogger(OrderEventService.class);
    private static final List<OrderStatus> AWAITING_PAYMENT =
            List.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_UNKNOWN);

    private final ProcessedEventRepository processed;
    private final OrderRepository orders;
    private final InventoryRepository inventory;
    private final PaymentAttemptRepository payments;
    private final OutboxRepository outbox;
    private final SimulatedPaymentGateway gateway;

    public OrderEventService(ProcessedEventRepository processed, OrderRepository orders,
            InventoryRepository inventory, PaymentAttemptRepository payments, OutboxRepository outbox,
            SimulatedPaymentGateway gateway) {
        this.processed = processed;
        this.orders = orders;
        this.inventory = inventory;
        this.payments = payments;
        this.outbox = outbox;
        this.gateway = gateway;
    }

    /** Exception bất kỳ làm rollback cả bản ghi processed_event, nên lần gửi lại sẽ được xử lý từ đầu. */
    @Transactional
    public void handle(UUID eventId, String eventType, UUID orderId) {
        switch (eventType) {
            case EventType.ORDER_CREATED -> chargePayment(eventId, orderId);
            case EventType.PAYMENT_SUCCEEDED -> settle(eventId, orderId, true);
            case EventType.PAYMENT_FAILED -> settle(eventId, orderId, false);
            // Loại sự kiện không hỗ trợ: xác nhận (ack) luôn vì gửi lại cũng không xử lý được.
            default -> log.warn("event=worker_unknown_event_type eventId={} eventType={}", eventId, eventType);
        }
    }

    /** Ghi sự kiện kết quả thanh toán vào outbox; dùng chung cho worker và đối soát. */
    public void publishPaymentResult(UUID orderId, UUID attemptId, PaymentStatus status) {
        outbox.insert("order", orderId.toString(),
                status == PaymentStatus.SUCCEEDED ? EventType.PAYMENT_SUCCEEDED : EventType.PAYMENT_FAILED,
                Map.of("orderId", orderId.toString(), "paymentAttemptId", attemptId.toString(),
                        "status", status.name()),
                CorrelationIdFilter.current());
    }

    /** order.created: gọi cổng thanh toán, ghi payment_attempt và phát sự kiện kết quả. */
    private void chargePayment(UUID eventId, UUID orderId) {
        if (!processed.markProcessed(eventId, "payment")) {
            log.info("event=worker_duplicate_skipped eventId={} orderId={} consumer=payment", eventId, orderId);
            return;
        }
        OrderRepository.PaymentInfo order = orders.findPaymentInfo(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            log.info("event=worker_state_skipped orderId={} status={}", orderId, order.status());
            return;
        }

        String operationKey = "order:" + orderId + ":attempt:1";
        try {
            PaymentStatus status = gateway.charge(operationKey, order.paymentScenario(), order.totalMinor());
            UUID attemptId = payments.insert(orderId, operationKey, order.totalMinor(), order.currency(), status,
                    status == PaymentStatus.FAILED ? "card_declined" : null);
            publishPaymentResult(orderId, attemptId, status);
            log.info("event=payment_attempted orderId={} status={}", orderId, status);
        } catch (SimulatedPaymentGateway.PaymentTimeoutException e) {
            // Chưa biết đã trừ tiền hay chưa: giữ nguyên tồn kho đã giữ, chờ đối soát.
            payments.insert(orderId, operationKey, order.totalMinor(), order.currency(), PaymentStatus.UNKNOWN, null);
            orders.transition(orderId, List.of(OrderStatus.PENDING_PAYMENT), OrderStatus.PAYMENT_UNKNOWN);
            log.warn("event=payment_attempted orderId={} status=UNKNOWN reason=timeout", orderId);
        }
    }

    /** payment.succeeded / payment.failed: chốt trạng thái đơn, xuất kho hoặc trả lại phần đã giữ. */
    private void settle(UUID eventId, UUID orderId, boolean succeeded) {
        if (!processed.markProcessed(eventId, "order")) {
            log.info("event=worker_duplicate_skipped eventId={} orderId={} consumer=order", eventId, orderId);
            return;
        }
        OrderStatus newStatus = succeeded ? OrderStatus.PAID : OrderStatus.PAYMENT_FAILED;
        if (!orders.transition(orderId, AWAITING_PAYMENT, newStatus)) {
            log.info("event=worker_state_skipped orderId={} wanted={}", orderId, newStatus);
            return;
        }
        for (OrderRepository.LineQuantity line : orders.findLineQuantities(orderId)) {
            if (succeeded) {
                inventory.commit(line.productId(), line.quantity());
            } else {
                inventory.release(line.productId(), line.quantity());
            }
            inventory.insertMovement(line.productId(), orderId,
                    succeeded ? MovementType.COMMIT : MovementType.RELEASE, line.quantity());
        }
        log.info("event=order_settled orderId={} status={}", orderId, newStatus);
    }
}
