package com.iwhalecloud.byai.state.domain.ws.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * WebSocket 心跳每 6 秒一次，沙箱活跃心跳按用户限频转发。
 *
 * @author Claude Code Agent
 * @since 2026-11-17
 */
class WebSocketHeartbeatThrottleTest {

    @Test
    void forwardsOncePerIntervalPerUser() {
        WebSocketHandler handler = new WebSocketHandler();
        assertThat(handler.sandboxHeartbeatDue("alice", 0)).isTrue();
        assertThat(handler.sandboxHeartbeatDue("alice", 6_000)).isFalse();
        assertThat(handler.sandboxHeartbeatDue("bob", 6_000)).isTrue();
        assertThat(handler.sandboxHeartbeatDue("alice", 29_999)).isFalse();
        assertThat(handler.sandboxHeartbeatDue("alice", 30_000)).isTrue();
        assertThat(handler.sandboxHeartbeatDue(null, 30_001)).isTrue();
    }
}
