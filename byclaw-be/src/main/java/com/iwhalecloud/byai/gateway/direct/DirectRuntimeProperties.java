package com.iwhalecloud.byai.gateway.direct;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** Direct HARNESS runtime on CubeSandbox. Disabled by default; enabled per owner canary. */
@Data
@ConfigurationProperties(prefix = "byclaw.direct")
public class DirectRuntimeProperties {

    /** Master switch; Legacy remains the route for every session while false. */
    private boolean enabled = false;

    /** Owner canary by userCode. Empty means no owner is routed to Direct. */
    private Set<String> canaryUserCodes = new LinkedHashSet<>();

    /** Derives per-binding runtime secrets; at least 32 characters, server-only. */
    private String masterSecret;

    private int admissionTtlSeconds = 120;

    /** Crash window for isolated acceptance; ignored unless {@code faultAcceptance} is also true. */
    private String fault;

    /** Explicit second opt-in for {@code fault}; never set in a deployment. */
    private boolean faultAcceptance = false;

    private Cube cube = new Cube();

    private Runtime runtime = new Runtime();

    @Data
    public static class Cube {
        /** CubeAPI base URL, for example http://127.0.0.1:3000. */
        private String apiUrl = "http://127.0.0.1:3000";

        /** Optional CubeAPI key (X-API-Key); never sent to browsers. */
        private String apiKey;

        /** CubeProxy node address and HTTP port used for sandbox traffic. */
        private String proxyHost = "127.0.0.1";

        private int proxyPort = 80;

        private String sandboxDomain = "cube.app";

        /** Pinned READY template built from the fixed Harness image. */
        private String templateId;

        private Duration requestTimeout = Duration.ofSeconds(30);

        /** Egress CIDRs the runtime needs on private networks (control plane, Redis). */
        private List<String> allowOut = new ArrayList<>();
    }

    @Data
    public static class Runtime {
        /** DSH web port inside the runtime sandbox. */
        private int port = 3080;

        /** The runtime's desktop viewer (noVNC) port, exposed by the template. */
        private int desktopPort = 8081;

        /** Host-mount prefix on the Cube node (must be under CubeMaster allowed prefixes). */
        private String hostMountRoot = "/data/shared/byclaw-direct";

        /** Same directory as seen by this backend, for creating owner directories. */
        private String localMountRoot = "/data/shared/byclaw-direct";

        /** Guest path of the owner state mount. */
        private String mountPath = "/mnt/byclaw-state";

        /** UID/GID of the runtime user inside the sandbox; owner directories are 0700 for it. */
        private int guestUid = 1001;

        private int guestGid = 1001;

        private Duration readyTimeout = Duration.ofMinutes(3);

        private Duration readyPollInterval = Duration.ofSeconds(2);

        /** Single-flight ensure lock TTL across backend replicas. */
        private Duration ensureLockTtl = Duration.ofMinutes(5);

        /** How long a backup may hold the owner lock, keeping the paused runtime from being woken. */
        private Duration backupLockTtl = Duration.ofMinutes(30);

        /** Where owner backups are kept; blank means {@code .backups} beside the owner directories. */
        private String backupRoot = "";

        /** Idle time after the last real work before the runtime is drained and paused. */
        private Duration idleTimeout = Duration.ofSeconds(300);

        /**
         * Read-only Redis user (discovery keys only) that runtime tools use to find platform
         * services; blank disables it, e.g. for a managed Redis without ACL support.
         */
        private String toolRedisUser = "byclaw-direct-tools";

        /** Non-secret runtime environment shared by all owners (backend URL, Redis host, ...). */
        private Map<String, String> env = new LinkedHashMap<>();

        /** Secret runtime environment (Redis password, model token keys); never logged. */
        private Map<String, String> secretEnv = new LinkedHashMap<>();
    }
}
