package vn.edu.uth.bookstore.dto;

import java.util.Map;

import vn.edu.uth.bookstore.logging.CorrelationIdFilter;

/**
 * Định dạng lỗi chung của toàn bộ API:
 * {@code {"error": {"code", "message", "correlationId", "details"}}}.
 */
public record ApiErrorResponse(Detail error) {

    public record Detail(String code, String message, String correlationId, Map<String, Object> details) {
    }

    public static ApiErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ApiErrorResponse(new Detail(code, message, CorrelationIdFilter.current(), details));
    }
}
