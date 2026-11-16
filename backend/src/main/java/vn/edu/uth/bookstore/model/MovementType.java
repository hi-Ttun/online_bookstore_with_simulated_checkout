package vn.edu.uth.bookstore.model;

/**
 * Loại biến động kho.
 * SEED: nhập kho ban đầu. RESERVE: giữ chỗ khi checkout. COMMIT: xuất kho khi thanh toán thành công.
 * RELEASE: trả lại chỗ đã giữ khi thanh toán thất bại.
 */
public enum MovementType {
    SEED,
    RESERVE,
    COMMIT,
    RELEASE
}
