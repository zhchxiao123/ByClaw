package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.mapper.ProjectionProgressMapper;
import com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission;
import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;

class DirectStatusTest {

    private static final long NOW = 1_790_000_000_000L;
    private final RuntimeBindingService bindings = mock(RuntimeBindingService.class);
    private final DirectChatGateway gateway = mock(DirectChatGateway.class);
    private final ProjectionProgressMapper progress = mock(ProjectionProgressMapper.class);
    private final DirectStatus status = new DirectStatus(bindings, gateway, progress, DirectTransactions.DIRECT,
        Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    private static RuntimeBinding binding(String state, String lastError) {
        RuntimeBinding binding = new RuntimeBinding();
        binding.setBindingId(7L);
        binding.setOwnerScope("u100.e0");
        binding.setGeneration(4L);
        binding.setState(state);
        binding.setSandboxId("sandbox-secret-id");
        binding.setTrafficToken("traffic-token");
        binding.setEnvdToken("envd-token");
        binding.setLastError(lastError);
        binding.setUpdateTime(new Date(NOW - 5000));
        return binding;
    }

    private static RequestAdmission admission(String requestId, String state, String owner) {
        RequestAdmission admission = new RequestAdmission();
        admission.setSessionId(9000L);
        admission.setRequestId(requestId);
        admission.setOwnerScope(owner);
        admission.setState(state);
        admission.setDetail("detail of " + requestId);
        admission.setUpdateTime(new Date(NOW - 30_000));
        return admission;
    }

    @Test
    void thePageNeverShowsAPreparingOrFailedRuntimeAsReady() {
        assertThat(DirectStatus.pageStatus(RuntimeBindingService.READY)).isEqualTo("RUNNING");
        assertThat(DirectStatus.pageStatus(RuntimeBindingService.PROVISIONING)).isEqualTo("STARTING");
        assertThat(DirectStatus.pageStatus(RuntimeBindingService.RESUMING)).isEqualTo("STARTING");
        assertThat(DirectStatus.pageStatus(RuntimeBindingService.DRAINING)).isEqualTo("RELEASING");
        assertThat(DirectStatus.pageStatus(RuntimeBindingService.PAUSED)).isEqualTo("PAUSED");
        assertThat(DirectStatus.pageStatus(RuntimeBindingService.FAILED)).isEqualTo("FAILED");
        assertThat(DirectStatus.pageStatus(RuntimeBindingService.RECONCILE_REQUIRED)).isEqualTo("FAILED");
        assertThat(DirectStatus.pageStatus(RuntimeBindingService.RESTORE_PENDING)).isEqualTo("RESTORE_PENDING");
        assertThat(DirectStatus.fault("capacity: CubeAPI returned 429")).contains(RuntimeFault.CAPACITY);
        assertThat(DirectStatus.fault("nonsense")).isEmpty();
    }

    @Test
    void aFailedEnvironmentIsExplainedWithoutWakingAndOwnersNeverSeeRawIdentifiers() {
        when(bindings.find("u100.e0")).thenReturn(Optional.of(binding(RuntimeBindingService.FAILED,
            "capacity: CubeAPI POST /sandboxes returned 429 at /data/shared/host/path")));
        when(progress.openAdmissions(0, 5000)).thenReturn(List.of(admission("req-open", "dispatching", "u100.e0"),
            admission("req-other", "accepted", "u200.e0")));
        when(progress.recentFailures("u100.e0", 10)).thenReturn(List.of(admission("req-failed", "rejected", "u100.e0")));
        when(progress.progressOf("u100.e0")).thenReturn(List.of(Map.of("sessionId", 9000L, "throughSeq", 42L, "lineage", "L1")));

        JSONObject owner = status.of("u100.e0", null, null, false);

        JSONObject environment = owner.getJSONObject("environment");
        assertThat(environment.getString("pageStatus")).isEqualTo("FAILED");
        assertThat(environment.getJSONObject("fault").getString("code")).isEqualTo("capacity");
        assertThat(environment.getJSONObject("fault").getBoolean("retryable")).isTrue();
        assertThat(environment.getJSONObject("fault").containsKey("detail")).isFalse();
        assertThat(environment.getString("sandbox")).startsWith("sbx-").doesNotContain("sandbox-secret-id");
        assertThat(environment.getLong("since")).isEqualTo(NOW - 5000);
        assertThat(owner.getJSONObject("runtime").getBoolean("running")).isFalse();
        assertThat(owner.getJSONObject("queue").getIntValue("open")).as("only this owner's work").isEqualTo(1);
        assertThat(owner.getJSONObject("queue").getJSONArray("items").getJSONObject(0).getLong("ageSeconds")).isEqualTo(30L);
        assertThat(owner.getJSONArray("sync").getJSONObject(0).getLong("projectedThroughSeq")).isEqualTo(42L);
        assertThat(owner.getJSONArray("recentFailures").getJSONObject(0).getString("requestId")).isEqualTo("req-failed");
        String all = owner.toJSONString();
        assertThat(all).doesNotContain("traffic-token").doesNotContain("envd-token").doesNotContain("/data/shared");
        verify(gateway, never()).runtimeStatus(anyString());

        JSONObject operator = status.of("u100.e0", null, null, true);
        assertThat(operator.getJSONObject("environment").getString("sandbox")).isEqualTo("sandbox-secret-id");
        assertThat(operator.getJSONObject("environment").getJSONObject("fault").getString("detail")).contains("429");
        assertThat(operator.toJSONString()).doesNotContain("traffic-token").doesNotContain("envd-token");
    }

    @Test
    void aRunningRuntimeReportsItsProcessLeasesAndProjectionLagAndARequestItsLayers() {
        when(bindings.find("u100.e0")).thenReturn(Optional.of(binding(RuntimeBindingService.READY, null)));
        JSONObject runtime = JSONObject.parseObject("""
            {"bootId":"b-1","startedAt":1,"lineage":"L1","generation":4,"protocol":"byclaw.direct/v1",
             "leases":{"active":1,"kinds":{"turn":1},"lastReleasedAt":5},"running":1,"draining":false,
             "pendingProjection":{"9000":3},"now":1790000000250}""");
        when(gateway.runtimeStatus("u100.e0")).thenReturn(Optional.of(runtime));
        when(progress.openAdmissions(0, 5000)).thenReturn(List.of(admission("req-open", "accepted", "u100.e0")));
        when(progress.progressOf("u100.e0")).thenReturn(List.of(Map.of("sessionId", 9000L, "throughSeq", 40L)));
        RequestAdmission open = admission("req-open", "accepted", "u100.e0");
        when(gateway.admission(9000L, "req-open")).thenReturn(open);
        when(gateway.runtimeTurnStateOf(anyString(), any())).thenReturn(Optional.of("running"));
        when(gateway.projectedThroughSeq(9000L)).thenReturn(40L);

        JSONObject view = status.of("u100.e0", "req-open", 9000L, false);

        assertThat(view.getJSONObject("runtime").getString("bootId")).isEqualTo("b-1");
        assertThat(view.getJSONObject("runtime").getJSONObject("leases").getIntValue("turn")).isEqualTo(1);
        assertThat(view.getJSONObject("runtime").getLong("clockSkewMs")).isEqualTo(250L);
        assertThat(view.getJSONArray("sync").getJSONObject(0).getLong("runtimePending")).isEqualTo(3L);
        assertThat(view.getJSONObject("request").getString("runtimeState")).isEqualTo("running");
        assertThat(view.getJSONObject("request").getLong("projectedThroughSeq")).isEqualTo(40L);

        when(gateway.admission(9000L, "req-foreign")).thenReturn(admission("req-foreign", "accepted", "u200.e0"));
        assertThat(status.of("u100.e0", "req-foreign", 9000L, false).get("request")).as("another owner's request").isNull();
    }
}
