package com.iwhalecloud.byai.gateway.sandbox.shared;

import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * JSON configuration object of system parameter {@code BYCLAW_HARNESS_STATELESS_POLICY}: which shared
 * harness pool serves each user. The mounted pool keeps user homes on shared storage; the stateless pool
 * keeps nothing on its hosts (database and the user's own space).
 */
@Getter
@Setter
public class HarnessPoolPolicyConfig {
    public static final String MOUNTED = "mounted";
    public static final String STATELESS = "stateless";

    /** Mode of users on neither list: {@link #MOUNTED} (default) or {@link #STATELESS}. */
    private String defaultMode;

    /** Shared host template (platform shared pool serviceKey) of the mounted pool. */
    private String mountedServiceKey;

    /** Shared host template of the stateless pool. */
    private String statelessServiceKey;

    /** Users served by the stateless pool whatever the default. */
    private Set<String> statelessUsers = new HashSet<>();

    /** Users served by the mounted pool whatever the default. */
    private Set<String> mountedUsers = new HashSet<>();
}
