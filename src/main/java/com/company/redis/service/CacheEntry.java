package com.company.redis.service;

public record CacheEntry(String value, boolean fromFallback, long ttlSeconds) {

    public CacheEntry(String value, boolean fromFallback) {
        this(value, fromFallback, 0L);
    }

    public boolean isPresent() {
        return value != null;
    }
}
