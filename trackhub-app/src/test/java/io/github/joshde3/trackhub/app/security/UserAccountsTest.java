package io.github.joshde3.trackhub.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joshde3.trackhub.app.PostgresTestConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class UserAccountsTest {

    @Autowired private UserAccounts users;
    @Autowired private JdbcTemplate jdbc;

    private static Jwt token(String sub, String email) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject(sub)
                .claim("email", email).claim("name", "Test").issuedAt(Instant.now()).build();
    }

    @Test
    void createsTheUserOnFirstSignInAndFindsThemAfter() {
        String sub = "sub-" + UUID.randomUUID();

        UUID first = users.idOf(token(sub, sub + "@example.com"));
        UUID second = users.idOf(token(sub, sub + "@example.com"));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE google_sub = ?", Long.class, sub)).isEqualTo(1L);
    }

    @Test
    void keysOnTheGoogleSubjectNotTheEmail() {
        String sub = "sub-" + UUID.randomUUID();
        UUID id = users.idOf(token(sub, sub + "@example.com"));

        assertThat(users.idOf(token(sub, "renamed-" + sub + "@example.com"))).isEqualTo(id);
    }
}
