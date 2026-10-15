SET search_path TO byai;

-- PostgreSQL compatibility for the existing openGauss timestamp defaults.
-- Keep the native openGauss implementation when present.
DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'pg_systimestamp'
          AND p.pronargs = 0
          AND n.nspname IN ('pg_catalog', 'public')
    ) THEN
        EXECUTE $function$
            CREATE FUNCTION public.pg_systimestamp()
            RETURNS timestamp without time zone
            LANGUAGE sql VOLATILE
            AS 'SELECT clock_timestamp()::timestamp without time zone'
        $function$;
    END IF;
END;
$migration$;

-- Direct HARNESS runtime: one logical runtime per trusted owner scope.
-- generation fences stale controllers; create_intent_id reconciles unknown create results.
CREATE TABLE IF NOT EXISTS byai.byclaw_runtime_binding
(
    binding_id       BIGINT       NOT NULL PRIMARY KEY,
    owner_scope      VARCHAR(128) NOT NULL,
    user_id          BIGINT       NOT NULL,
    user_code        VARCHAR(64)  NOT NULL,
    provider         VARCHAR(32)  NOT NULL,
    template_id      VARCHAR(128) NOT NULL,
    generation       BIGINT       NOT NULL DEFAULT 1,
    state            VARCHAR(32)  NOT NULL,
    create_intent_id VARCHAR(64)  NOT NULL,
    sandbox_id       VARCHAR(128),
    traffic_token    VARCHAR(512),
    envd_token       VARCHAR(512),
    host_path        VARCHAR(512) NOT NULL,
    last_error       VARCHAR(512),
    create_time      TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_byclaw_runtime_binding_owner
    ON byai.byclaw_runtime_binding (owner_scope);

COMMENT ON TABLE byai.byclaw_runtime_binding IS 'Direct HARNESS runtime binding per owner scope';
COMMENT ON COLUMN byai.byclaw_runtime_binding.traffic_token IS 'Server-only Cube traffic token; never returned to browsers';
COMMENT ON COLUMN byai.byclaw_runtime_binding.envd_token IS 'Server-only envd access token for runtime bootstrap';

-- Server-side transport choice per session; Legacy and Direct never write the same session.
CREATE TABLE IF NOT EXISTS byai.byclaw_runtime_session_route
(
    session_id  BIGINT       NOT NULL PRIMARY KEY,
    owner_scope VARCHAR(128) NOT NULL,
    backend     VARCHAR(16)  NOT NULL,
    create_time TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE byai.byclaw_runtime_session_route IS 'Server-selected chat backend (legacy or direct) per session';

-- Platform-side admission per (session, business requestId); carrier ids are not part of the key.
CREATE TABLE IF NOT EXISTS byai.byclaw_runtime_request_admission
(
    session_id        BIGINT       NOT NULL,
    request_id        VARCHAR(128) NOT NULL,
    owner_scope       VARCHAR(128) NOT NULL,
    intent_digest     VARCHAR(64)  NOT NULL,
    user_message_id   BIGINT,
    answer_message_id BIGINT,
    trace_id          VARCHAR(64),
    state             VARCHAR(32)  NOT NULL,
    detail            VARCHAR(256),
    create_time       TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time       TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, request_id)
);

COMMENT ON TABLE byai.byclaw_runtime_request_admission IS 'Direct turn admission and idempotency per session and business request';

-- Platform projection position per Direct session; the runtime keeps events until acknowledged.
CREATE TABLE IF NOT EXISTS byai.byclaw_runtime_projection_progress
(
    session_id  BIGINT       NOT NULL PRIMARY KEY,
    owner_scope VARCHAR(128) NOT NULL,
    through_seq BIGINT       NOT NULL DEFAULT 0,
    update_time TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE byai.byclaw_runtime_projection_progress IS 'Highest runtime outbox sequence projected into the platform per Direct session';
