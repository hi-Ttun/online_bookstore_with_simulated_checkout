package vn.edu.uth.bookstore.controller;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import vn.edu.uth.bookstore.dto.PubSubPushRequest;
import vn.edu.uth.bookstore.logging.CorrelationIdFilter;
import vn.edu.uth.bookstore.service.OrderEventService;

/**
 * Endpoint nhận message từ Pub/Sub push subscription (worker).
 * 2xx = ack, chỉ trả về sau khi transaction đã commit; mã khác = Pub/Sub gửi lại theo retry policy và chuyển
 * sang dead-letter topic khi vượt số lần tối đa.
 *
 * <p>Chỉ đăng ký khi {@code app.worker.enabled=true}. Service worker trên Cloud Run phải bật "Require authentication";
 * Pub/Sub gửi kèm OIDC token của service account có quyền {@code roles/run.invoker}.
 */
@Hidden
@RestController
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class PubSubPushController {

    private static final Logger log = LoggerFactory.getLogger(PubSubPushController.class);

    private final OrderEventService orderEvents;

    public PubSubPushController(OrderEventService orderEvents) {
        this.orderEvents = orderEvents;
    }

    @PostMapping("/internal/pubsub/push")
    public ResponseEntity<Void> receive(@RequestBody PubSubPushRequest request) {
        Map<String, String> attrs = request.message() == null || request.message().attributes() == null
                ? Map.of() : request.message().attributes();
        UUID eventId = parseUuid(attrs.get("event_id"));
        UUID orderId = parseUuid(attrs.get("aggregate_id"));
        String eventType = attrs.get("event_type");
        if (eventId == null || orderId == null || eventType == null) {
            // Message không hợp lệ: trả 400 để sau số lần retry tối đa, message vào dead-letter topic.
            log.error("event=worker_bad_message attributes={}", attrs);
            return ResponseEntity.badRequest().build();
        }
        // Tiếp nối correlation ID của request checkout gốc (relay gửi kèm trong attribute của message).
        String correlationId = CorrelationIdFilter.sanitize(attrs.get("correlation_id"));
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        MDC.put("orderId", orderId.toString());
        MDC.put("eventId", eventId.toString());
        log.info("event=worker_received eventId={} eventType={} orderId={} deliveryAttempt={}",
                eventId, eventType, orderId, request.deliveryAttempt());
        orderEvents.handle(eventId, eventType, orderId);
        return ResponseEntity.noContent().build();
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
