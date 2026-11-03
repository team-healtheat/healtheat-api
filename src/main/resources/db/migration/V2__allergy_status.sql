-- An unanswered allergy question is distinct from none or unknown.
-- Existing allergen rows are preserved; no absence of rows is treated as "none".
ALTER TABLE user_preferences ADD COLUMN allergy_status text NOT NULL DEFAULT 'UNANSWERED';
