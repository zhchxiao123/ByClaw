package com.iwhalecloud.byai.gateway.direct;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;

import com.iwhalecloud.byai.common.constants.resource.WorkerAgentType;
import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.common.util.MapParamUtil;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeSessionRouteMapper;
import com.iwhalecloud.byai.state.common.exception.BdpRuntimeException;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.domain.chat.dto.MultiAgentMetadata;
import com.iwhalecloud.byai.state.domain.chat.enums.ChatTransport;
import com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Server-side choice between Legacy and Direct for one session. The browser cannot select it:
 * a session is Direct only when the server created it for a canary owner, and the choice is
 * recorded once so Legacy and Direct never write the same session.
 */
@Slf4j
public class DirectRouteSelector {

    public static final String DIRECT = "direct";
    static final String ACCESS_DENIED = "无权访问该会话";

    private final DirectRuntimeProperties properties;
    private final RuntimeSessionRouteMapper routes;
    private final DirectTransactions transactions;
    private java.util.function.BooleanSupplier admissionOpen = () -> true;

    public DirectRouteSelector(DirectRuntimeProperties properties, RuntimeSessionRouteMapper routes) {
        this(properties, routes, DirectTransactions.DIRECT);
    }

    public DirectRouteSelector(DirectRuntimeProperties properties, RuntimeSessionRouteMapper routes,
                               DirectTransactions transactions) {
        this.properties = properties;
        this.routes = routes;
        this.transactions = transactions;
    }

    /**
     * The recorded backend, read outside the caller's transaction: a route recorded moments ago in
     * its own transaction must be seen even where the caller's session already cached "none".
     */
    private String backend(long sessionId) {
        return transactions.requiresNew(() -> routes.selectBackend(sessionId));
    }

    /** Whether new sessions may become Direct; closed during a rollback, when they use Legacy. */
    public DirectRouteSelector withAdmission(java.util.function.BooleanSupplier admissionOpen) {
        this.admissionOpen = admissionOpen;
        return this;
    }

    /** The owner when this turn must use Direct; empty keeps the Legacy route. */
    public Optional<RuntimeOwnerScope> select(ChatProcessContext ctx, LoginInfo login) {
        if (ctx == null || ctx.sessionId == null || ctx.getAssistantChatDto() == null || login == null
            || login.getUserId() == null) {
            return Optional.empty();
        }
        String recorded = backend(ctx.sessionId);
        if (recorded != null) {
            if (!DIRECT.equals(recorded)) return Optional.empty();
            return Optional.of(requireOwner(ctx.sessionId, login));
        }
        if (!properties.isEnabled() || !admissionOpen.getAsBoolean() || !eligibleNewSession(ctx, login)) return Optional.empty();
        RuntimeOwnerScope owner = owner(login);
        try {
            transactions.requiresNew(() -> routes.insert(ctx.sessionId, owner.key(), DIRECT));
        }
        catch (DuplicateKeyException e) {
            // A concurrent first turn recorded the route; follow it.
            return select(ctx, login);
        }
        log.info("Direct route selected for new session, sessionId={}, owner={}", ctx.sessionId, owner.key());
        return Optional.of(owner);
    }

    /**
     * Reject a non-owner before anything is written to a Direct session. Legacy sessions are
     * unaffected. Returns the owner when the session is Direct and belongs to the login.
     */
    /**
     * A session the server just created outside a turn (an upload before the first message): Direct
     * when its first turn would be, recorded now so that turn and its attachments use the owner's
     * runtime. Same owner, canary and personal HARNESS rules as a session created by a turn.
     */
    public Optional<RuntimeOwnerScope> selectForServerCreatedSession(long sessionId, String workerType, Long projectId,
                                                                     LoginInfo login) {
        if (!properties.isEnabled() || !admissionOpen.getAsBoolean() || login == null || login.getUserId() == null
            || !personalWorkspace(projectId)
            || com.iwhalecloud.byai.manager.domain.tenant.TenantRequestContextHolder.get() != null
            || !properties.getCanaryUserCodes().contains(login.getUserCode())
            || !(WorkerAgentType.HARNESS.getCode().equals(workerType) || WorkerAgentType.BYCLAW_DSH.getCode().equals(workerType))
            || backend(sessionId) != null) {
            return Optional.empty();
        }
        RuntimeOwnerScope owner = owner(login);
        transactions.requiresNew(() -> routes.insert(sessionId, owner.key(), DIRECT));
        log.info("Direct route selected for a server-created session, sessionId={}, owner={}", sessionId, owner.key());
        return Optional.of(owner);
    }

