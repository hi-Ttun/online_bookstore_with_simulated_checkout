package vn.edu.uth.bookstore.model;

/**
 * Trạng thái đơn hàng.
 *
 * <pre>
 * PENDING_PAYMENT ──succeeded──► PAID ──fulfill──► FULFILLED
 *        │ └──────failed──────► PAYMENT_FAILED
 *        └─timeout─► PAYMENT_UNKNOWN ──reconcile──► PAID | PAYMENT_FAILED
 * </pre>
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAYMENT_UNKNOWN,
    PAID,
    PAYMENT_FAILED,
    FULFILLED
}
