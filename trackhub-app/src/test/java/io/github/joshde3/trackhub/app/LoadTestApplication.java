package io.github.joshde3.trackhub.app;

import io.github.joshde3.trackhub.app.security.LoadTestConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * The real application, started from the test classpath for load tests (see loadtest/README.md):
 * Postgres in Testcontainers, a carrier that answers in 300 ms, and locally signed tokens instead
 * of Google's. None of this exists in the production jar.
 *
 * <pre>mvn -q install -DskipTests && mvn -pl trackhub-app spring-boot:test-run</pre>
 */
public class LoadTestApplication {

    public static void main(String[] args) {
        SpringApplication.from(TrackHubApplication::main)
                .with(PostgresTestConfiguration.class, LoadTestConfiguration.class)
                .run("--management.endpoints.web.exposure.include=health,info,metrics");
    }
}
