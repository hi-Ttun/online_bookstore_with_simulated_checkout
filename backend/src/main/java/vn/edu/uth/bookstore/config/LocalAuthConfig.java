package vn.edu.uth.bookstore.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Xác thực cho môi trường phát triển (profile local): JWT ký HS256 bằng khóa cục bộ {@code app.auth.dev-secret}.
 * Bean JwtDecoder ở đây thay thế cấu hình Firebase; ở profile cloud lớp này không được nạp nên token dev bị từ chối.
 */
@Configuration
@Profile("local")
public class LocalAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalAuthConfig.class);

    private final SecretKey key;

    public LocalAuthConfig(@Value("${app.auth.dev-secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        log.warn("event=dev_auth_enabled message=\"Development tokens are enabled (POST /dev/token)\"");
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder devJwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }
}
