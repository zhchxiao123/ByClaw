package com.iwhalecloud.byai.gateway.direct;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;

import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.ReadyRuntime;

import okhttp3.Request;

/**
 * The owner's desktop: the noVNC viewer of the browser inside its runtime, reached through the
 * platform with the owner's login (CubeProxy and its traffic token stay server-side). An open
 * viewer is real interaction, so the runtime is not paused while one is connected.
 */
public class DirectDesktop {

    private final RuntimeBindingService bindings;
    private final CubeSandboxClient cube;
    private final RuntimeScopeAuthority authority;
    private final int port;
    private final Map<String, AtomicInteger> viewers = new ConcurrentHashMap<>();
    private final String secret;
    private final java.time.Clock clock;
    static final java.time.Duration TICKET_TTL = java.time.Duration.ofHours(8);

    public DirectDesktop(RuntimeBindingService bindings, CubeSandboxClient cube, RuntimeScopeAuthority authority, int port,
                         String masterSecret, java.time.Clock clock) {
        this.bindings = bindings;
        this.cube = cube;
        this.authority = authority;
        this.port = port;
        this.secret = DirectProtocol.hmacHex(masterSecret, "byclaw-direct-desktop");
        this.clock = clock;
    }

    /**
     * A viewer ticket for the login's owner scope. The viewer page is opened in a frame or tab
     * without the platform's headers, so the ticket sits in the path and authenticates the page,
     * every asset and the stream alike. It names the login session (a logout revokes it) and expires.
     */
    public String ticket(RuntimeOwnerScope owner, String loginSessionId) {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            (owner.key() + "|" + owner.userCode() + "|" + StringUtils.defaultString(loginSessionId) + "|"
                + (clock.millis() + TICKET_TTL.toMillis())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return payload + "." + DirectProtocol.hmacHex(secret, payload);
    }

    /** The owner a valid, unexpired, unrevoked ticket names, while that scope may still act. */
    public Optional<RuntimeOwnerScope> verify(String ticket) {
        int dot = ticket == null ? -1 : ticket.lastIndexOf('.');
        if (dot <= 0) return Optional.empty();
        String payload = ticket.substring(0, dot);
        if (!java.security.MessageDigest.isEqual(DirectProtocol.hmacHex(secret, payload).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            ticket.substring(dot + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        String[] parts;
        try {
            parts = new String(java.util.Base64.getUrlDecoder().decode(payload), java.nio.charset.StandardCharsets.UTF_8).split("\\|", -1);
        }
        catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (parts.length != 4 || Long.parseLong(parts[3]) < clock.millis()
            || (!parts[2].isEmpty() && com.iwhalecloud.byai.common.login.auth.LoginRevocation.revoked(parts[2]))) {
            return Optional.empty();
        }
        java.util.regex.Matcher key = java.util.regex.Pattern.compile("u(\\d+)\\.e(\\d+)").matcher(parts[0]);
        if (!key.matches()) return Optional.empty();
        RuntimeOwnerScope owner = new RuntimeOwnerScope(Long.parseLong(key.group(1)), parts[1], Long.parseLong(key.group(2)));
        return authority.revoked(owner.key()) == null ? Optional.of(owner) : Optional.empty();
    }

    /** The login's own owner scope, when it may use a Direct runtime now. */
    public Optional<RuntimeOwnerScope> owner(LoginInfo login) {
        if (login == null || login.getUserId() == null || login.getUserCode() == null) return Optional.empty();
        RuntimeOwnerScope owner = DirectRouteSelector.owner(login);
        return authority.revoked(owner.key()) == null ? Optional.of(owner) : Optional.empty();
    }

    /**
     * Opening the desktop wakes the owner's existing runtime; its assets and stream never do, and
     * the desktop never creates a runtime that a conversation has not.
     */
    public Optional<ReadyRuntime> runtime(RuntimeOwnerScope owner, boolean wake) {
        boolean exists = bindings.find(owner).map(binding -> binding.getSandboxId() != null).orElse(false);
        if (!exists) return Optional.empty();
        return wake ? Optional.of(bindings.ensure(owner)) : bindings.readyWithoutWake(owner);
    }

    public Request.Builder request(ReadyRuntime runtime, String pathAndQuery) {
        return cube.sandboxRequest(runtime.sandboxId(), runtime.trafficToken(), port, pathAndQuery);
    }

    public CubeSandboxClient cube() {
        return cube;
    }

    public void opened(String ownerKey) {
        viewers.computeIfAbsent(ownerKey, key -> new AtomicInteger()).incrementAndGet();
    }

    public void closed(String ownerKey) {
        viewers.computeIfPresent(ownerKey, (key, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    /** Whether a viewer is connected to this owner's desktop through this backend. */
    public boolean inUse(String ownerKey) {
        AtomicInteger count = viewers.get(ownerKey);
        return count != null && count.get() > 0;
    }

    /** The page's sandbox entry for this desktop, when the owner has a runtime. */
    public Optional<Map<String, Object>> sandboxEntry(LoginInfo login, String contextPath) {
        return owner(login).flatMap(bindings::find).filter(binding -> binding.getSandboxId() != null).map(binding -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("userCode", login.getUserCode());
            entry.put("sandboxType", "direct");
            entry.put("sandboxId", "direct");
            // The page shows ready as running and creating/resuming as transitioning; a paused
            // runtime is asleep (opening the viewer wakes it) and a fault says why it is not running.
            entry.put("status", DirectStatus.pageStatus(binding.getState()));
            entry.put("runtimeState", binding.getState());
            DirectStatus.fault(binding.getLastError()).ifPresent(fault -> entry.put("statusDetail", fault.message()));
            entry.put("instanceEndpoints", Map.of("vnc", contextPath + "/direct-desktop/"
                + ticket(DirectRouteSelector.owner(login), login.getSessionId()) + "/"));
            return entry;
        });
    }
}