    /**
     * The owner whose runtime the next turn would use, read without recording a route: an existing
     * Direct session of this login, or a new personal HARNESS session of a canary owner. Empty when
     * that turn would use Legacy (or the session is someone else's).
     */
    public Optional<RuntimeOwnerScope> ownerOfNextTurn(Long sessionId, String workerType, Long projectId, LoginInfo login) {
        if (login == null || login.getUserId() == null) return Optional.empty();
        RuntimeOwnerScope owner = owner(login);
        String recorded = sessionId == null || sessionId <= 0 ? null : backend(sessionId);
        if (recorded != null) {
            boolean mine = DIRECT.equals(recorded)
                && owner.key().equals(transactions.requiresNew(() -> routes.selectOwner(sessionId)));
            return mine ? Optional.of(owner) : Optional.empty();
        }
        boolean eligible = properties.isEnabled() && admissionOpen.getAsBoolean() && personalWorkspace(projectId)
            && com.iwhalecloud.byai.manager.domain.tenant.TenantRequestContextHolder.get() == null
            && properties.getCanaryUserCodes().contains(login.getUserCode())
            && (WorkerAgentType.HARNESS.getCode().equals(workerType) || WorkerAgentType.BYCLAW_DSH.getCode().equals(workerType));
        return eligible ? Optional.of(owner) : Optional.empty();
    }

    public boolean isDirect(long sessionId) {
        return DIRECT.equals(backend(sessionId));
    }

    public RuntimeOwnerScope requireOwnerIfDirect(Long sessionId, LoginInfo login) {
        if (sessionId == null || !DIRECT.equals(backend(sessionId))) return null;
        return requireOwner(sessionId, login);
    }

    private RuntimeOwnerScope requireOwner(long sessionId, LoginInfo login) {
        if (login == null || login.getUserId() == null) throw new BdpRuntimeException(ACCESS_DENIED);
        RuntimeOwnerScope owner = owner(login);
        if (!owner.key().equals(transactions.requiresNew(() -> routes.selectOwner(sessionId)))) {
            log.warn("Direct session access denied, sessionId={}, requester={}", sessionId, owner.key());
            throw new BdpRuntimeException(ACCESS_DENIED);
        }
        return owner;
    }

    /**
     * The first turn of a session the server created without a turn (an upload before the first
     * message, before any assistant was chosen): nothing has run in it yet on any backend.
     */
    private boolean firstTurn(ChatProcessContext ctx) {
        Long userMessageId = ctx.getUserMessageId();
        if (userMessageId == null || userMessageId <= 0) return false;
        try {
            return transactions.requiresNew(() -> routes.countMessagesBefore(ctx.sessionId, userMessageId)) == 0L;
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    private boolean eligibleNewSession(ChatProcessContext ctx, LoginInfo login) {
        AssistantChatDto dto = ctx.getAssistantChatDto();
        String workerType = MapParamUtil.getStringValue(ctx.getParams(), "worker_agent_type");
        return (dto.isServerCreatedSession() || firstTurn(ctx))
            && ctx.tenantContext == null
            && ChatTransport.WEBSOCKET.equals(ctx.transport)
            && properties.getCanaryUserCodes().contains(login.getUserCode())
            && (WorkerAgentType.HARNESS.getCode().equals(workerType) || WorkerAgentType.BYCLAW_DSH.getCode().equals(workerType))
            && !MultiAgentMetadata.fromExtParams(dto.getExtParams()).hasLanes()
            && (dto.getExtParams() == null || dto.getExtParams().get("groupCoordination") == null)
            && personalWorkspace(dto.getProjectId());
    }

    /**
     * A project's workspace is storage shared by its members, which a private runtime does not
     * mount; project sessions keep the Legacy route rather than run in a missing directory.
     */
    private static boolean personalWorkspace(Long projectId) {
        return projectId == null || projectId <= 0;
    }

    static RuntimeOwnerScope owner(LoginInfo login) {
        long enterprise = login.getEnterpriseId() == null ? 0L : login.getEnterpriseId();
        return new RuntimeOwnerScope(login.getUserId(), login.getUserCode(), enterprise);
    }
}
