package com.iwhalecloud.byai.gateway.route;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-owned turn composition shared by delivery adapters.
 * Contains internal credentials: never serialize it to a browser or log its content.
 */
public record PreparedChatGatewayRequest(
    String targetAgentType,
    String sessionId,
    Object content,
    String userCode,
    String userName,
    String actionType,
    String parentMessageId,
    String messageId,
    String traceId,
    Map<String, Object> parameters,
    Map<String, Object> metadata
) {
    public PreparedChatGatewayRequest {
        parameters = Collections.unmodifiableMap(new HashMap<>(parameters));
        metadata = Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    @Override
    public String toString() {
        return "PreparedChatGatewayRequest[sessionId=" + sessionId + ", traceId=" + traceId + "]";
    }
}
