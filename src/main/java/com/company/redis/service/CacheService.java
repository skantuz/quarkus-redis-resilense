package com.company.redis.service;

import com.company.redis.infrastructure.RedisPoolManager;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.jboss.logging.Logger;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class CacheService {

    private static final Logger LOG = Logger.getLogger(CacheService.class);

    @Inject
    RedisPoolManager poolManager;

    private final Cache<String, CacheEntry> localCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfter(new Expiry<String, CacheEntry>() {
                @Override
                public long expireAfterCreate(String key, CacheEntry entry, long currentTime) {
                    long ttl = entry.ttlSeconds();
                    return ttl > 0 ? TimeUnit.SECONDS.toNanos(ttl) : TimeUnit.MINUTES.toNanos(10);
                }

                @Override
                public long expireAfterUpdate(String key, CacheEntry entry, long currentTime, long currentDuration) {
                    return currentDuration;
                }

                @Override
                public long expireAfterRead(String key, CacheEntry entry, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 5, delayUnit = ChronoUnit.SECONDS)
    @Fallback(fallbackMethod = "setFallback")
    public CacheWriteResult set(String key, String value, int ttlSeconds) {
        try {
            poolManager.executeTimed(api -> api.set(List.of(key, value, "EX", String.valueOf(ttlSeconds))));
            localCache.put(key, new CacheEntry(value, false, ttlSeconds));
            return new CacheWriteResult(key, value, true, false);
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
    }

    public CacheWriteResult setFallback(String key, String value, int ttlSeconds) {
        LOG.warnf("Fallback activado para set(key=%s): degradando a memoria local", key);
        localCache.put(key, new CacheEntry(value, true, ttlSeconds));
        return new CacheWriteResult(key, value, false, true);
    }

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 5, delayUnit = ChronoUnit.SECONDS)
    @Fallback(fallbackMethod = "getFallback")
    public CacheEntry get(String key, boolean bypassLocalCache) {
        if (!bypassLocalCache) {
            CacheEntry cached = localCache.getIfPresent(key);
            if (cached != null && cached.isPresent()) {
                return cached;
            }
        }
        try {
            Response resp = poolManager.executeTimed(api -> api.get(key));
            if (resp == null) {
                return new CacheEntry(null, false);
            }
            String value = resp.toString();
            localCache.put(key, new CacheEntry(value, false));
            return new CacheEntry(value, false);
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
    }

    public CacheEntry getFallback(String key, boolean bypassLocalCache) {
        LOG.warnf("Fallback activado para get(key=%s)", key);
        CacheEntry fallback = localCache.getIfPresent(key);
        if (fallback != null && fallback.isPresent()) {
            return new CacheEntry(fallback.value(), true);
        }
        return new CacheEntry(null, false);
    }

    public long clearLocalCache() {
        long size = localCache.estimatedSize();
        localCache.invalidateAll();
        return size;
    }

    public CacheEntry getLocal(String key) {
        return localCache.getIfPresent(key);
    }
}
