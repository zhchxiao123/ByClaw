package com.iwhalecloud.byai.gateway.direct.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission;

@Mapper
public interface RequestAdmissionMapper {

    @Select("""
        SELECT session_id AS "sessionId", request_id AS "requestId", owner_scope AS "ownerScope",
               intent_digest AS "intentDigest", user_message_id AS "userMessageId",
               answer_message_id AS "answerMessageId", trace_id AS "traceId", state, detail,
               create_time AS "createTime", update_time AS "updateTime"
        FROM byclaw_runtime_request_admission
        WHERE session_id = #{sessionId} AND request_id = #{requestId}
        """)
    RequestAdmission select(@Param("sessionId") long sessionId, @Param("requestId") String requestId);

    @Select("""
        SELECT session_id AS "sessionId", request_id AS "requestId", owner_scope AS "ownerScope",
               intent_digest AS "intentDigest", user_message_id AS "userMessageId",
               answer_message_id AS "answerMessageId", trace_id AS "traceId", state, detail,
               create_time AS "createTime", update_time AS "updateTime"
        FROM byclaw_runtime_request_admission
        WHERE session_id = #{sessionId} AND answer_message_id = #{answerMessageId}
        """)
    RequestAdmission selectByAnswer(@Param("sessionId") long sessionId, @Param("answerMessageId") long answerMessageId);

    /** The primary key rejects a second admission of the same request. */
    @Insert("""
        INSERT INTO byclaw_runtime_request_admission (session_id, request_id, owner_scope, intent_digest, state,
            create_time, update_time)
        VALUES (#{sessionId}, #{requestId}, #{ownerScope}, #{intentDigest}, #{state}, NOW(), NOW())
        """)
    int insert(RequestAdmission admission);

    @Update("""
        UPDATE byclaw_runtime_request_admission
        SET user_message_id = #{userMessageId}, answer_message_id = #{answerMessageId}, trace_id = #{traceId},
            state = #{nextState}, update_time = NOW()
        WHERE session_id = #{sessionId} AND request_id = #{requestId} AND state = #{expectedState}
        """)
    int bind(@Param("sessionId") long sessionId, @Param("requestId") String requestId,
             @Param("expectedState") String expectedState, @Param("nextState") String nextState,
             @Param("userMessageId") Long userMessageId, @Param("answerMessageId") Long answerMessageId,
             @Param("traceId") String traceId);

    @Update("""
        UPDATE byclaw_runtime_request_admission
        SET state = #{state}, detail = #{detail}, update_time = NOW()
        WHERE session_id = #{sessionId} AND request_id = #{requestId}
        """)
    int record(@Param("sessionId") long sessionId, @Param("requestId") String requestId,
               @Param("state") String state, @Param("detail") String detail);
}
