package com.iwhalecloud.byai.gateway.direct.persistence;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** Durable owner-to-runtime binding. Contains server-only tokens: never serialize to a browser. */
@TableName("byclaw_runtime_binding")
@Data
public class RuntimeBinding {

    @TableId("binding_id")
    private Long bindingId;

    private String ownerScope;

    private Long userId;

    private String userCode;

    private String provider;

    private String templateId;

    private Long generation;

    private String state;

    private String createIntentId;

    private String sandboxId;

    private String trafficToken;

    private String envdToken;

    private String hostPath;

    private String lastError;

    private Date createTime;

    private Date updateTime;

    @Override
    public String toString() {
        return "RuntimeBinding[bindingId=" + bindingId + ", ownerScope=" + ownerScope + ", generation=" + generation
            + ", state=" + state + ", sandboxId=" + sandboxId + "]";
    }
}
