package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;

class DirectIntentTest {

    private AssistantChatDto dto(String text, String requestId, String clientRequestId) {
        AssistantChatDto dto = new AssistantChatDto();
        dto.setChatContent(text);
        dto.setAgentId(10000153L);
        dto.setClientRequestId(clientRequestId);
        dto.setExtParams(new HashMap<>(Map.of("requestId", requestId, "thinkingLevel", "high", "turnId", "carrier-turn")));
        return dto;
    }

    @Test
    void retriesWithNewCarrierIdsKeepTheSameIdentity() {
        String first = DirectIntent.digest(dto("hello", "req-1", "q1_a1"));
        String retry = DirectIntent.digest(dto("hello", "req-1", "q9_a9"));
        assertThat(retry).hasSize(64).isEqualTo(first);
        assertThat(DirectIntent.requestId(dto("hello", "req-1", "q1_a1"))).isEqualTo("req-1");
    }

    @Test
    void changedContentModelOrResourcesChangeTheIdentity() {
        String base = DirectIntent.digest(dto("hello", "req-1", "q1_a1"));
        assertThat(DirectIntent.digest(dto("hello!", "req-1", "q1_a1"))).isNotEqualTo(base);
        AssistantChatDto thinking = dto("hello", "req-1", "q1_a1");
        thinking.getExtParams().put("thinkingLevel", "low");
        assertThat(DirectIntent.digest(thinking)).isNotEqualTo(base);
        AssistantChatDto agent = dto("hello", "req-1", "q1_a1");
        agent.setAgentId(1L);
        assertThat(DirectIntent.digest(agent)).isNotEqualTo(base);
        AssistantChatDto model = dto("hello", "req-1", "q1_a1");
        model.setRelModelId("77");
        assertThat(DirectIntent.digest(model)).isNotEqualTo(base);
    }

    @Test
    void extParamOrderDoesNotMatter() {
        AssistantChatDto a = dto("hello", "req-1", "q1_a1");
        a.getExtParams().put("projectId", 7);
        a.getExtParams().put("zeta", List.of(1, 2));
        AssistantChatDto b = dto("hello", "req-1", "q1_a1");
        Map<String, Object> reordered = new java.util.LinkedHashMap<>();
        reordered.put("zeta", List.of(1, 2));
        reordered.put("projectId", 7);
        reordered.putAll(b.getExtParams());
        b.setExtParams(reordered);
        assertThat(DirectIntent.digest(b)).isEqualTo(DirectIntent.digest(a));
    }

    @Test
    void aTurnWithoutABusinessRequestIdHasNoIdempotencyKey() {
        AssistantChatDto none = dto("hello", "req-1", "q1_a1");
        none.getExtParams().remove("requestId");
        assertThat(DirectIntent.requestId(none)).isNull();
    }
}
