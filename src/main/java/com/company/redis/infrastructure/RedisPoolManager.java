package com.company.redis.infrastructure;

import com.company.redis.config.RedisResilienceConfig;
import io.quarkus.runtime.Startup;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Gestor de pool de conexiones Redis (Programación imperativa, sin Mutiny) con resiliencia:
 * medición síncrona de latencia, detección de degradación sostenida, auto-reset con cooldown,
 * soporte para contraseñas y ciclo de vida limpio (@PreDestroy).
 */
@Startup
@ApplicationScoped
public class RedisPoolManager {

    private static final Logger LOG = Logger.getLogger(RedisPoolManager.class);

    @Inject
    Vertx vertx;

    @Inject
    RedisResilienceConfig resilienceConfig;

    @ConfigProperty(name = "quarkus.redis.hosts")
    String hosts;

    @ConfigProperty(name = "quarkus.redis.password")
    Optional<String> password;

    @ConfigProperty(name = "quarkus.redis.max-pool-size", defaultValue = "20")
    int maxPoolSize;

    @ConfigProperty(name = "quarkus.redis.max-pool-waiting", defaultValue = "32")
    int maxPoolWaiting;

    @ConfigProperty(name = "quarkus.redis.timeout", defaultValue = "2s")
    Duration timeout;

    private volatile Redis redis;
    private volatile RedisAPI redisAPI;
    private final AtomicLong connectionId = new AtomicLong(0);
    private final AtomicInteger consecutiveDelays = new AtomicInteger(0);
    private final AtomicBoolean resetInProgress = new AtomicBoolean(false);
    private volatile long lastResetAtMillis = 0;

    @PostConstruct
    void init() {
        LOG.infof("Initializing Redis connection pool [host: %s, max-size: %d, max-waiting: %d]",
                hosts, maxPoolSize, maxPoolWaiting);
        openNewClient();
        LOG.info("Redis connection pool initialized successfully.");
    }

    @PreDestroy
    void close() {
        LOG.info("Closing Redis connection pool on application shutdown...");
        Redis client = this.redis;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warn("Error closing Redis client on shutdown: " + e.getMessage());
            }
        }
    }

    private void openNewClient() {
        RedisOptions options = new RedisOptions()
                .setConnectionString(hosts)
                .setMaxPoolSize(maxPoolSize)
                .setMaxPoolWaiting(maxPoolWaiting)
                .setNetClientOptions(new NetClientOptions().setConnectTimeout((int) timeout.toMillis()));

        password.filter(p -> !p.isBlank()).ifPresent(options::setPassword);

        Redis client = Redis.createClient(vertx, options);
        this.redis = client;
        this.redisAPI = RedisAPI.api(client);
        connectionId.incrementAndGet();
    }

    public RedisAPI getRedisAPI() {
        return redisAPI;
    }

    public long getConnectionId() {
        return connectionId.get();
    }

    public int getConsecutiveDelays() {
        return consecutiveDelays.get();
    }

    /**
     * Ejecuta una llamada a Redis de forma imperativa y síncrona:
     * mide la latencia de la llamada y aplica timeout síncrono.
     */
    public <T> T executeTimed(Function<RedisAPI, Future<T>> call) throws Exception {
        long start = System.nanoTime();
        RedisAPI api = this.redisAPI;
        if (api == null) {
            throw new IllegalStateException("Redis client not initialized");
        }
        try {
            T result = call.apply(api)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            recordLatency(Duration.ofNanos(System.nanoTime() - start));
            return result;
        } catch (Exception e) {
            Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
            recordFailure(cause);
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    private void recordLatency(Duration elapsed) {
        long ms = elapsed.toMillis();
        if (ms > resilienceConfig.latencyThresholdMs()) {
            int n = consecutiveDelays.incrementAndGet();
            LOG.warnf("Redis call slow (%dms > %dms umbral). consecutiveDelays=%d", ms,
                    resilienceConfig.latencyThresholdMs(), n);
            maybeAutoReset(n, "latencia sostenida (%dms > %dms)".formatted(ms, resilienceConfig.latencyThresholdMs()));
        } else {
            consecutiveDelays.set(0);
        }
    }

    private void recordFailure(Throwable t) {
        int n = consecutiveDelays.incrementAndGet();
        LOG.warnf("Redis call failed: %s. consecutiveDelays=%d", t.getMessage(), n);
        maybeAutoReset(n, "fallo de conexión: " + t.getMessage());
    }

    private void maybeAutoReset(int consecutive, String reason) {
        if (consecutive >= resilienceConfig.consecutiveDelaysThreshold()) {
            reset(reason);
        }
    }

    /**
     * Reinicia el pool: abre un nuevo cliente y cierra el anterior de manera diferida.
     */
    public ResetStatus reset(String reason) {
        if (!resetInProgress.compareAndSet(false, true)) {
            return ResetStatus.ALREADY_IN_PROGRESS;
        }
        try {
            long now = System.currentTimeMillis();
            if (now - lastResetAtMillis < resilienceConfig.reconnectCooldownMs()) {
                return ResetStatus.COOLDOWN_ACTIVE;
            }
            Redis old = this.redis;
            openNewClient();
            consecutiveDelays.set(0);
            lastResetAtMillis = now;
            LOG.warnf("Redis pool reset (reason=%s). Nuevo connectionId=%d", reason, connectionId.get());
            if (old != null) {
                vertx.setTimer(1500, id -> {
                    try {
                        old.close();
                    } catch (Exception ignored) {
                    }
                });
            }
            return ResetStatus.SUCCESS;
        } finally {
            resetInProgress.set(false);
        }
    }
}
