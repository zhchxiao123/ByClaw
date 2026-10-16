package com.iwhalecloud.byai.gateway.direct.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission;

@Mapper
public interface ProjectionProgressMapper {

    @Select("SELECT through_seq FROM byclaw_runtime_projection_progress WHERE session_id = #{sessionId}")
    Long throughSeq(@Param("sessionId") long sessionId);

    @Insert("""
        INSERT INTO byclaw_runtime_projection_progress (session_id, owner_scope, through_seq, update_time)
        VALUES (#{sessionId}, #{ownerScope}, 0, NOW())
        """)
    int insert(@Param("sessionId") long sessionId, @Param("ownerScope") String ownerScope);

    /** The ID mapping of one owner scope, for its backup: session routes, admissions and projection positions. */
    @Select("""
        SELECT session_id AS "sessionId", backend, create_time AS "createTime"
        FROM byclaw_runtime_session_route WHERE owner_scope = #{ownerScope} ORDER BY session_id
        """)
    List<java.util.Map<String, Object>> routesOf(@Param("ownerScope") String ownerScope);

    @Select("""
        SELECT session_id AS "sessionId", request_id AS "requestId", state, detail, user_message_id AS "userMessageId",
               answer_message_id AS "answerMessageId", trace_id AS "traceId", update_time AS "updateTime"
        FROM byclaw_runtime_request_admission WHERE owner_scope = #{ownerScope} ORDER BY create_time
        """)
    List<java.util.Map<String, Object>> admissionsOf(@Param("ownerScope") String ownerScope);

    @Select("""
        SELECT session_id AS "sessionId", through_seq AS "throughSeq", lineage
        FROM byclaw_runtime_projection_progress WHERE owner_scope = #{ownerScope} ORDER BY session_id
        """)
    List<java.util.Map<String, Object>> progressOf(@Param("ownerScope") String ownerScope);

    /** The owner's latest admissions that ended without completing, newest first. */
    @Select("""
        SELECT session_id AS "sessionId", request_id AS "requestId", owner_scope AS "ownerScope", state, detail,
               trace_id AS "traceId", create_time AS "createTime", update_time AS "updateTime"
        FROM byclaw_runtime_request_admission
        WHERE owner_scope = #{ownerScope} AND state IN ('failed', 'rejected', 'cancelled')
        ORDER BY update_time DESC LIMIT #{limit}
        """)
    List<RequestAdmission> recentFailures(@Param("ownerScope") String ownerScope, @Param("limit") int limit);

    @Select("SELECT lineage FROM byclaw_runtime_projection_progress WHERE session_id = #{sessionId}")
    String lineage(@Param("sessionId") long sessionId);

    /** Sets the position and its outbox lineage exactly: adopting, or resyncing after the runtime log changed. */
    @Update("""
        UPDATE byclaw_runtime_projection_progress SET lineage = #{lineage}, through_seq = #{throughSeq}, update_time = NOW()
        WHERE session_id = #{sessionId}
        """)
    int rebase(@Param("sessionId") long sessionId, @Param("lineage") String lineage, @Param("throughSeq") long throughSeq);

    /** Monotonic: an older position never moves the watermark back. */
    @Update("""
        UPDATE byclaw_runtime_projection_progress SET through_seq = #{throughSeq}, update_time = NOW()
        WHERE session_id = #{sessionId} AND through_seq < #{throughSeq}
        """)
    int advance(@Param("sessionId") long sessionId, @Param("throughSeq") long throughSeq);

    /** Admissions whose projection may still be missing: open and not touched for a while. */
    @Select("""
        SELECT session_id AS "sessionId", request_id AS "requestId", owner_scope AS "ownerScope",
               intent_digest AS "intentDigest", user_message_id AS "userMessageId",
               answer_message_id AS "answerMessageId", trace_id AS "traceId", state, detail,
               create_time AS "createTime", update_time AS "updateTime"
        FROM byclaw_runtime_request_admission
        WHERE state IN ('accepted', 'unknown', 'dispatching', 'sending')
          AND update_time < NOW() - (#{settleSeconds} || ' seconds')::interval
        ORDER BY update_time
        LIMIT #{limit}
        """)
    List<RequestAdmission> openAdmissions(@Param("settleSeconds") int settleSeconds, @Param("limit") int limit);
}
