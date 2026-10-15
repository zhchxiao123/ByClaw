package com.iwhalecloud.byai.gateway.direct.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;

/** Every transition is a compare-and-set on (binding, generation, state). */
@Mapper
public interface RuntimeBindingMapper {

    String COLUMNS = """
        binding_id AS "bindingId", owner_scope AS "ownerScope", user_id AS "userId", user_code AS "userCode",
        provider, template_id AS "templateId", generation, state, create_intent_id AS "createIntentId",
        sandbox_id AS "sandboxId", traffic_token AS "trafficToken", envd_token AS "envdToken",
        host_path AS "hostPath", last_error AS "lastError", create_time AS "createTime", update_time AS "updateTime"
        """;

    @Select("SELECT " + COLUMNS + " FROM byclaw_runtime_binding WHERE owner_scope = #{ownerScope}")
    RuntimeBinding selectByOwner(@Param("ownerScope") String ownerScope);

    /** Unique owner_scope rejects a second binding; callers re-read on duplicate key. */
    @Insert("""
        INSERT INTO byclaw_runtime_binding (binding_id, owner_scope, user_id, user_code, provider, template_id,
            generation, state, create_intent_id, host_path, create_time, update_time)
        VALUES (#{bindingId}, #{ownerScope}, #{userId}, #{userCode}, #{provider}, #{templateId},
            #{generation}, #{state}, #{createIntentId}, #{hostPath}, NOW(), NOW())
        """)
    int insert(RuntimeBinding binding);

    @Update("""
        UPDATE byclaw_runtime_binding
        SET sandbox_id = #{sandboxId}, traffic_token = #{trafficToken}, envd_token = #{envdToken},
            state = #{nextState}, last_error = NULL, update_time = NOW()
        WHERE binding_id = #{bindingId} AND generation = #{generation} AND state = #{expectedState}
        """)
    int recordInstance(@Param("bindingId") long bindingId, @Param("generation") long generation,
                       @Param("expectedState") String expectedState, @Param("nextState") String nextState,
                       @Param("sandboxId") String sandboxId, @Param("trafficToken") String trafficToken,
                       @Param("envdToken") String envdToken);

    @Update("""
        UPDATE byclaw_runtime_binding
        SET state = #{nextState}, last_error = #{lastError}, update_time = NOW()
        WHERE binding_id = #{bindingId} AND generation = #{generation} AND state = #{expectedState}
        """)
    int transition(@Param("bindingId") long bindingId, @Param("generation") long generation,
                   @Param("expectedState") String expectedState, @Param("nextState") String nextState,
                   @Param("lastError") String lastError);

    /** A new create intent after a confirmed-absent instance; fences the old generation. */
    @Update("""
        UPDATE byclaw_runtime_binding
        SET generation = generation + 1, create_intent_id = #{createIntentId}, sandbox_id = NULL,
            traffic_token = NULL, envd_token = NULL, state = #{nextState}, last_error = NULL, update_time = NOW()
        WHERE binding_id = #{bindingId} AND generation = #{generation} AND state = #{expectedState}
        """)
    int renewIntent(@Param("bindingId") long bindingId, @Param("generation") long generation,
                    @Param("expectedState") String expectedState, @Param("nextState") String nextState,
                    @Param("createIntentId") String createIntentId);
}
