package vn.edu.uth.bookstore.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import vn.edu.uth.bookstore.model.OrderStatus;
import vn.edu.uth.bookstore.model.PaymentScenario;

/** Một dòng trong danh sách đơn của Store Operator. itemCount = tổng số cuốn trong đơn. */
public record AdminOrderRow(
        UUID orderId,
        String customerId,
        OrderStatus status,
        PaymentScenario paymentScenario,
        long totalMinor,
        String currency,
        int itemCount,
        OffsetDateTime createdAt,
        OffsetDateTime fulfilledAt) {
}
