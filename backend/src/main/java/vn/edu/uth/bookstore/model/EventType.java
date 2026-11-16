package vn.edu.uth.bookstore.model;

/** Loại sự kiện ghi vào outbox và phát qua Pub/Sub (thuộc tính {@code event_type} của message). */
public final class EventType {

    public static final String ORDER_CREATED = "order.created";
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";

    private EventType() {
    }
}
