package vn.edu.uth.bookstore.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import vn.edu.uth.bookstore.model.OrderStatus;
import vn.edu.uth.bookstore.model.PaymentScenario;
import vn.edu.uth.bookstore.model.PaymentStatus;

/** Biên nhận đơn hàng: thông tin đơn, các dòng sách và lịch sử thanh toán. */
public record OrderReceipt(
        UUID orderId,
        OrderStatus status,
        PaymentScenario paymentScenario,
        long totalMinor,
        String currency,
        OffsetDateTime createdAt,
        List<Line> lines,
        List<PaymentAttemptView> paymentAttempts) {

    public record Line(long productId, String title, int quantity, long unitPriceMinor, long lineTotalMinor) {
    }

    public record PaymentAttemptView(PaymentStatus status, OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
    }
}
