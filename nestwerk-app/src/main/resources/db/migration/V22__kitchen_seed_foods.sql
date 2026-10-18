-- Ergänzt Lebensmittel, die der Rezept-Grundstock braucht und die V21 nicht enthält
INSERT INTO foods (name, category, kcal, protein, fat, carbs, piece_grams, package_grams, shelf_days, source)
SELECT v.name, v.category, v.kcal, v.protein, v.fat, v.carbs, v.piece_grams, v.package_grams, v.shelf_days, 'SEED'
FROM (VALUES ('Wasser', 'DRINKS', 0, 0, 0, 0, NULL::double precision, NULL::double precision, NULL::int)) AS v (name, category, kcal, protein, fat, carbs,
                                                                                              piece_grams, package_grams, shelf_days)
WHERE NOT EXISTS (SELECT 1 FROM foods f WHERE lower(f.name) = lower(v.name));
