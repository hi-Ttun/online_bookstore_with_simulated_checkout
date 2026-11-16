package vn.edu.uth.bookstore.model;

/** Trạng thái một lần thanh toán. UNKNOWN: cổng thanh toán không phản hồi, chờ đối soát. */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
