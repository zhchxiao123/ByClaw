package com.iwhalecloud.byai.gateway.direct;

import java.nio.charset.StandardCharsets;

/**
 * Trusted owner of one logical Direct runtime: the authenticated user inside one enterprise
 * security domain. Built only from the server login, never from request parameters.
 */
public record RuntimeOwnerScope(long userId, String userCode, long enterpriseId) {

    public RuntimeOwnerScope {
        if (userId <= 0 || userCode == null || userCode.isBlank() || enterpriseId < 0) {
            throw new IllegalArgumentException("Direct runtime owner requires an authenticated user");
        }
    }

    /** Stable owner key stored with the binding and asserted by the runtime. */
    public String key() {
        return "u" + userId + ".e" + enterpriseId;
    }

    /** Opaque, path-safe directory name for this owner's state mount. */
    public String directoryName() {
        return "o-" + DirectProtocol.sha256Hex(key().getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }
}
