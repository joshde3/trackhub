# Load test

Measures the Phase 1 numbers in the main README: latency per endpoint, cache hit rate and
carrier calls. It runs the real application from the test classpath (`LoadTestApplication`) with:

- Postgres 16 in Testcontainers (needs Docker),
- a fake carrier that answers in 300 ms with three in-transit scans,
- tokens signed by the k6 script with a test-only key instead of by Google. They still go
  through the same issuer, audience and verified-email checks. This decoder exists only in test
  sources, so it can never be in the production jar.

```bash
# terminal 1: build the modules, then start the app on :8080
mvn -q install -DskipTests
mvn -pl trackhub-app spring-boot:test-run

# terminal 2: 20 virtual users for 60 s
docker run --rm --network host -v "$PWD/loadtest:/scripts" grafana/k6:1.4.2 run /scripts/tracking.js
```

`VUS`, `DURATION` and `BASE_URL` can be set with `-e`, for example `-e VUS=50`.

Each virtual user adds 5 parcels (cache misses: the carrier is called), then loops: list, get one,
refresh one (cache hits within the TTL). At the end the script prints the cache hit rate and the
number of real carrier calls, read from the app's own metrics.
