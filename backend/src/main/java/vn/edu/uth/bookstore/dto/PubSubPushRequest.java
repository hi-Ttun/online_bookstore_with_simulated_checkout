package vn.edu.uth.bookstore.dto;

import java.util.Map;

/** Thân request Pub/Sub push subscription gửi tới worker. {@code message.data} là payload mã hóa base64. */
public record PubSubPushRequest(Message message, String subscription, Integer deliveryAttempt) {

    public record Message(String data, Map<String, String> attributes, String messageId) {
    }
}
