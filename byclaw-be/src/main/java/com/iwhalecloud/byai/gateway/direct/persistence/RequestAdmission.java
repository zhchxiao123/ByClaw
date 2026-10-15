package com.iwhalecloud.byai.gateway.direct.persistence;

import java.util.Date;

import lombok.Data;

/** Platform record of one admitted Direct request. */
@Data
public class RequestAdmission {

    private Long sessionId;

    private String requestId;

    private String ownerScope;

    private String intentDigest;

    private Long userMessageId;

    private Long answerMessageId;

    private String traceId;

    private String state;

    private String detail;

    private Date createTime;

    private Date updateTime;
}
