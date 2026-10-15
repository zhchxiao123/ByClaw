package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DirectToolRedisTest {

    @Test
    void toolsGetAReadOnlyDiscoveryUserNotTheSharedPassword() {
        List<List<String>> commands = new ArrayList<>();
        DirectToolRedis access = new DirectToolRedis("master-secret-for-tests-0123456789abcdef", "byclaw-direct-tools",
            "ByaiService", command -> commands.add(List.of(command)));
        access.define();
        assertThat(commands).hasSize(1);
        List<String> acl = commands.get(0);
        assertThat(acl.subList(0, 4)).containsExactly("ACL", "SETUSER", "byclaw-direct-tools", "reset");
        assertThat(acl).contains("on", "~byai_gateway:sd:*", "resetchannels", "+@read", "+@connection", "-@dangerous");
        assertThat(acl).noneMatch(part -> part.startsWith(">"));
        Map<String, String> env = access.toolEnvironment();
        assertThat(env).containsEntry("DATACLOUD_GATEWAY_REDIS_USERNAME", "byclaw-direct-tools")
            .containsEntry("BE_DOMAINNAME", "ByaiService").containsKey("DATACLOUD_GATEWAY_REDIS_PASSWORD");
        String password = env.get("DATACLOUD_GATEWAY_REDIS_PASSWORD");
        assertThat(acl).contains("#" + DirectProtocol.sha256Hex(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(new DirectToolRedis("another-master-secret-0123456789abcdef", "byclaw-direct-tools", "ByaiService",
            command -> { }).toolEnvironment().get("DATACLOUD_GATEWAY_REDIS_PASSWORD")).isNotEqualTo(password);
    }
}
