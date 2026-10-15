package com.iwhalecloud.byai.state.domain.session.service;

import java.util.List;

import com.iwhalecloud.byai.manager.entity.session.ByaiSession;
import com.iwhalecloud.byai.state.domain.session.enums.SessionType;

/**
 * Who may add to or change a session: its creator for a personal session, a user member for a
 * group session. The stored session type decides; a type declared by the client never does.
 */
public final class SessionWriteAccess {

    private SessionWriteAccess() {
    }

    public static boolean isGroup(ByaiSession session) {
        return session != null && SessionType.HS_AS.getCode().equalsIgnoreCase(session.getSessionType());
    }

    public static boolean canWrite(ByaiSession session, Long userId, SessionMemberService members) {
        if (session == null || userId == null) return false;
        if (!isGroup(session)) return session.getCreatorId() == null || session.getCreatorId().equals(userId);
        List<?> found = members.findSessionMembers(session.getSessionId(), "USER", userId);
        return found != null && !found.isEmpty();
    }
}
