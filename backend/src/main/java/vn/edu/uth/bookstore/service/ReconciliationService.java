package vn.edu.uth.bookstore.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.uth.bookstore.logging.CorrelationIdFilter;
import vn.edu.uth.bookstore.model.PaymentStatus;
import vn.edu.uth.bookstore.repository.PaymentAttemptRepository;

/**
 * Đối soát các lần thanh toán UNKNOWN: tra cứu kết quả theo operation_key, cập nhật payment_attempt và phát
 * sự kiện payment.succeeded / payment.failed qua outbox. Việc chốt đơn sau đó do {@link OrderEventService}
 * xử lý như luồng thông thường.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int BATCH_SIZE = 50;

    private final PaymentAttemptRepository payments;
    private final SimulatedPaymentGateway gateway;
    private final OrderEventService orderEvents;
    private final int minAgeSeconds;

    public ReconciliationService(PaymentAttemptRepository payments, SimulatedPaymentGateway gateway,
            OrderEventService orderEvents, @Value("${app.reconciler.min-age-seconds}") int minAgeSeconds) {
        this.payments = payments;
        this.gateway = gateway;
        this.orderEvents = orderEvents;
        this.minAgeSeconds = minAgeSeconds;
    }

    /** Trả về số lần thanh toán đã được xác định kết quả trong lượt này. */
    @Transactional
    public int reconcileNow() {
        List<PaymentAttemptRepository.UnknownAttempt> attempts = payments.lockUnknownOlderThan(minAgeSeconds, BATCH_SIZE);
        String callerCorrelationId = CorrelationIdFilter.current();
        int resolved = 0;
        for (PaymentAttemptRepository.UnknownAttempt attempt : attempts) {
            // Dùng correlation ID của đơn để log đối soát nằm cùng chuỗi với request checkout ban đầu.
            setCorrelationId(attempt.correlationId());
            try {
                PaymentStatus status = gateway.lookup(attempt.operationKey(), attempt.paymentScenario());
                if (payments.resolve(attempt.id(), status)) {
                    orderEvents.publishPaymentResult(attempt.orderId(), attempt.id(), status);
                    resolved++;
                    log.info("event=payment_reconciled orderId={} operationKey={} status={}",
                            attempt.orderId(), attempt.operationKey(), status);
                }
            } finally {
                setCorrelationId(callerCorrelationId);
            }
        }
        long unknownCount = payments.countUnknown();
        log.atInfo().addKeyValue("event", "reconcile_stats").addKeyValue("unknownCount", unknownCount)
                .addKeyValue("resolved", resolved)
                .log("event=reconcile_stats unknownCount={} resolved={}", unknownCount, resolved);
        return resolved;
    }

    private static void setCorrelationId(String correlationId) {
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        } else {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
