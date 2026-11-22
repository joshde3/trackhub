package io.github.joshde3.trackhub.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. One jar runs both the API and the background worker; which parts start is
 * controlled by the {@code api} and {@code worker} Spring profiles (ADR 0001).
 */
@SpringBootApplication
public class TrackHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackHubApplication.class, args);
    }
}
