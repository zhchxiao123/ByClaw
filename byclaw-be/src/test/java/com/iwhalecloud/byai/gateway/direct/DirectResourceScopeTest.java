package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DirectResourceScopeTest {

    private final Map<String, Map<String, String>> hashes = new HashMap<>();
    private final Map<String, Long> enterprises = new HashMap<>(Map.of("10000153", 1L, "10000068", 1L,
        "10000801", 10000789L));

    private final DirectResourceScope.Store store = new DirectResourceScope.Store() {
        @Override
        public Map<String, String> read(String key) {
            return new LinkedHashMap<>(hashes.getOrDefault(key, Map.of()));
        }

        @Override
        public void replace(String key, Map<String, String> entries) {
            hashes.put(key, new LinkedHashMap<>(entries));
        }
    };

    @Test
    void anOwnersRuntimeSeesOnlyItsEnterprisesGrants() {
        hashes.put("USER:RESOURCES:AUTH:10001", Map.of("10000153", "DIG_EMPLOYEE", "10000068", "SKILL",
            "10000801", "DIG_EMPLOYEE", "99999999", "DIG_EMPLOYEE"));
        DirectResourceScope scope = new DirectResourceScope(store, ids -> {
            Map<String, Long> found = new HashMap<>();
            ids.forEach(id -> { if (enterprises.containsKey(id)) found.put(id, enterprises.get(id)); });
            return found;
        });
        assertThat(scope.refresh(new RuntimeOwnerScope(10001L, "adminvip", 1L))).isTrue();
        assertThat(hashes.get("USER:RESOURCES:AUTH:10001:E1"))
            .containsEntry("10000153", "DIG_EMPLOYEE").containsEntry("10000068", "SKILL")
            .doesNotContainKeys("10000801", "99999999").containsEntry("scope", "u10001.e1");
        assertThat(scope.refresh(new RuntimeOwnerScope(10001L, "adminvip", 1L))).as("unchanged: no rewrite").isFalse();

        assertThat(scope.refresh(new RuntimeOwnerScope(10001L, "adminvip", 10000789L))).isTrue();
        assertThat(hashes.get("USER:RESOURCES:AUTH:10001:E10000789")).containsOnlyKeys("10000801", "scope");

        hashes.put("USER:RESOURCES:AUTH:10001", Map.of("10000068", "SKILL"));
        scope.refresh(new RuntimeOwnerScope(10001L, "adminvip", 1L));
        assertThat(hashes.get("USER:RESOURCES:AUTH:10001:E1")).as("a revoked grant disappears")
            .containsOnlyKeys("10000068", "scope");
        assertThat(DirectResourceScope.key(new RuntimeOwnerScope(101L, "x", 1L))).isEqualTo("USER:RESOURCES:AUTH:101:E1");
    }
}
