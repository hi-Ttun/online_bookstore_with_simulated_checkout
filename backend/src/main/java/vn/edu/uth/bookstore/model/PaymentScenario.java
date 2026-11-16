package vn.edu.uth.bookstore.model;

/** Kịch bản thanh toán mô phỏng do khách chọn khi checkout. Kết quả thanh toán chỉ phụ thuộc giá trị này. */
public enum PaymentScenario {
    SUCCESS,
    FAIL,
    TIMEOUT
}
