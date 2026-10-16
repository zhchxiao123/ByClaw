package com.iwhalecloud.byai.state.application.service.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.gateway.direct.DirectRouteSelector;
import com.iwhalecloud.byai.state.application.service.chat.ChatFileArtifactApplicationService;
import com.iwhalecloud.byai.state.domain.session.service.SessionMemberService;
import com.iwhalecloud.byai.state.domain.session.service.SessionService;
import com.iwhalecloud.byai.state.domain.session.service.SessionWriteAccess;

/**
 * Who may read a session's messages through the page APIs: the owner scope of a Direct session;
 * for any other session the platform's view rule (creator, member, project member or admin).
 * Writes (feedback aside, e.g. deleting a message) also need the session write rule. Reading never
 * reaches the runtime. The checks never throw, so a refusal does not mark the caller's transaction
 * rollback-only. Share links and the open API carry their own authority and do not pass here.
 */
@Component
public class SessionReadAccess {

    private final SessionService sessions;
    private final SessionMemberService members;
    private final ObjectProvider<ChatFileArtifactApplicationService> files;
    private final ObjectProvider<DirectRouteSelector> direct;

    public SessionReadAccess(SessionService sessions, SessionMemberService members,
                             ObjectProvider<ChatFileArtifactApplicationService> files,
                             ObjectProvider<DirectRouteSelector> direct) {
        this.sessions = sessions;
        this.members = members;
        this.files = files;
        this.direct = direct;
    }

    public boolean canRead(Long sessionId) {
        LoginInfo login = CurrentUserHolder.getLoginInfo();
        if (sessionId == null || login == null) return false;
        try {
            DirectRouteSelector routes = direct.getIfAvailable();
            if (routes != null && routes.isDirect(sessionId)) return routes.requireOwnerIfDirect(sessionId, login) != null;
            // A session the page opens before it is stored has nothing to read yet: not a refusal.
            if (sessions.findById(sessionId) == null) return true;
            return files.getObject().canView(sessionId);
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    public boolean canWrite(Long sessionId) {
        return canRead(sessionId)
            && SessionWriteAccess.canWrite(sessions.findById(sessionId), CurrentUserHolder.getCurrentUserId(), members);
    }

    /** The items whose session the current login may read, in order. */
    public <T> List<T> readable(List<T> items, Function<T, Long> sessionOf) {
        Map<Long, Boolean> decided = new HashMap<>();
        List<T> kept = new ArrayList<>();
        for (T item : items) {
            Long sessionId = sessionOf.apply(item);
            if (sessionId != null && decided.computeIfAbsent(sessionId, this::canRead)) kept.add(item);
        }
        return kept;
    }
}
