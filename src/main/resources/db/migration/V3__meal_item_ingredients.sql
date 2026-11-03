-- Preserve the ingredient names supplied for every recommended item, including ready-made food.
ALTER TABLE meal_items ADD COLUMN explicit_ingredients jsonb;
