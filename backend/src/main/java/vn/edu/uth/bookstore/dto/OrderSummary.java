package vn.edu.uth.bookstore.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import vn.edu.uth.bookstore.model.OrderStatus;

/** Một dòng trong danh sách đơn hàng của khách. */
public record OrderSummary(UUID orderId, OrderStatus status, long totalMinor, String currency, OffsetDateTime createdAt) {
}
