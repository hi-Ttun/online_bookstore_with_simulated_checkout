package vn.edu.uth.bookstore.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import vn.edu.uth.bookstore.logging.CorrelationIdFilter;

/** CORS cho Frontend chạy khác origin với API. Danh sách origin lấy từ {@code app.cors.allowed-origins}. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST")
                .allowedHeaders("Authorization", "Content-Type", "Idempotency-Key", CorrelationIdFilter.HEADER)
                .exposedHeaders(CorrelationIdFilter.HEADER, "Idempotent-Replayed");
    }
}
