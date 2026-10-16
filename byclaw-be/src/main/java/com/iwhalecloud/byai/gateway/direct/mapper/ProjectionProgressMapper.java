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
