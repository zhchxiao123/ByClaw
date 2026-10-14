SET search_path TO byai;

-- Normalize the existing model tag with PostgreSQL-compatible DELETE syntax.
-- Matches the legacy initialization intent; safe to repeat in a transaction.
DELETE FROM byai.byai_system_config_list
WHERE param_group_code = 'MODEL_TAGS' AND param_value = '7';

INSERT INTO byai.byai_system_config_list
    (param_id, param_group_code, param_group_name, param_name,
     param_en_name, param_value, param_desc, param_seq)
VALUES
    (nextval('byai.seq_any_table'), 'MODEL_TAGS', '模型打标', '多模态模型',
     'MULTIMODAL_MODEL', '7', '多模态模型', 7);

-- Opt-in fixture assignment for the isolated issue01 acceptance database only.
-- Unset in ordinary deployment; never changes a production employee by default.
DO $acceptance$
DECLARE
    fixture text;
BEGIN
    BEGIN
        fixture := current_setting('byclaw.acceptance_fixture');
    EXCEPTION WHEN undefined_object THEN
        fixture := NULL;
    END;
    IF fixture = 'issue01' THEN
        UPDATE byai.ss_resource r
        SET worker_agent_type = 'HARNESS', impl_type = 'ASK_AGENT'
        WHERE r.resource_name = '本地 HARNESS 回归 01'
          AND r.resource_biz_type = 'DIG_EMPLOYEE'
          AND r.create_by IN (SELECT user_id FROM byai.po_users WHERE user_code = 'adminvip');
    END IF;
END;
$acceptance$;
