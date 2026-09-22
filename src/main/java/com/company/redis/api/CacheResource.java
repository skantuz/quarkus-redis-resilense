package com.company.redis.api;

import com.company.redis.config.RedisResilienceConfig;
import com.company.redis.infrastructure.RedisPoolManager;
import com.company.redis.infrastructure.ResetStatus;
import com.company.redis.service.CacheEntry;
import com.company.redis.service.CacheService;
import com.company.redis.service.CacheWriteResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/cache")
@Produces(MediaType.APPLICATION_JSON)
public class CacheResource {

    @Inject
    CacheService cacheService;

    @Inject
    RedisPoolManager poolManager;

    @Inject
    RedisResilienceConfig resilienceConfig;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response set(CachePayload payload) {
        Response invalid = validate(payload);
        if (invalid != null) {
            return invalid;
        }
        CacheWriteResult result = cacheService.set(payload.key(), payload.value(), payload.ttlSeconds());
        if (result.isDegraded()) {
            return Response.status(202)
                    .entity(new CacheWriteResponse(result.key(), result.value(), true, result.status()))
                    .build();
        }
        return Response.ok(new CacheWriteResponse(result.key(), result.value(), false, result.status()))
                .build();
    }

    private Response validate(CachePayload payload) {
        if (payload == null || payload.key() == null || payload.key().isBlank()) {
            return Response.status(400).entity(new ErrorResponse("key no puede estar vacía")).build();
        }
        if (payload.ttlSeconds() == null || payload.ttlSeconds() < 1) {
            return Response.status(400).entity(new ErrorResponse("ttlSeconds debe ser >= 1")).build();
        }
        return null;
    }

    @GET
    @Path("/{key}")
    public Response get(@PathParam("key") String key,
                        @QueryParam("bypassLocalCache") @DefaultValue("false") boolean bypassLocalCache) {
        CacheEntry entry = cacheService.get(key, bypassLocalCache);
        if (entry == null || !entry.isPresent()) {
            return Response.status(404).entity(new ErrorResponse("Key not found")).build();
        }
        return Response.ok(new CacheResponse(key, entry.value(), entry.fromFallback())).build();
    }

    @GET
    @Path("/connection/id")
    public ConnectionIdResponse connectionId() {
        return new ConnectionIdResponse(poolManager.getConnectionId());
    }

    @POST
    @Path("/local-cache/clear")
    public Response clearLocalCache() {
        long size = cacheService.clearLocalCache();
        return Response.ok(new ResetResponse("SUCCESS", "Caché local limpiada (%d entradas)".formatted(size))).build();
    }

    @POST
    @Path("/pool/reset")
    public Response poolReset(@HeaderParam("X-Admin-Key") String adminKey,
                              @QueryParam("reason") @DefaultValue("manual") String reason) {
        if (adminKey == null || !adminKey.equals(resilienceConfig.adminSecret())) {
            return Response.status(403).entity(new ResetResponse("FORBIDDEN", "X-Admin-Key inválida o ausente")).build();
        }
        ResetStatus status = poolManager.reset(reason);
        return switch (status) {
            case SUCCESS -> Response.ok(new ResetResponse("SUCCESS",
                    "Pool reiniciado. connectionId=" + poolManager.getConnectionId())).build();
            case COOLDOWN_ACTIVE -> Response.status(429).entity(new ResetResponse("COOLDOWN_ACTIVE",
                    "Cooldown activo (app.redis.resilience.reconnect-cooldown-ms), reintenta más tarde")).build();
            case ALREADY_IN_PROGRESS -> Response.status(429).entity(new ResetResponse("ALREADY_IN_PROGRESS",
                    "Ya hay un reset en curso")).build();
        };
    }

    // ---- DTOs ----

    public record CachePayload(String key, String value, Integer ttlSeconds) {
    }

    public record CacheResponse(String key, String value, boolean isFallback) {
    }

    public record CacheWriteResponse(String key, String value, boolean isFallback, String status) {
    }

    public record ErrorResponse(String error) {
    }

    public record ResetResponse(String status, String message) {
    }

    public record ConnectionIdResponse(long redisClientId) {
    }
}
