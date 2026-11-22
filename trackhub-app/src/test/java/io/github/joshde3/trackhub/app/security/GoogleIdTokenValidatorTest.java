package io.github.joshde3.trackhub.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

class GoogleIdTokenValidatorTest {

    private static final String CLIENT_ID = "our-app.apps.googleusercontent.com";
    private final GoogleIdTokenValidator validator = new GoogleIdTokenValidator(CLIENT_ID);

    private static Jwt token(Consumer<Jwt.Builder> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer("https://accounts.google.com")
                .audience(List.of(CLIENT_ID))
                .subject("1234567890")
                .claim("email_verified", true)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        claims.accept(builder);
        return builder.build();
    }

    private List<String> errors(Jwt token) {
        return validator.validate(token).getErrors().stream().map(OAuth2Error::getDescription).toList();
    }

    @Test
    void acceptsAGoogleTokenForOurApp() {
        assertThat(errors(token(b -> {}))).isEmpty();
        assertThat(errors(token(b -> b.issuer("accounts.google.com")))).isEmpty();
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        assertThat(errors(token(b -> b.issuer("https://evil.example.com")))).containsExactly("Token was not issued by Google");
    }

    @Test
    void rejectsAGoogleTokenIssuedForAnotherApp() {
        // The attack this prevents: any app can get its users Google tokens; only ours may sign in here.
        assertThat(errors(token(b -> b.audience(List.of("other-app.apps.googleusercontent.com")))))
                .containsExactly("Token was issued for a different app");
    }

    @Test
    void rejectsAnUnverifiedEmail() {
        assertThat(errors(token(b -> b.claim("email_verified", false)))).containsExactly("Google account email is not verified");
    }

    @Test
    void refusesToStartWithoutAClientId() {
        assertThatThrownBy(() -> new GoogleIdTokenValidator(" ")).hasMessageContaining("GOOGLE_CLIENT_ID");
    }
}
