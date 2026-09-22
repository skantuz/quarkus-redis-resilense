package com.company.redis;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
public class CacheResourceTest {

    @Test
    @DisplayName("Should gracefully fallback on SET when Redis is offline (Issue #1 and #4)")
    public void testSetFallbackWhenRedisDown() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "key": "fallbackUser",
                            "value": "FallbackValue123",
                            "ttlSeconds": 60
                        }
                        """)
                .when()
                .post("/api/v1/cache")
                .then()
                .statusCode(202) // 202 Accepted (Degraded mode) en vez de 500
                .body("key", equalTo("fallbackUser"))
                .body("isFallback", equalTo(true))
                .body("status", equalTo("DEGRADED_STORED_IN_LOCAL_FALLBACK"));

        // Comprobar que GET posterior lee de la memoria local acotada (Caffeine)
        given()
                .when()
                .get("/api/v1/cache/fallbackUser")
                .then()
                .statusCode(200)
                .body("key", equalTo("fallbackUser"))
                .body("value", equalTo("FallbackValue123"))
                .body("isFallback", equalTo(true));
    }

    @Test
    @DisplayName("Should return 404 for unknown key when Redis is down")
    public void testGetNotFoundWhenRedisDown() {
        given()
                .when()
                .get("/api/v1/cache/unknownKey999")
                .then()
                .statusCode(404)
                .body("error", equalTo("Key not found"));
    }

    @Test
    @DisplayName("Should enforce payload and TTL validation (Issue #8)")
    public void testPayloadValidation() {
        // TTL inválido (< 1)
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "key": "validKey",
                            "value": "validValue",
                            "ttlSeconds": -5
                        }
                        """)
                .when()
                .post("/api/v1/cache")
                .then()
                .statusCode(400);

        // Key en blanco
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "key": "   ",
                            "value": "validValue",
                            "ttlSeconds": 60
                        }
                        """)
                .when()
                .post("/api/v1/cache")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("Should reject unauthorized pool reset (Issue #7)")
    public void testUnauthorizedPoolReset() {
        given()
                .when()
                .post("/api/v1/cache/pool/reset?reason=NoAuth")
                .then()
                .statusCode(403)
                .body("status", equalTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("Should allow authorized pool reset with admin secret (Issue #6 and #7)")
    public void testAuthorizedPoolReset() {
        given()
                .header("X-Admin-Key", "supersecret123")
                .when()
                .post("/api/v1/cache/pool/reset?reason=AuthorizedTest")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(429)))
                .body("status", notNullValue())
                .body("message", notNullValue());
    }
}
