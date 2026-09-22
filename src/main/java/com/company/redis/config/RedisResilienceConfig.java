package com.company.redis.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "app.redis.resilience")
public interface RedisResilienceConfig {

    @WithDefault("150")
    long latencyThresholdMs();

    @WithDefault("3")
    int consecutiveDelaysThreshold();

    @WithDefault("15000")
    long reconnectCooldownMs();

    @WithDefault("supersecret123")
    String adminSecret();
}
