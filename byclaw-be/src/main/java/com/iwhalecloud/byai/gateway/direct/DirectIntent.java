package com.iwhalecloud.byai.gateway.direct;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.domain.resource.dto.ResourceVo;

/**
 * Stable identity of what the user asked for. The business requestId names the request; the
 * digest covers its content, target, resources and settings, but not carrier ids or tokens that
 * change on every attempt, so a retry of the same request is recognised and a changed one conflicts.
 */
public final class DirectIntent {

    /** Per-attempt carrier values; never part of the intent. */
    private static final Set<String> CARRIER_KEYS = Set.of("requestId", "clientRequestId", "turnId", "laneId",
        "desktopNewSession");

    private DirectIntent() {
    }

    /** The client's business request id, or null when the turn carries none. */
    public static String requestId(AssistantChatDto dto) {
        Object value = dto == null || dto.getExtParams() == null ? null : dto.getExtParams().get("requestId");
        String id = value == null ? null : StringUtils.trimToNull(String.valueOf(value));
        return id == null || id.length() > 128 ? null : id;
    }

    public static String digest(AssistantChatDto dto) {
        Map<String, Object> intent = new TreeMap<>();
        intent.put("v", 1);
        intent.put("content", StringUtils.defaultString(dto.getChatContent()));
        intent.put("agentId", dto.getAgentId());
        intent.put("actionType", dto.getActionType());
        intent.put("relModelId", dto.getRelModelId());
        List<Map<String, Object>> resources = new ArrayList<>();
        for (ResourceVo resource : dto.getResourceList() == null ? List.<ResourceVo>of() : dto.getResourceList()) {
            Map<String, Object> item = new TreeMap<>();
            item.put("id", resource.getResourceId() == null ? resource.getId() : resource.getResourceId());
            item.put("type", resource.getResourceType() == null ? null : resource.getResourceType().name());
            item.put("code", resource.getResourceCode());
            resources.add(item);
        }
        intent.put("resources", resources);
        Map<String, Object> ext = new TreeMap<>();
        if (dto.getExtParams() != null) {
            dto.getExtParams().forEach((key, value) -> {
                if (!CARRIER_KEYS.contains(key)) ext.put(key, value);
            });
        }
        intent.put("ext", ext);
        byte[] canonical = JSON.toJSONString(intent, SerializerFeature.MapSortField, SerializerFeature.SortField,
            SerializerFeature.DisableCircularReferenceDetect).getBytes(StandardCharsets.UTF_8);
        return DirectProtocol.sha256Hex(canonical);
    }
}
