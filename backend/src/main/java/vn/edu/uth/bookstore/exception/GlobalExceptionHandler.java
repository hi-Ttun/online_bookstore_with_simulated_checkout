package vn.edu.uth.bookstore.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import vn.edu.uth.bookstore.dto.ApiErrorResponse;

/** Chuyển mọi exception phát sinh trong Controller thành định dạng lỗi chung {@link ApiErrorResponse}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApi(ApiException e) {
        return body(e.getStatus(), e.getCode(), e.getMessage(), e.getDetails());
    }

    /** Sai kiểu tham số (vd /api/products/abc, status không thuộc enum) hoặc JSON không đọc được. */
    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class })
    ResponseEntity<ApiErrorResponse> handleBadInput(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu gửi lên không hợp lệ", Map.of());
    }

    /** Vi phạm ràng buộc Bean Validation; details.errors liệt kê từng trường sai. */
    @ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class })
    ResponseEntity<ApiErrorResponse> handleValidation(Exception e) {
        List<String> errors = new ArrayList<>();
        if (e instanceof MethodArgumentNotValidException m) {
            m.getAllErrors().forEach(err -> errors.add(describe(err, "body")));
        } else if (e instanceof HandlerMethodValidationException h) {
            h.getParameterValidationResults().forEach(r -> r.getResolvableErrors()
                    .forEach(err -> errors.add(describe(err, r.getMethodParameter().getParameterName()))));
        }
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu gửi lên không hợp lệ",
                Map.of("errors", errors));
    }

    /** Lỗi 4xx chuẩn của Spring (404 sai đường dẫn, 405...) giữ nguyên mã; còn lại là lỗi hệ thống 500. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleOther(Exception e) {
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = er.getStatusCode();
            String code = switch (status.value()) {
                case 400 -> "VALIDATION_ERROR";
                case 404 -> "NOT_FOUND";
                default -> "HTTP_" + status.value();
            };
            return body(status, code, er.getBody().getDetail(), Map.of());
        }
        // Không trả chi tiết lỗi nội bộ cho client; stack trace chỉ nằm trong log.
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Lỗi hệ thống", Map.of());
    }

    private static String describe(MessageSourceResolvable err, String fallbackName) {
        return (err instanceof FieldError fe ? fe.getField() : fallbackName) + ": " + err.getDefaultMessage();
    }

    private static ResponseEntity<ApiErrorResponse> body(HttpStatusCode status, String code, String message,
            Map<String, Object> details) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message, details));
    }
}
