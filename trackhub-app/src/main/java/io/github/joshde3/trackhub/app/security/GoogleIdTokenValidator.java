package io.github.joshde3.trackhub.app.security;

import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The checks Google says a server must make on an ID token, beyond the signature and expiry that
 * Spring already checks: it was issued by Google, it was issued for <em>our</em> app (otherwise any
 * app's Google token would let its user in), and the email is verified.
 */
final class GoogleIdTokenValidator implements OAuth2TokenValidator<Jwt> {

    /** Google documents both forms. */
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final String clientId;

    GoogleIdTokenValidator(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("GOOGLE_CLIENT_ID is not set");
        }
        this.clientId = clientId;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String issuer = token.getClaimAsString("iss");
        if (issuer == null || !ISSUERS.contains(issuer)) {
            return failure("Token was not issued by Google");
        }
        if (token.getAudience() == null || !token.getAudience().contains(clientId)) {
            return failure("Token was issued for a different app");
        }
        if (!Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {
            return failure("Google account email is not verified");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
