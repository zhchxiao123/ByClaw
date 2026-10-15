package com.iwhalecloud.byai.gateway.direct.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Server-side chat backend per session; written once, so one session never has two writers. */
@Mapper
public interface RuntimeSessionRouteMapper {

    @Select("SELECT backend FROM byclaw_runtime_session_route WHERE session_id = #{sessionId}")
    String selectBackend(@Param("sessionId") long sessionId);

    @Select("SELECT owner_scope FROM byclaw_runtime_session_route WHERE session_id = #{sessionId}")
    String selectOwner(@Param("sessionId") long sessionId);

    @Insert("""
        INSERT INTO byclaw_runtime_session_route (session_id, owner_scope, backend, create_time)
        VALUES (#{sessionId}, #{ownerScope}, #{backend}, NOW())
        """)
    int insert(@Param("sessionId") long sessionId, @Param("ownerScope") String ownerScope,
               @Param("backend") String backend);
}
