package io.github.joshde3.trackhub.app.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at {@code /swagger-ui.html}, the OpenAPI document at {@code /v3/api-docs}. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "TrackHub API",
        version = "0.1",
        description = "Track parcels across carriers. Send a Google ID token as the bearer token."))
@SecurityScheme(
        name = OpenApiConfiguration.GOOGLE_ID_TOKEN,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "A Google ID token whose audience is TrackHub's OAuth client id")
class OpenApiConfiguration {
    static final String GOOGLE_ID_TOKEN = "google-id-token";
}
