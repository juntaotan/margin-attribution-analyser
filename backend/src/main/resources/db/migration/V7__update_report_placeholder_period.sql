-- Align historical placeholder seeds to sample data period (January 2026).
UPDATE report_placeholder_config
SET duration = '2026-01-01 to 2026-01-31',
    prompt = REPLACE(prompt, '2026-08-01 to 2026-08-31', '2026-01-01 to 2026-01-31'),
    updated_at = CURRENT_TIMESTAMP
WHERE duration = '2026-08-01 to 2026-08-31';
