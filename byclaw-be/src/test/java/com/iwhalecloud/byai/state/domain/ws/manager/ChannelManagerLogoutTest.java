package com.iwhalecloud.byai.state.domain.ws.manager;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.state.domain.ws.constant.Constant;

import io.netty.channel.embedded.EmbeddedChannel;

class ChannelManagerLogoutTest {

    private final ChannelManager manager = new ChannelManager();

    @AfterEach
    void tearDown() {
        ChannelManager.removeAllChannels(901L);
    }

    private EmbeddedChannel channel(String loginSessionId) {
        EmbeddedChannel channel = new EmbeddedChannel();
        LoginInfo login = new LoginInfo();
        login.setUserId(901L);
        login.setSessionId(loginSessionId);
        channel.attr(Constant.ATT_USER_INFO).set(login);
        manager.addChannel(901L, channel);
        return channel;
    }

    @Test
    void logoutClosesOnlyTheSubscriptionsOfThatLoginSession() {
        EmbeddedChannel loggedOut = channel("login-a");
        EmbeddedChannel sameLoginOtherTab = channel("login-a");
        EmbeddedChannel otherDevice = channel("login-b");

        int closed = manager.closeLoginSessionChannels(901L, "login-a");

        assertThat(closed).isEqualTo(2);
        assertThat(loggedOut.isOpen()).isFalse();
        assertThat(sameLoginOtherTab.isOpen()).isFalse();
        assertThat(otherDevice.isOpen()).as("another login of the same user keeps its subscription").isTrue();
        assertThat(manager.closeLoginSessionChannels(901L, null)).isZero();
    }
}
