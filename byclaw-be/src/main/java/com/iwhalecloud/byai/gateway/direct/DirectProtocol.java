package com.iwhalecloud.byai.gateway.direct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.iwhalecloud.byai.gateway.route.PreparedChatGatewayRequest;

/**
 * Wire contract of the Harness Direct Remote ingress (byclaw.direct/v1).
 * The runtime verifies the same HMAC admission and control signatures in direct-remote.ts.
 */
public final class DirectProtocol {

    public static final String PROTOCOL = "byclaw.direct/v1";
    public static final String ROUTE_PREFIX = "/plugins/byclaw-direct/v1";
    public static final String ADMISSION_HEADER = "x-byclaw-direct-admission";
    public static final String SIGNATURE_HEADER = "x-byclaw-direct-signature";
    public static final String CONTROL_HEADER = "x-byclaw-direct-control";
    private static final int MIN_MASTER_SECRET_LENGTH = 32;

    private DirectProtocol() {
    }

    /** Body and short-lived admission for one prepared turn. Contains credentials: never log. */
    public record SignedTurn(String requestId, byte[] body, String admission, String signature) {
        @Override
        public String toString() {
            return "SignedTurn[requestId=" + requestId + "]";
        }
    }

    public static SignedTurn signTurn(PreparedChatGatewayRequest prepared, String ownerScope, long generation,
                                      String runtimeSecret, long nowMillis, int ttlSeconds) {
        Object requestId = prepared.metadata().get("requestId");
        if (!(requestId instanceof String id) || StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("Direct turns require a server-side requestId");
        }
        byte[] body = JSON.toJSONBytes(commandBody(prepared), SerializerFeature.DisableCircularReferenceDetect);
        Map<String, Object> admission = new LinkedHashMap<>();
        admission.put("protocol", PROTOCOL);
        admission.put("ownerScope", ownerScope);
        admission.put("userCode", prepared.userCode());
        admission.put("generation", generation);
        admission.put("requestId", id);
        admission.put("sessionId", prepared.sessionId());
        admission.put("traceId", prepared.traceId());
        admission.put("bodySha256", sha256Hex(body));
        admission.put("expiresAt", nowMillis + ttlSeconds * 1000L);
        String encoded = encode(JSON.toJSONString(admission));
        return new SignedTurn(id, body, encoded, sign(runtimeSecret, encoded));
    }

    /** The by-framework AskAgentCommand dictionary the Legacy Worker received over Redis. */
    static Map<String, Object> commandBody(PreparedChatGatewayRequest prepared) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("message_id", prepared.messageId());
        header.put("session_id", prepared.sessionId());
        header.put("trace_id", prepared.traceId());
        header.put("source_agent_type", "");
        header.put("target_agent_type", prepared.targetAgentType());
        header.put("parent_message_id", prepared.parentMessageId());
        header.put("user_code", prepared.userCode());
        header.put("user_name", prepared.userName());
        header.put("metadata", prepared.metadata());
        Map<String, Object> commandBody = new LinkedHashMap<>();
        commandBody.put("content", prepared.content());
        commandBody.put("wait_for_reply", false);
        commandBody.put("extra_payload", prepared.parameters());
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("header", header);
        command.put("body", commandBody);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocol", PROTOCOL);
        body.put("command", command);
        return body;
    }

    /** Per-binding runtime secret; derived, so it is never stored beside the binding. */
    public static String runtimeSecret(String masterSecret, String ownerScope, long bindingId) {
        if (masterSecret == null || masterSecret.length() < MIN_MASTER_SECRET_LENGTH) {
            throw new IllegalStateException("byclaw.direct.master-secret must be at least 32 characters");
        }
        return hmacHex(masterSecret, "byclaw-direct-runtime:" + ownerScope + ":" + bindingId);
    }

    public static String controlHeader(String secret, String method, String path, long nowMillis) {
        return nowMillis + "." + hmacHex(secret, method.toUpperCase() + "\n" + path + "\n" + nowMillis);
    }

    public static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static String sign(String secret, String encodedAdmission) {
        return hmacHex(secret, encodedAdmission);
    }

    public static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacHex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
