-- Do not remove or merge existing meal records automatically: a human must resolve
-- any conflicting or incomplete historical data before enforcing this policy.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM meal_records WHERE meal_date IS NULL OR meal_type IS NULL OR meal_type NOT IN ('BREAKFAST', 'LUNCH', 'DINNER')) THEN
        RAISE EXCEPTION 'Cannot enforce meal record policy: existing records have missing or invalid dates or meal types; resolve them first';
    END IF;
    IF EXISTS (SELECT 1 FROM meal_records GROUP BY user_id, meal_date, meal_type HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Cannot enforce meal record policy: existing records contain duplicate user/date/meal slots; resolve them first';
    END IF;
END $$;
ALTER TABLE meal_records ALTER COLUMN meal_date SET NOT NULL;
ALTER TABLE meal_records ALTER COLUMN meal_type SET NOT NULL;
ALTER TABLE meal_records ADD CONSTRAINT meal_records_meal_type_valid CHECK (meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER'));
ALTER TABLE meal_records ADD CONSTRAINT meal_records_user_date_meal_unique UNIQUE (user_id, meal_date, meal_type);
