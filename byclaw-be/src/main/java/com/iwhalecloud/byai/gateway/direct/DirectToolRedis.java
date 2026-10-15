package com.iwhalecloud.byai.gateway.direct;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Business CLIs in a runtime find platform services through the service-discovery registry in
 * the shared Redis. Tools must never hold the shared Redis password (it reaches every user's
 * sessions and tokens), so they get a read-only Redis user limited to the discovery keys, with a
 * password derived from the master secret, and the platform's own service name.
 */
public class DirectToolRedis {

    static final String DISCOVERY_KEYS = "~byai_gateway:sd:*";

    private final String password;
    private final String username;
    private final String serviceName;
    private final Consumer<String[]> redis;

    public DirectToolRedis(String masterSecret, String username, String serviceName, Consumer<String[]> redis) {
        this.password = DirectProtocol.hmacHex(masterSecret, "byclaw-direct-tool-redis:" + username);
        this.username = username;
        this.serviceName = serviceName;
        this.redis = redis;
    }

    /** Define (or redefine) the user: read-only, discovery keys only, no channels; idempotent. */
    public void define() {
        redis.accept(new String[] {"ACL", "SETUSER", username, "reset", "on",
            "#" + DirectProtocol.sha256Hex(password.getBytes(StandardCharsets.UTF_8)),
            DISCOVERY_KEYS, "resetchannels", "-@all", "+@read", "+@connection", "-@dangerous"});
    }

    /** Environment the runtime hands to tools for discovery (the CLIs' DATACLOUD_GATEWAY_REDIS_*). */
    public Map<String, String> toolEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DATACLOUD_GATEWAY_REDIS_USERNAME", username);
        env.put("DATACLOUD_GATEWAY_REDIS_PASSWORD", password);
        env.put("BE_DOMAINNAME", serviceName);
        return env;
    }
}
