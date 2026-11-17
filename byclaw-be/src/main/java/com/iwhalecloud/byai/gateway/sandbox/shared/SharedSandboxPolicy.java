package com.iwhalecloud.byai.gateway.sandbox.shared;

import static com.iwhalecloud.byai.gateway.sandbox.shared.SharedSandboxPolicyConfig.SHARED;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.iwhalecloud.byai.state.domain.sys.service.ByaiSystemConfigService;

/**
 * 每次共享路由前检查当前使用资格；映射和在途任务的生命周期由平台管理。
 */
@Component
public class SharedSandboxPolicy {


    private static final Logger log = org.slf4j.LoggerFactory.getLogger(SharedSandboxPolicy.class);

    public static final String USER_NAS = "BYCLAW_SANDBOX_SHARED_USER_NAS_ENABLED";
    public static final String POLICY = "BYCLAW_SANDBOX_SHARED_POLICY";
    public static final String BY_TENANT = "BYCLAW_SANDBOX_SHARED_BY_TENANT";
    public static final String HARNESS_POOL = "BYCLAW_HARNESS_STATELESS_POLICY";

    @Autowired
    private ByaiSystemConfigService byaiSystemConfigService;

    /**
     * 每次读取策略并直接反序列化为配置对象；读取或解析失败时记录堆栈并返回无共享资格。
     *
     * @param userCode 用户工号，匹配时去除首尾空白并区分大小写
     * @return 用户具有共享资格时为 true；工号为空或不符合策略时为 false
     */
    public boolean eligible(String userCode) {
        if (StringUtils.isBlank(userCode)) {
            return false;
        }
        try {
            String value = byaiSystemConfigService.getDcSystemConfigValueByCode(POLICY);
            SharedSandboxPolicyConfig policy = JSON.parseObject(value, SharedSandboxPolicyConfig.class);
            return eligible(policy, userCode);
        } catch (Exception unavailable) {
            log.error(unavailable.getMessage(), unavailable);
            return false;
        }
    }


    /**
     * 根据配置对象判断资格：默认共享且不在排除名单中的有效用户才可共享。
     *
     * @param policy   反序列化后的策略对象，允许为 null
     * @param userCode 用户工号，匹配时去除首尾空白并区分大小写
     * @return 默认模式为共享、工号非空且未被排除时为 true，否则为 false
     */
    public static boolean eligible(SharedSandboxPolicyConfig policy, String userCode) {
        return StringUtils.isNotBlank(userCode) && policy != null && SHARED.equals(policy.getDefaultMode())
            && policy.getExcludedUsers() != null
            && policy.getExcludedUsers().stream().noneMatch(user -> userCode.trim().equals(StringUtils.trim(user)));
    }

    /**
     * 用户 NAS 仅对开启 NAS 策略且具有共享资格的 DSH 用户生效。
     *
     * @param userCode   用户工号
     * @param serviceKey 沙箱服务标识，仅 byclaw-dsh 支持此策略
     * @return NAS 开关开启且用户具有 DSH 共享资格时为 true，否则为 false
     * @throws com.iwhalecloud.byai.state.common.exception.BdpRuntimeException NAS 开关值非法
     */
    public boolean userNasEnabled(String userCode, String serviceKey) {
        if (!SharedSandboxGateway.DSH_SERVICE.equals(serviceKey)) {
            return false;
        }
        String value = StringUtils.trimToEmpty(byaiSystemConfigService.getDcSystemConfigValueByCode(USER_NAS));
        if (value.isEmpty() || "0".equals(value)) {
            return false;
        }
        if (!"1".equals(value)) {
            throw new com.iwhalecloud.byai.state.common.exception.BdpRuntimeException("INVALID_SHARED_USER_NAS_POLICY");
        }
        return eligible(userCode);
    }

    /**
     * 判断共享池是否按租户隔离；配置读取异常向上传递，不猜测分池策略。
     *
     * @return 分池开关值去除首尾空白后为 1 时返回 true，否则返回 false
     */
    public boolean byTenant() {
        // 分池策略不可读时不猜测全局池，防止本应隔离的租户进入同一实例。
        return "1".equals(StringUtils.trimToEmpty(byaiSystemConfigService.getDcSystemConfigValueByCode(BY_TENANT)));
    }

    /**
     * 用户的 byclaw-dsh 上下文属于哪一组共享 harness（挂载组或无状态组），每次决策时重新读取。
     * 参数为空表示只有一组，由平台按来源服务解析；参数无法解析或缺少规格时抛出，不猜测分组，
     * 以免用户落到错误的组或回退为独享沙箱。
     *
     * @param userCode 用户工号，匹配时去除首尾空白并区分大小写
     * @return 用户所在的组；未配置分组时为 null
     */
    public HarnessPool harnessPool(String userCode) {
        String value = StringUtils.trimToEmpty(byaiSystemConfigService.getDcSystemConfigValueByCode(HARNESS_POOL));
        if (value.isEmpty()) {
            return null;
        }
        HarnessPoolPolicyConfig config;
        try {
            config = JSON.parseObject(value, HarnessPoolPolicyConfig.class);
        } catch (RuntimeException invalid) {
            log.error("系统参数 {} 不是合法的 JSON 配置", HARNESS_POOL, invalid);
            throw new com.iwhalecloud.byai.state.common.exception.BdpRuntimeException("INVALID_HARNESS_STATELESS_POLICY");
        }
        return harnessPool(config, userCode);
    }

    /**
     * 根据配置对象决定用户所在的组：名单优先，其余用户按 defaultMode。
     *
     * @param config   反序列化后的配置
     * @param userCode 用户工号
     * @return 用户所在的组
     * @throws com.iwhalecloud.byai.state.common.exception.BdpRuntimeException 配置不完整或模式非法
     */
    public static HarnessPool harnessPool(HarnessPoolPolicyConfig config, String userCode) {
        String mounted = config == null ? null : StringUtils.trimToNull(config.getMountedServiceKey());
        String stateless = config == null ? null : StringUtils.trimToNull(config.getStatelessServiceKey());
        String mode = config == null ? null : StringUtils.defaultIfBlank(StringUtils.trim(config.getDefaultMode()), HarnessPoolPolicyConfig.MOUNTED);
        if (mounted == null || stateless == null || mounted.equals(stateless)
            || !(HarnessPoolPolicyConfig.MOUNTED.equals(mode) || HarnessPoolPolicyConfig.STATELESS.equals(mode))) {
            throw new com.iwhalecloud.byai.state.common.exception.BdpRuntimeException("INVALID_HARNESS_STATELESS_POLICY");
        }
        String user = StringUtils.trimToEmpty(userCode);
        boolean useStateless;
        if (listed(config.getStatelessUsers(), user)) {
            useStateless = true;
        } else if (listed(config.getMountedUsers(), user)) {
            useStateless = false;
        } else {
            useStateless = HarnessPoolPolicyConfig.STATELESS.equals(mode);
        }
        return useStateless ? new HarnessPool(stateless, mounted, true) : new HarnessPool(mounted, stateless, false);
    }

    private static boolean listed(java.util.Set<String> users, String user) {
        return users != null && !user.isEmpty() && users.stream().anyMatch(entry -> user.equals(StringUtils.trim(entry)));
    }
}
