package vn.edu.uth.bookstore.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.uth.bookstore.dto.ApiErrorResponse;

/**
 * Xác thực bằng JWT (header {@code Authorization: Bearer}) và phân quyền theo role.
 * <ul>
 * <li>Profile cloud: token Firebase Auth / Identity Platform (application-cloud.properties).</li>
 * <li>Profile local: token phát hành bởi {@code POST /dev/token} ({@link LocalAuthConfig}).</li>
 * <li>Mọi người dùng đã xác thực có role CUSTOMER; thêm OPERATOR khi email thuộc {@code app.auth.operator-emails}
 * và {@code email_verified = true}.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private static final String UNAUTHORIZED_MESSAGE = "Cần đăng nhập: thiếu, sai hoặc hết hạn token";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json,
            @Value("${app.auth.operator-emails}") Set<String> operatorEmails) throws Exception {
        Set<String> operators = operatorEmails.stream()
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        http
                // API stateless dùng bearer token, không dùng cookie nên không cần CSRF và session.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .requestMatchers("/healthz", "/error", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        // Chỉ được đăng ký ở profile local.
                        .requestMatchers(HttpMethod.POST, "/dev/token").permitAll()
                        // Chỉ được đăng ký trên service worker; Cloud Run IAM xác thực OIDC token của Pub/Sub.
                        .requestMatchers(HttpMethod.POST, "/internal/pubsub/push").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("OPERATOR")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(toAuthentication(operators)))
                        .authenticationEntryPoint((req, res, e) ->
                                writeError(res, json, 401, "UNAUTHORIZED", UNAUTHORIZED_MESSAGE)))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeError(res, json, 401, "UNAUTHORIZED", UNAUTHORIZED_MESSAGE))
                        .accessDeniedHandler((req, res, e) -> writeError(res, json, 403, "FORBIDDEN",
                                "Tài khoản không có quyền thực hiện thao tác này")));
        return http.build();
    }

    private static Converter<Jwt, AbstractAuthenticationToken> toAuthentication(Set<String> operators) {
        return jwt -> new JwtAuthenticationToken(jwt, authorities(jwt, operators), jwt.getSubject());
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt, Set<String> operators) {
        List<GrantedAuthority> roles = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        String email = jwt.getClaimAsString("email");
        // Bắt buộc email đã xác minh: Firebase cho phép tự đăng ký với email bất kỳ.
        boolean verified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
        if (email != null && verified && operators.contains(email.toLowerCase(Locale.ROOT))) {
            roles.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
        }
        return roles;
    }

    // Lỗi xác thực xảy ra trong filter chain, trước khi tới GlobalExceptionHandler.
    private static void writeError(HttpServletResponse res, JsonMapper json, int status, String code, String message)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        if (status == 401) {
            res.setHeader("WWW-Authenticate", "Bearer");
        }
        json.writeValue(res.getOutputStream(), ApiErrorResponse.of(code, message, Map.of()));
    }
}
