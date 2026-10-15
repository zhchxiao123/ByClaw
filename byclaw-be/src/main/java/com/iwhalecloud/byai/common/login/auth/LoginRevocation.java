package com.iwhalecloud.byai.common.login.auth;

import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;

import com.iwhalecloud.byai.common.util.RedisUtil;

/**
 * Logged-out logins. Access tokens are stateless and outlive a logout, so a logout records its
 * login session id here and token checks refuse tokens of that login. The record outlives every
 * access and refresh token; a new login always gets a new session id.
 */
public final class LoginRevocation {

    static final String PREFIX = "byclaw:login:revoked:";
    private static final long TTL_DAYS = 31;

    /** Where revocations are kept; Redis unless a test supplies another. */
    public interface Store {
        void put(String key);

        boolean contains(String key);
    }

    private static final Store REDIS = new Store() {
        @Override
        public void put(String key) {
            RedisUtil.setStringExp(key, "1", TTL_DAYS, TimeUnit.DAYS);
        }

        @Override
        public boolean contains(String key) {
            try {
                return Boolean.TRUE.equals(RedisUtil.hasKey(key));
            }
            catch (RuntimeException e) {
                // Without Redis the platform cannot authenticate either; do not add a second failure.
                return false;
            }
        }
    };

    private static volatile Store store = REDIS;

    private LoginRevocation() {
    }

    static void useStore(Store replacement) {
        store = replacement == null ? REDIS : replacement;
    }

    public static void revoke(String loginSessionId) {
        if (StringUtils.isNotBlank(loginSessionId)) store.put(PREFIX + loginSessionId);
    }

    public static boolean revoked(String loginSessionId) {
        return StringUtils.isNotBlank(loginSessionId) && store.contains(PREFIX + loginSessionId);
    }
}
