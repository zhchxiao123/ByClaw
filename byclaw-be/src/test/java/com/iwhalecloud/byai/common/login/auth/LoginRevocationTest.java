package com.iwhalecloud.byai.common.login.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LoginRevocationTest {

    @AfterEach
    void reset() {
        LoginRevocation.useStore(null);
    }

    @Test
    void aLoggedOutLoginStaysRevokedWhileOtherLoginsAreUnaffected() {
        Set<String> keys = new HashSet<>();
        LoginRevocation.useStore(new LoginRevocation.Store() {
            @Override
            public void put(String key) {
                keys.add(key);
            }

            @Override
            public boolean contains(String key) {
                return keys.contains(key);
            }
        });
        assertThat(LoginRevocation.revoked("login-1")).isFalse();
        LoginRevocation.revoke("login-1");
        assertThat(LoginRevocation.revoked("login-1")).isTrue();
        assertThat(LoginRevocation.revoked("login-2")).isFalse();
        assertThat(LoginRevocation.revoked(null)).as("tokens without a login session are not login-bound").isFalse();
    }
}
