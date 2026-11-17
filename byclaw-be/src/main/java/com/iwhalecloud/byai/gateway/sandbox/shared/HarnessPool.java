package com.iwhalecloud.byai.gateway.sandbox.shared;

import java.util.Objects;

/**
 * The shared harness pool a user's {@code byclaw-dsh} context belongs to.
 *
 * @author Claude Code Agent
 * @since 2026-11-16
 */
public final class HarnessPool {

    private final String serviceKey;

    private final String otherServiceKey;

    private final boolean stateless;

    /**
     * @param serviceKey      shared host template of the user's pool
     * @param otherServiceKey shared host template of the other pool, where the user must not stay
     * @param stateless       whether the user's pool is the stateless one
     */
    public HarnessPool(String serviceKey, String otherServiceKey, boolean stateless) {
        this.serviceKey = serviceKey;
        this.otherServiceKey = otherServiceKey;
        this.stateless = stateless;
    }

    /**
     * @return shared host template of the user's pool
     */
    public String serviceKey() {
        return serviceKey;
    }

    /**
     * @return shared host template of the other pool
     */
    public String otherServiceKey() {
        return otherServiceKey;
    }

    /**
     * @return whether the user's pool is the stateless one
     */
    public boolean stateless() {
        return stateless;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HarnessPool)) {
            return false;
        }
        HarnessPool pool = (HarnessPool) other;
        return stateless == pool.stateless && Objects.equals(serviceKey, pool.serviceKey)
            && Objects.equals(otherServiceKey, pool.otherServiceKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceKey, otherServiceKey, stateless);
    }

    @Override
    public String toString() {
        return "HarnessPool{serviceKey=" + serviceKey + ", otherServiceKey=" + otherServiceKey + ", stateless=" + stateless + "}";
    }
}
