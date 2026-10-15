package com.iwhalecloud.byai.gateway.direct;

/**
 * Whether an owner scope may still act now: its user is enabled and, outside the default
 * enterprise, still an active member. Asked before every Direct action and after a wake, because
 * revocation can happen while a runtime sleeps.
 */
@FunctionalInterface
public interface RuntimeScopeAuthority {

    /** @return null when the scope may act, otherwise a short reason code for logs and audit. */
    String revoked(String ownerKey);

    RuntimeScopeAuthority ALWAYS = ownerKey -> null;
}
