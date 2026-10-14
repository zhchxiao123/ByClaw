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
