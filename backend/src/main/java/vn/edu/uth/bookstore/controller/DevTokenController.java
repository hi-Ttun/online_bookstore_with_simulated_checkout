package vn.edu.uth.bookstore.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Phát JWT cho môi trường phát triển (profile local), thay cho đăng nhập Firebase.
 * Token có các claim giống token Firebase: sub, email ({@code <user>@demo.local}), email_verified.
 * {@code user=operator} tương ứng tài khoản Store Operator theo cấu hình local.
 */
@Tag(name = "Development")
@RestController
@Profile("local")
public class DevTokenController {

    private final JwtEncoder encoder;

    public DevTokenController(JwtEncoder devJwtEncoder) {
        this.encoder = devJwtEncoder;
    }

    @Operation(summary = "Phát token phát triển (chỉ có ở profile local)")
    @PostMapping("/dev/token")
    public Map<String, Object> token(
            @RequestParam @Pattern(regexp = "[a-z0-9-]{1,40}") String user,
            @RequestParam(defaultValue = "true") boolean emailVerified,
            // Cho phép giá trị âm để tạo token đã hết hạn phục vụ kiểm thử.
            @RequestParam(defaultValue = "3600") @Min(-7200) @Max(86400) long expiresInSeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expiresInSeconds);
        String email = user + "@demo.local";
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("local-dev")
                .subject(user)
                .issuedAt(expiresAt.isBefore(now) ? expiresAt.minusSeconds(60) : now)
                .expiresAt(expiresAt)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return Map.of("accessToken", token, "tokenType", "Bearer", "expiresIn", expiresInSeconds,
                "sub", user, "email", email, "emailVerified", emailVerified);
    }
}
