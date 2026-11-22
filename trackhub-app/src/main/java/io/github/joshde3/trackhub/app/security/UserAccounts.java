package io.github.joshde3.trackhub.app.security;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a verified Google identity into our user id, creating the user on first sign-in. Keyed on
 * Google's {@code sub}, which never changes, not on email, which can.
 */
@Component
public class UserAccounts {

    private final JdbcTemplate jdbc;

    UserAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID idOf(Jwt token) {
        String sub = token.getSubject();
        List<UUID> existing = findBySub(sub);
        if (!existing.isEmpty()) {
            return existing.getFirst(); // the common case: one indexed read
        }
        // Two first requests can race; ON CONFLICT makes the loser a no-op and both read the winner.
        jdbc.update("""
                INSERT INTO users (id, google_sub, email, name) VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), sub, token.getClaimAsString("email"), token.getClaimAsString("name"));
        return findBySub(sub).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Email already belongs to another Google account"));
    }

    private List<UUID> findBySub(String sub) {
        return jdbc.queryForList("SELECT id FROM users WHERE google_sub = ?", UUID.class, sub);
    }
}
