package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.route.PreparedChatGatewayRequest;

/** Wire vectors are produced by the Harness Direct Remote implementation (direct-remote.ts). */
class DirectProtocolTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void signaturesMatchTheRuntimeVerifier() {
        String encoded = DirectProtocol.encode("{\"protocol\":\"byclaw.direct/v1\",\"ownerScope\":\"u7.e0\"}");
        assertThat(encoded).isEqualTo("eyJwcm90b2NvbCI6ImJ5Y2xhdy5kaXJlY3QvdjEiLCJvd25lclNjb3BlIjoidTcuZTAifQ");
        assertThat(DirectProtocol.sign(SECRET, encoded))
            .isEqualTo("dad153b4c97bf9963e0e2ddcabb7de5cf1148043c42d9eece82153ac7af61ae3");
        assertThat(DirectProtocol.controlHeader(SECRET, "GET", "/plugins/byclaw-direct/v1/status", 1790000000000L))
            .isEqualTo("1790000000000.b93bdd3593f169958ad808e71349f0cda7126b7096ddbd1d13ac60c5cda186bd");
        assertThat(DirectProtocol.sha256Hex("{\"protocol\":\"byclaw.direct/v1\",\"command\":{}}".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("b90ce9d22aac34c9a084601e2bee99d6bd59333c43b804b604a915491f393db1");
    }

    @Test
    void runtimeSecretsAreDistinctPerOwnerAndBinding() {
        String master = "master-secret-for-tests-0123456789abcdef";
        String first = DirectProtocol.runtimeSecret(master, "u1.e0", 10L);
        assertThat(first).hasSize(64).isEqualTo(DirectProtocol.runtimeSecret(master, "u1.e0", 10L));
        assertThat(DirectProtocol.runtimeSecret(master, "u2.e0", 10L)).isNotEqualTo(first);
        assertThat(DirectProtocol.runtimeSecret(master, "u1.e0", 11L)).isNotEqualTo(first);
        assertThatThrownBy(() -> DirectProtocol.runtimeSecret("short", "u1.e0", 10L))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preparedTurnBecomesTheAskAgentDictionaryAndAShortSignedAdmission() {
        PreparedChatGatewayRequest prepared = new PreparedChatGatewayRequest("BYCLAW_DSH_u1", "9000",
            List.of(Map.of("role", "user", "content", Map.of("text", "hi"))), "u1", "User One", "ASK_AGENT", "-1",
            "9002", "9001_9002", Map.of("cwd", "/by/projects/-1"),
            Map.of("requestId", "req-1", "Beyond-Token", "server-minted"));
        DirectProtocol.SignedTurn turn = DirectProtocol.signTurn(prepared, "u1.e0", 3L, SECRET, 1_790_000_000_000L, 120);

        JSONObject body = JSON.parseObject(new String(turn.body(), StandardCharsets.UTF_8));
        assertThat(body.getString("protocol")).isEqualTo("byclaw.direct/v1");
        JSONObject header = body.getJSONObject("command").getJSONObject("header");
        assertThat(header.getString("message_id")).isEqualTo("9002");
        assertThat(header.getString("session_id")).isEqualTo("9000");
        assertThat(header.getString("trace_id")).isEqualTo("9001_9002");
        assertThat(header.getString("target_agent_type")).isEqualTo("BYCLAW_DSH_u1");
        assertThat(header.getString("user_code")).isEqualTo("u1");
        assertThat(header.getJSONObject("metadata").getString("requestId")).isEqualTo("req-1");
        JSONObject commandBody = body.getJSONObject("command").getJSONObject("body");
        assertThat(commandBody.getJSONObject("extra_payload").getString("cwd")).isEqualTo("/by/projects/-1");
        assertThat(commandBody.getBoolean("wait_for_reply")).isFalse();

        JSONObject admission = JSON.parseObject(new String(Base64.getUrlDecoder().decode(turn.admission()),
            StandardCharsets.UTF_8));
        assertThat(admission.getString("ownerScope")).isEqualTo("u1.e0");
        assertThat(admission.getString("userCode")).isEqualTo("u1");
        assertThat(admission.getLong("generation")).isEqualTo(3L);
        assertThat(admission.getString("requestId")).isEqualTo("req-1");
        assertThat(admission.getString("bodySha256")).isEqualTo(DirectProtocol.sha256Hex(turn.body()));
        assertThat(admission.getLong("expiresAt")).isEqualTo(1_790_000_120_000L);
        assertThat(turn.signature()).isEqualTo(DirectProtocol.sign(SECRET, turn.admission()));
        assertThat(turn.requestId()).isEqualTo("req-1");
        assertThat(turn.toString()).doesNotContain("server-minted").doesNotContain(SECRET);
    }

    @Test
    void turnsWithoutAServerRequestIdAreRefused() {
        PreparedChatGatewayRequest prepared = new PreparedChatGatewayRequest("BYCLAW_DSH_u1", "9000", "hi", "u1",
            "User One", "ASK_AGENT", "-1", "9002", "9001_9002", Map.of(), Map.of());
        assertThatThrownBy(() -> DirectProtocol.signTurn(prepared, "u1.e0", 1L, SECRET, 0L, 120))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
