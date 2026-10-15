package com.iwhalecloud.byai.gateway.direct.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** The enterprise each resource belongs to, for scoping a Direct runtime's grants. */
@Mapper
public interface ResourceEnterpriseMapper {

    @Select("""
        <script>
        SELECT CAST(resource_id AS VARCHAR) AS "resourceId", com_acct_id AS "enterpriseId"
        FROM ss_resource
        WHERE resource_id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<Map<String, Object>> enterprises(@Param("ids") Collection<Long> ids);
}
