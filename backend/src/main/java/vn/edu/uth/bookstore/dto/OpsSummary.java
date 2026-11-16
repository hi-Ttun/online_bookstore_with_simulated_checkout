package vn.edu.uth.bookstore.dto;

import java.util.Map;

/** Tình trạng vận hành: outbox tồn đọng, thanh toán chờ đối soát, số đơn theo trạng thái. */
public record OpsSummary(
        long outboxPending,
        long outboxOldestAgeSeconds,
        long paymentsUnknown,
        Map<String, Long> ordersByStatus) {
}
