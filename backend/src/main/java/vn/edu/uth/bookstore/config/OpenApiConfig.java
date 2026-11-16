package vn.edu.uth.bookstore.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/** Thông tin OpenAPI (Swagger UI tại /swagger-ui.html) và khai báo xác thực bearer JWT. */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Bookstore API", version = "v1",
                description = "Online Bookstore with Simulated Checkout"),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
