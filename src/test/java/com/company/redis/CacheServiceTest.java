package com.company.redis;

import com.company.redis.service.CacheEntry;
import com.company.redis.service.CacheService;
import com.company.redis.service.CacheWriteResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class CacheServiceTest {

    @Inject
    CacheService cacheService;

    @Test
    @DisplayName("Should write to fallback when Redis is offline and allow retrieval")
    public void testDegradedWriteAndRead() {
        CacheWriteResult result = cacheService.set("user:101", "Alice", 10);

        assertNotNull(result);
        assertTrue(result.isDegraded());
        assertEquals("DEGRADED_STORED_IN_LOCAL_FALLBACK", result.status());
        assertEquals("Alice", result.value());

        // Leer con bypassLocalCache=false debe devolver la entrada del fallback
        CacheEntry entry = cacheService.get("user:101", false);

        assertNotNull(entry);
        assertTrue(entry.isPresent());
        assertTrue(entry.fromFallback());
        assertEquals("Alice", entry.value());
    }

    @Test
    @DisplayName("Should clear local cache correctly")
    public void testClearLocalCache() {
        cacheService.set("temp:key", "val", 60);

        assertNotNull(cacheService.getLocal("temp:key"));

        long cleared = cacheService.clearLocalCache();
        assertTrue(cleared >= 1);
        assertFalse(cacheService.getLocal("temp:key") != null);
    }
}
