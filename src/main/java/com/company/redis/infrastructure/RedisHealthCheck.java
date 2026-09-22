package com.company.redis.infrastructure;

import com.company.redis.config.RedisResilienceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Readiness
@ApplicationScoped
public class RedisHealthCheck implements HealthCheck {

    @Inject
    RedisPoolManager poolManager;

    @Inject
    RedisResilienceConfig resilienceConfig;

    @Override
    public HealthCheckResponse call() {
        var api = poolManager.getRedisAPI();
        if (api == null) {
            return HealthCheckResponse.named("Quarkus Redis Connection Pool")
                    .down()
                    .withData("reason", "Quarkus Redis client not initialized")
                    .withData("connectionId", poolManager.getConnectionId())
                    .withData("degradedModeAvailable", true)
                    .build();
        }

        long checkTimeoutMs = Math.max(resilienceConfig.latencyThresholdMs(), 500);

        try {
            var resp = api.ping(Collections.emptyList())
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(checkTimeoutMs, TimeUnit.MILLISECONDS);

            return HealthCheckResponse.named("Quarkus Redis Connection Pool")
                    .up()
                    .withData("response", resp.toString())
                    .withData("connectionId", poolManager.getConnectionId())
                    .build();
        } catch (Exception err) {
            Throwable cause = err.getCause() != null ? err.getCause() : err;
            return HealthCheckResponse.named("Quarkus Redis Connection Pool")
                    .down()
                    .withData("error", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName())
                    .withData("connectionId", poolManager.getConnectionId())
                    .withData("degradedModeAvailable", true)
                    .build();
        }
    }
}
