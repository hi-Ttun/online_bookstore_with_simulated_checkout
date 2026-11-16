package vn.edu.uth.bookstore.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filter đầu tiên của mọi request (chạy trước Spring Security).
 * <ul>
 * <li>Gán Correlation-ID: lấy từ header {@value #HEADER} hoặc sinh UUID mới, đặt vào MDC và trả lại trong header
 * response. ID này được lưu cùng đơn hàng, đi theo sự kiện Pub/Sub sang worker.</li>
 * <li>Ghi một dòng log {@code event=http_request} (method, path, status, durationMs) khi request kết thúc.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    // Chỉ chấp nhận ký tự an toàn để giá trị từ client không thể chèn dòng giả vào log.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Correlation-ID của luồng đang chạy, null nếu chưa có. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** Trả về giá trị nếu hợp lệ, ngược lại trả null. */
    public static String sanitize(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches() ? candidate : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = sanitize(request.getHeader(HEADER));
        String correlationId = incoming != null ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (!"/healthz".equals(request.getRequestURI())) {
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                log.atInfo()
                        .addKeyValue("event", "http_request")
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", request.getRequestURI())
                        .addKeyValue("status", response.getStatus())
                        .addKeyValue("durationMs", durationMs)
                        .log("event=http_request method={} path={} status={} durationMs={}",
                                request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            }
            MDC.clear(); // Thread của Tomcat được tái sử dụng cho request khác.
        }
    }
}
