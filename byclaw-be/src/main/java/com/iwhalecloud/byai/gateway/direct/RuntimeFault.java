package com.iwhalecloud.byai.gateway.direct;

/**
 * Why an owner's runtime cannot serve a turn now. The code is the one fault identity used in the
 * binding's last error, logs, admission detail and status; the message is what the page shows.
 * Every fault keeps the binding, the instance and the owner's data.
 */
public enum RuntimeFault {
    BUSY("busy", true, "另一个请求正在准备运行环境，请稍后重试"),
    CONCURRENT_UPDATE("concurrent_update", true, "运行环境状态已被其他请求更新，请重试"),
    CAPACITY("capacity", true, "运行资源暂时不足，已保留原会话，请稍后重试"),
    TEMPLATE_UNAVAILABLE("template_unavailable", false, "运行环境模板未就绪，已保留原会话，请联系管理员"),
    CREATE_FAILED("create_failed", false, "运行环境创建失败，已保留原会话"),
    CREATE_UNKNOWN("create_unknown", true, "运行环境创建结果待确认，请稍后重试"),
    DUPLICATE_INSTANCES("duplicate_instances", false, "同一创建意图存在多个实例，需要人工协调"),
    RECORD_INCOMPLETE("record_incomplete", false, "运行环境记录缺少实例，需要人工协调"),
    INSTANCE_MISSING("instance_missing", false, "运行环境实例不存在，已保留会话数据，需要恢复"),
    INSTANCE_STOPPED("instance_stopped", false, "运行环境实例已停止，已保留会话数据，需要恢复"),
    RESUME_FAILED("resume_failed", false, "运行环境唤醒失败，已保留原实例与数据"),
    STATE_MISSING("state_missing", false, "运行环境数据不可用，已停止启动以保护原数据，需要恢复"),
    RESTORE_PENDING("restore_pending", true, "运行环境正在恢复数据，请稍后重试"),
    PROTOCOL_INCOMPATIBLE("protocol_incompatible", false, "运行环境版本与平台不兼容，已保留原实例与数据"),
    IDENTITY_MISMATCH("identity_mismatch", false, "运行环境身份校验失败"),
    START_TIMEOUT("start_timeout", true, "运行环境启动超时，请稍后重试"),
    REOPEN_TIMEOUT("reopen_timeout", true, "运行环境恢复后未能重新接受请求，请稍后重试"),
    INTERRUPTED("interrupted", true, "运行环境准备被中断");

    private final String code;
    private final boolean retryable;
    private final String message;

    RuntimeFault(String code, boolean retryable, String message) {
        this.code = code;
        this.retryable = retryable;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public String message() {
        return message;
    }

    /** The binding's last error: the fault code first, so status and logs agree. */
    String lastError(String detail) {
        return detail == null || detail.isBlank() ? code : code + ": " + detail;
    }
}
