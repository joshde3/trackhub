package io.github.joshde3.trackhub.app.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.joshde3.trackhub.app.shipment.TrackingCache;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine, in the app's own memory, instead of Redis: one instance on a $5 plan has no one to
 * share a cache with, and a Redis service would cost more than the app (ADR 0006). Behind Spring's
 * cache abstraction, so swapping to Redis later is configuration, not code.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfiguration {

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // Only caches declared here exist; a typo in a cache name fails instead of making a new one.
        manager.setCacheNames(List.of());
        // Expiry per entry: each snapshot's TTL depends on the status it reports.
        manager.registerCustomCache(TrackingCache.NAME, Caffeine.newBuilder()
                .maximumSize(TrackingCache.MAX_ENTRIES)
                .expireAfter(Expiry.writing((Object key, Object value) -> TrackingCache.ttlFor((TrackingSnapshot) value)))
                .recordStats() // hit and miss counts reach Micrometer as cache.gets{result=hit|miss}
                .build());
        return manager;
    }
}
