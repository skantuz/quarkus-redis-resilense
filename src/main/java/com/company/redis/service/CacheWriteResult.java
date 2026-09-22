package com.company.redis.service;

public record CacheWriteResult(String key, String value, boolean persistedToRedis, boolean storedInFallback) {

    public CacheWriteResult(boolean persistedToRedis, boolean storedInFallback) {
        this(null, null, persistedToRedis, storedInFallback);
    }

    public boolean isDegraded() {
        return !persistedToRedis && storedInFallback;
    }

    public String status() {
        return isDegraded() ? "DEGRADED_STORED_IN_LOCAL_FALLBACK" : "STORED_IN_REDIS";
    }
}
