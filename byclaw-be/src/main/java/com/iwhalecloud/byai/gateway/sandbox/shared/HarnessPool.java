package com.iwhalecloud.byai.gateway.sandbox.shared;

/**
 * The shared harness pool a user's {@code byclaw-dsh} context belongs to.
 *
 * @param serviceKey      shared host template of the user's pool
 * @param otherServiceKey shared host template of the other pool, where the user must not stay
 * @param stateless       whether the user's pool is the stateless one
 */
public record HarnessPool(String serviceKey, String otherServiceKey, boolean stateless) {
}
