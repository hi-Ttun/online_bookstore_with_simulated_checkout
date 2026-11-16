package vn.edu.uth.bookstore.scheduler;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;

import vn.edu.uth.bookstore.logging.CorrelationIdFilter;
import vn.edu.uth.bookstore.repository.OutboxRepository;

/**
 * Outbox relay: định kỳ đọc các sự kiện chưa gửi trong outbox_event, publish lên Pub/Sub rồi đánh dấu đã gửi.
 *
 * <p>Bảo đảm at-least-once: published_at chỉ được ghi sau khi Pub/Sub xác nhận. Nếu tiến trình dừng giữa hai bước
 * này, sự kiện sẽ được gửi lại ở lần chạy sau; consumer chống trùng bằng {@code event_id}.
 */
@Component
@ConditionalOnProperty(name = "app.relay.enabled", havingValue = "true")
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final OutboxRepository outbox;
    private final Publisher publisher;
    private final TransactionTemplate tx;
    private final int batchSize;

    public OutboxRelayJob(OutboxRepository outbox, Publisher publisher, TransactionTemplate tx,
            @Value("${app.relay.batch-size}") int batchSize) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = tx;
        this.batchSize = batchSize;
        log.info("event=relay_started batchSize={}", batchSize);
    }

    @Scheduled(fixedDelayString = "${app.relay.interval-ms}")
    public void publishPending() {
        // Các bản ghi của lô bị khóa (FOR UPDATE) đến khi transaction kết thúc.
        tx.executeWithoutResult(status -> {
            List<OutboxRepository.PendingEvent> batch = outbox.lockUnpublishedBatch(batchSize);
            for (OutboxRepository.PendingEvent event : batch) {
                if (event.correlationId() != null) {
                    MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
                }
                try {
                    String messageId = publisher.publish(toMessage(event)).get(10, TimeUnit.SECONDS);
                    outbox.markPublished(event.id());
                    log.info("event=outbox_published eventId={} eventType={} orderId={} messageId={}",
                            event.id(), event.eventType(), event.aggregateId(), messageId);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    outbox.markFailed(event.id(), cause.toString());
                    log.warn("event=outbox_publish_failed eventId={} error={}", event.id(), cause.toString());
                    break; // Dừng lô để giữ thứ tự; chu kỳ sau thử lại từ sự kiện này.
                } finally {
                    MDC.remove(CorrelationIdFilter.MDC_KEY);
                }
            }
        });
    }

    private static PubsubMessage toMessage(OutboxRepository.PendingEvent event) {
        PubsubMessage.Builder message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(event.payload()))
                .putAttributes("event_id", event.id().toString())
                .putAttributes("event_type", event.eventType())
                .putAttributes("aggregate_id", event.aggregateId());
        if (event.correlationId() != null) {
            message.putAttributes("correlation_id", event.correlationId());
        }
        return message.build();
    }
}
