-- changeset biliwind:137-expand-honeypot-probes
INSERT INTO honeypot_rules (rule_key, enabled, action)
VALUES ('sensitive_file_probe', true, 'OBSERVE')
ON CONFLICT (rule_key) DO NOTHING;

-- rollback DELETE FROM honeypot_rules WHERE rule_key = 'sensitive_file_probe';
