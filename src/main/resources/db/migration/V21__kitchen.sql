-- Küche: Lebensmittel, Rezepte, Essensplan, Vorrat, Einkaufsliste
CREATE TABLE foods (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100)             NOT NULL,
    category      VARCHAR(20)              NOT NULL,
    kcal          DOUBLE PRECISION,
    protein       DOUBLE PRECISION,
    fat           DOUBLE PRECISION,
    carbs         DOUBLE PRECISION,
    piece_grams   DOUBLE PRECISION,
    package_grams DOUBLE PRECISION,
    shelf_days    INT,
    barcode       VARCHAR(32) UNIQUE,
    source        VARCHAR(10)              NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX foods_name_idx ON foods (lower(name));

CREATE TABLE recipes (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(200)             NOT NULL,
    description TEXT,
    servings    INT                      NOT NULL CHECK (servings BETWEEN 1 AND 100),
    minutes     INT CHECK (minutes BETWEEN 1 AND 2880),
    tags        VARCHAR(500),
    source_url  VARCHAR(2000),
    steps       TEXT,
    created_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version     BIGINT                   NOT NULL DEFAULT 0
);
CREATE INDEX recipes_title_idx ON recipes (lower(title));

CREATE TABLE recipe_ingredients (
    id        BIGSERIAL PRIMARY KEY,
    recipe_id BIGINT       NOT NULL REFERENCES recipes (id) ON DELETE CASCADE,
    position  INT          NOT NULL,
    amount    DOUBLE PRECISION,
    unit      VARCHAR(10),
    food_id   BIGINT REFERENCES foods (id) ON DELETE SET NULL,
    text      VARCHAR(300) NOT NULL,
    note      VARCHAR(200)
);
CREATE INDEX recipe_ingredients_recipe_idx ON recipe_ingredients (recipe_id, position);
CREATE INDEX recipe_ingredients_food_idx ON recipe_ingredients (food_id);

CREATE TABLE meal_slots (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(50) NOT NULL,
    sort_order INT         NOT NULL,
    share      INT         NOT NULL CHECK (share BETWEEN 0 AND 100),
    auto_fill  BOOLEAN     NOT NULL DEFAULT FALSE
);
INSERT INTO meal_slots (name, sort_order, share, auto_fill)
VALUES ('Frühstück', 10, 25, FALSE),
       ('Mittagessen', 20, 35, FALSE),
       ('Abendessen', 30, 30, TRUE),
       ('Snack', 40, 10, FALSE);

CREATE TABLE meals (
    id        BIGSERIAL PRIMARY KEY,
    day       DATE         NOT NULL,
    slot_id   BIGINT       NOT NULL REFERENCES meal_slots (id) ON DELETE CASCADE,
    recipe_id BIGINT REFERENCES recipes (id) ON DELETE SET NULL,
    title     VARCHAR(200) NOT NULL,
    cooked_at TIMESTAMP WITH TIME ZONE,
    version   BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX meals_day_idx ON meals (day);

CREATE TABLE meal_eaters (
    meal_id   BIGINT           NOT NULL REFERENCES meals (id) ON DELETE CASCADE,
    member_id BIGINT           NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    portions  DOUBLE PRECISION NOT NULL CHECK (portions > 0),
    PRIMARY KEY (meal_id, member_id)
);

ALTER TABLE family_members ADD COLUMN kcal_goal INT CHECK (kcal_goal BETWEEN 500 AND 10000);

CREATE TABLE pantry_items (
    id          BIGSERIAL PRIMARY KEY,
    food_id     BIGINT REFERENCES foods (id) ON DELETE SET NULL,
    name        VARCHAR(100)             NOT NULL,
    amount      DOUBLE PRECISION         NOT NULL CHECK (amount >= 0),
    unit        VARCHAR(10)              NOT NULL,
    location    VARCHAR(50),
    best_before DATE,
    added_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX pantry_items_food_idx ON pantry_items (food_id);

CREATE TABLE shopping_items (
    id        BIGSERIAL PRIMARY KEY,
    name      VARCHAR(100)             NOT NULL,
    food_id   BIGINT REFERENCES foods (id) ON DELETE SET NULL,
    amount    DOUBLE PRECISION,
    unit      VARCHAR(10),
    note      VARCHAR(200),
    origin    VARCHAR(10)              NOT NULL,
    status    VARCHAR(10)              NOT NULL,
    sent_at   TIMESTAMP WITH TIME ZONE,
    bought_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX shopping_items_status_idx ON shopping_items (status);

-- Rezeptfoto als Anhang
ALTER TABLE attachments ADD COLUMN recipe_id BIGINT REFERENCES recipes (id) ON DELETE CASCADE;
ALTER TABLE attachments DROP CONSTRAINT attachments_check;
ALTER TABLE attachments ADD CONSTRAINT attachments_check
    CHECK (num_nonnulls(todo_id, comment_id, contact_id, recipe_id) = 1);
CREATE INDEX attachments_recipe_idx ON attachments (recipe_id);

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('KITCHEN_VIEW', 'Küche', 'Küche ansehen', 'Rezepte, Essensplan, Einkauf und Vorrat sehen', 210),
       ('KITCHEN_RECIPES_EDIT', 'Küche', 'Rezepte pflegen', 'Rezepte und Lebensmittel anlegen und ändern', 220),
       ('KITCHEN_PLAN', 'Küche', 'Essen planen', 'Essensplan bearbeiten und Mahlzeiten als gekocht buchen', 230),
       ('KITCHEN_SHOPPING', 'Küche', 'Einkauf und Vorrat', 'Einkaufsliste, Bring! und Vorrat bearbeiten', 240),
       ('KITCHEN_SETTINGS', 'Küche', 'Küche einstellen', 'Bring!-Konto, Mahlzeiten und Kalorienziele', 250);

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
         CROSS JOIN (VALUES ('KITCHEN_VIEW'),
                            ('KITCHEN_RECIPES_EDIT'),
                            ('KITCHEN_PLAN'),
                            ('KITCHEN_SHOPPING')) AS p (permission)
WHERE r.name = 'Benutzer' AND r.system_role;

-- Grundstock: Durchschnittswerte pro 100 g; Stückgewicht und Packungsgröße in g; Haltbarkeit in Tagen
INSERT INTO foods (name, category, kcal, protein, fat, carbs, piece_grams, package_grams, shelf_days, source)
VALUES ('Zwiebel', 'PRODUCE', 40, 1.2, 0.2, 8, 80, NULL, 30, 'SEED'),
       ('Rote Zwiebel', 'PRODUCE', 42, 1.1, 0.1, 9, 100, NULL, 30, 'SEED'),
       ('Knoblauch', 'PRODUCE', 149, 6.4, 0.5, 33, 4, NULL, 60, 'SEED'),
       ('Kartoffel', 'PRODUCE', 77, 2, 0.1, 17, 150, 2500, 30, 'SEED'),
       ('Süßkartoffel', 'PRODUCE', 86, 1.6, 0.1, 20, 250, NULL, 21, 'SEED'),
       ('Karotte', 'PRODUCE', 41, 0.9, 0.2, 10, 80, 1000, 21, 'SEED'),
       ('Möhre', 'PRODUCE', 41, 0.9, 0.2, 10, 80, 1000, 21, 'SEED'),
       ('Tomate', 'PRODUCE', 18, 0.9, 0.2, 3.9, 100, NULL, 7, 'SEED'),
       ('Kirschtomate', 'PRODUCE', 20, 1, 0.3, 3.9, 15, 250, 7, 'SEED'),
       ('Gurke', 'PRODUCE', 15, 0.7, 0.1, 3.6, 400, NULL, 7, 'SEED'),
       ('Paprika', 'PRODUCE', 31, 1, 0.3, 6, 160, NULL, 10, 'SEED'),
       ('Zucchini', 'PRODUCE', 17, 1.2, 0.3, 3.1, 250, NULL, 10, 'SEED'),
       ('Aubergine', 'PRODUCE', 25, 1, 0.2, 6, 300, NULL, 7, 'SEED'),
       ('Brokkoli', 'PRODUCE', 34, 2.8, 0.4, 7, 400, NULL, 5, 'SEED'),
       ('Blumenkohl', 'PRODUCE', 25, 1.9, 0.3, 5, 800, NULL, 7, 'SEED'),
       ('Spinat', 'PRODUCE', 23, 2.9, 0.4, 3.6, NULL, 250, 3, 'SEED'),
       ('Eisbergsalat', 'PRODUCE', 14, 0.9, 0.1, 3, 500, NULL, 7, 'SEED'),
       ('Kopfsalat', 'PRODUCE', 13, 1.4, 0.2, 2.2, 300, NULL, 5, 'SEED'),
       ('Rucola', 'PRODUCE', 25, 2.6, 0.7, 3.7, NULL, 125, 4, 'SEED'),
       ('Champignon', 'PRODUCE', 22, 3.1, 0.3, 3.3, 20, 250, 5, 'SEED'),
       ('Lauch', 'PRODUCE', 31, 1.5, 0.3, 7, 250, NULL, 10, 'SEED'),
       ('Knollensellerie', 'PRODUCE', 42, 1.5, 0.3, 9, 600, NULL, 30, 'SEED'),
       ('Staudensellerie', 'PRODUCE', 16, 0.7, 0.2, 3, 40, NULL, 10, 'SEED'),
       ('Hokkaido-Kürbis', 'PRODUCE', 63, 1.7, 0.6, 12.6, 1000, NULL, 60, 'SEED'),
       ('Frühlingszwiebel', 'PRODUCE', 32, 1.8, 0.2, 7, 15, NULL, 7, 'SEED'),
       ('Ingwer', 'PRODUCE', 80, 1.8, 0.8, 18, 30, NULL, 21, 'SEED'),
       ('Chilischote', 'PRODUCE', 40, 1.9, 0.4, 9, 5, NULL, 10, 'SEED'),
       ('Petersilie', 'PRODUCE', 36, 3, 0.8, 6, 30, NULL, 5, 'SEED'),
       ('Schnittlauch', 'PRODUCE', 30, 3.3, 0.7, 4.4, 25, NULL, 5, 'SEED'),
       ('Basilikum', 'PRODUCE', 23, 3.2, 0.6, 2.7, 20, NULL, 5, 'SEED'),
       ('Koriander', 'PRODUCE', 23, 2.1, 0.5, 3.7, 20, NULL, 5, 'SEED'),
       ('Zitrone', 'PRODUCE', 29, 1.1, 0.3, 9, 100, NULL, 21, 'SEED'),
       ('Limette', 'PRODUCE', 30, 0.7, 0.2, 10, 60, NULL, 21, 'SEED'),
       ('Apfel', 'PRODUCE', 52, 0.3, 0.2, 14, 150, NULL, 30, 'SEED'),
       ('Banane', 'PRODUCE', 89, 1.1, 0.3, 23, 120, NULL, 6, 'SEED'),
       ('Orange', 'PRODUCE', 47, 0.9, 0.1, 12, 180, NULL, 14, 'SEED'),
       ('Birne', 'PRODUCE', 57, 0.4, 0.1, 15, 170, NULL, 10, 'SEED'),
       ('Erdbeere', 'PRODUCE', 32, 0.7, 0.3, 7.7, 15, 500, 3, 'SEED'),
       ('Heidelbeere', 'PRODUCE', 57, 0.7, 0.3, 14, NULL, 125, 5, 'SEED'),
       ('Weintraube', 'PRODUCE', 69, 0.7, 0.2, 18, NULL, 500, 7, 'SEED'),
       ('Avocado', 'PRODUCE', 160, 2, 15, 9, 200, NULL, 5, 'SEED'),
       ('Mango', 'PRODUCE', 60, 0.8, 0.4, 15, 300, NULL, 7, 'SEED'),
       ('Ananas', 'PRODUCE', 50, 0.5, 0.1, 13, 1000, NULL, 7, 'SEED'),
       ('Kiwi', 'PRODUCE', 61, 1.1, 0.5, 15, 75, NULL, 14, 'SEED'),
       ('Rotkohl', 'PRODUCE', 29, 1.4, 0.2, 7, 1000, NULL, 30, 'SEED'),
       ('Weißkohl', 'PRODUCE', 25, 1.3, 0.1, 6, 1500, NULL, 30, 'SEED'),
       ('Spitzkohl', 'PRODUCE', 27, 1.5, 0.2, 5, 1000, NULL, 14, 'SEED'),
       ('Rosenkohl', 'PRODUCE', 43, 3.4, 0.3, 9, 15, 500, 7, 'SEED'),
       ('Grüne Bohnen', 'PRODUCE', 31, 1.8, 0.2, 7, NULL, 500, 5, 'SEED'),
       ('Fenchel', 'PRODUCE', 31, 1.2, 0.2, 7, 250, NULL, 10, 'SEED'),
       ('Radieschen', 'PRODUCE', 16, 0.7, 0.1, 3.4, 10, NULL, 7, 'SEED'),
       ('Rote Bete', 'PRODUCE', 43, 1.6, 0.2, 10, 150, 500, 21, 'SEED'),
       ('Milch', 'CHILLED', 64, 3.4, 3.5, 4.8, NULL, 1000, 7, 'SEED'),
       ('Butter', 'CHILLED', 741, 0.7, 83, 0.6, NULL, 250, 60, 'SEED'),
       ('Sahne', 'CHILLED', 292, 2.4, 30, 3.2, NULL, 200, 10, 'SEED'),
       ('Schmand', 'CHILLED', 240, 2.7, 24, 3.7, NULL, 200, 14, 'SEED'),
       ('Saure Sahne', 'CHILLED', 160, 3, 15, 4, NULL, 200, 14, 'SEED'),
       ('Crème fraîche', 'CHILLED', 292, 2.4, 30, 2.6, NULL, 200, 14, 'SEED'),
       ('Joghurt', 'CHILLED', 63, 3.5, 3.5, 4.1, NULL, 500, 14, 'SEED'),
       ('Griechischer Joghurt', 'CHILLED', 120, 4, 10, 4, NULL, 500, 14, 'SEED'),
       ('Magerquark', 'CHILLED', 73, 12, 0.3, 4, NULL, 250, 14, 'SEED'),
       ('Frischkäse', 'CHILLED', 250, 6, 23, 3, NULL, 200, 21, 'SEED'),
       ('Mozzarella', 'CHILLED', 250, 18, 19, 1, 125, 125, 14, 'SEED'),
       ('Gouda', 'CHILLED', 356, 25, 28, 0, 25, 400, 30, 'SEED'),
       ('Parmesan', 'CHILLED', 392, 35, 28, 0, NULL, 150, 60, 'SEED'),
       ('Feta', 'CHILLED', 264, 14, 21, 4, NULL, 200, 21, 'SEED'),
       ('Emmentaler', 'CHILLED', 380, 28, 30, 0, 25, 400, 30, 'SEED'),
       ('Reibekäse', 'CHILLED', 330, 25, 26, 0, NULL, 200, 21, 'SEED'),
       ('Ei', 'CHILLED', 155, 13, 11, 1.1, 60, 600, 21, 'SEED'),
       ('Tofu', 'CHILLED', 145, 15, 9, 2, NULL, 200, 30, 'SEED'),
       ('Frische Hefe', 'CHILLED', 100, 12, 1.2, 11, NULL, 42, 14, 'SEED'),
       ('Blätterteig', 'CHILLED', 400, 5.5, 26, 36, NULL, 275, 21, 'SEED'),
       ('Pizzateig', 'CHILLED', 260, 7, 5, 46, NULL, 400, 21, 'SEED'),
       ('Gnocchi', 'CHILLED', 140, 3.2, 0.2, 31, NULL, 500, 30, 'SEED'),
       ('Brot', 'BAKERY', 230, 6.6, 1.2, 46, 50, 750, 5, 'SEED'),
       ('Vollkornbrot', 'BAKERY', 220, 7, 1.5, 40, 50, 500, 7, 'SEED'),
       ('Toastbrot', 'BAKERY', 260, 8, 3.5, 48, 25, 500, 7, 'SEED'),
       ('Brötchen', 'BAKERY', 270, 9, 1.5, 55, 55, NULL, 2, 'SEED'),
       ('Baguette', 'BAKERY', 260, 8.5, 1.5, 52, 250, NULL, 2, 'SEED'),
       ('Tortilla-Wrap', 'BAKERY', 300, 8, 7, 50, 60, 370, 30, 'SEED'),
       ('Laugenbrezel', 'BAKERY', 270, 8, 1, 55, 80, NULL, 2, 'SEED'),
       ('Hähnchenbrust', 'MEAT_FISH', 110, 23, 1.5, 0, 150, 400, 2, 'SEED'),
       ('Hähnchenschenkel', 'MEAT_FISH', 190, 18, 13, 0, 250, NULL, 2, 'SEED'),
       ('Putenbrust', 'MEAT_FISH', 107, 24, 1, 0, 150, 400, 2, 'SEED'),
       ('Rinderhackfleisch', 'MEAT_FISH', 250, 18, 20, 0, NULL, 500, 1, 'SEED'),
       ('Hackfleisch', 'MEAT_FISH', 260, 17, 21, 0, NULL, 500, 1, 'SEED'),
       ('Rindergulasch', 'MEAT_FISH', 140, 21, 6, 0, NULL, 500, 3, 'SEED'),
       ('Schweinefilet', 'MEAT_FISH', 120, 21, 4, 0, NULL, 500, 3, 'SEED'),
       ('Schweineschnitzel', 'MEAT_FISH', 115, 22, 2, 0, 150, NULL, 3, 'SEED'),
       ('Bratwurst', 'MEAT_FISH', 300, 13, 27, 1, 100, NULL, 7, 'SEED'),
       ('Wiener Würstchen', 'MEAT_FISH', 260, 13, 23, 1, 50, 250, 14, 'SEED'),
       ('Kochschinken', 'MEAT_FISH', 110, 19, 3.5, 1, 25, 200, 7, 'SEED'),
       ('Salami', 'MEAT_FISH', 380, 22, 32, 1, 10, 100, 14, 'SEED'),
       ('Speckwürfel', 'MEAT_FISH', 330, 15, 30, 0, NULL, 125, 30, 'SEED'),
       ('Lachs', 'MEAT_FISH', 200, 20, 13, 0, 125, 250, 2, 'SEED'),
       ('Kabeljau', 'MEAT_FISH', 80, 18, 0.7, 0, 125, 250, 2, 'SEED'),
       ('Spaghetti', 'PANTRY', 355, 12.5, 1.5, 71, NULL, 500, 730, 'SEED'),
       ('Nudeln', 'PANTRY', 355, 12.5, 1.5, 71, NULL, 500, 730, 'SEED'),
       ('Penne', 'PANTRY', 355, 12.5, 1.5, 71, NULL, 500, 730, 'SEED'),
       ('Lasagneplatten', 'PANTRY', 355, 12.5, 1.5, 71, NULL, 500, 730, 'SEED'),
       ('Reis', 'PANTRY', 350, 7, 0.6, 78, NULL, 1000, 730, 'SEED'),
       ('Basmatireis', 'PANTRY', 350, 8, 1, 77, NULL, 1000, 730, 'SEED'),
       ('Risottoreis', 'PANTRY', 350, 7, 0.6, 79, NULL, 1000, 730, 'SEED'),
       ('Couscous', 'PANTRY', 360, 12, 1.5, 72, NULL, 500, 365, 'SEED'),
       ('Bulgur', 'PANTRY', 350, 12, 1.5, 69, NULL, 500, 365, 'SEED'),
       ('Quinoa', 'PANTRY', 368, 14, 6, 64, NULL, 500, 365, 'SEED'),
       ('Rote Linsen', 'PANTRY', 340, 24, 1.5, 50, NULL, 500, 365, 'SEED'),
       ('Kichererbsen', 'PANTRY', 120, 7, 2.5, 16, NULL, 400, 730, 'SEED'),
       ('Kidneybohnen', 'PANTRY', 100, 7, 0.5, 14, NULL, 400, 730, 'SEED'),
       ('Weiße Bohnen', 'PANTRY', 95, 7, 0.5, 13, NULL, 400, 730, 'SEED'),
       ('Mais', 'PANTRY', 80, 2.9, 1.2, 15, NULL, 285, 730, 'SEED'),
       ('Gehackte Tomaten', 'PANTRY', 21, 1, 0.1, 4, NULL, 400, 730, 'SEED'),
       ('Passierte Tomaten', 'PANTRY', 32, 1.4, 0.2, 5.6, NULL, 500, 730, 'SEED'),
       ('Tomatenmark', 'PANTRY', 82, 4.3, 0.5, 16, NULL, 200, 365, 'SEED'),
       ('Kokosmilch', 'PANTRY', 180, 1.8, 18, 3, NULL, 400, 730, 'SEED'),
       ('Thunfisch', 'PANTRY', 115, 26, 1, 0, NULL, 150, 730, 'SEED'),
       ('Gemüsebrühe', 'PANTRY', 5, 0.2, 0.3, 0.6, NULL, 1000, 365, 'SEED'),
       ('Hühnerbrühe', 'PANTRY', 5, 0.5, 0.2, 0.4, NULL, 1000, 365, 'SEED'),
       ('Mehl', 'PANTRY', 340, 10, 1, 71, NULL, 1000, 365, 'SEED'),
       ('Dinkelmehl', 'PANTRY', 340, 12, 1.5, 69, NULL, 1000, 365, 'SEED'),
       ('Grieß', 'PANTRY', 330, 11, 0.8, 69, NULL, 500, 365, 'SEED'),
       ('Speisestärke', 'PANTRY', 350, 0.3, 0.1, 86, NULL, 400, 730, 'SEED'),
       ('Paniermehl', 'PANTRY', 360, 11, 2, 73, NULL, 400, 365, 'SEED'),
       ('Zucker', 'PANTRY', 400, 0, 0, 100, NULL, 1000, 1825, 'SEED'),
       ('Brauner Zucker', 'PANTRY', 390, 0, 0, 98, NULL, 500, 1825, 'SEED'),
       ('Puderzucker', 'PANTRY', 400, 0, 0, 100, NULL, 250, 1825, 'SEED'),
       ('Vanillezucker', 'PANTRY', 400, 0, 0, 100, NULL, 8, 730, 'SEED'),
       ('Backpulver', 'PANTRY', 100, 0, 0, 25, NULL, 15, 730, 'SEED'),
       ('Trockenhefe', 'PANTRY', 330, 40, 5, 40, NULL, 7, 365, 'SEED'),
       ('Honig', 'PANTRY', 304, 0.3, 0, 82, NULL, 500, 730, 'SEED'),
       ('Ahornsirup', 'PANTRY', 260, 0, 0, 67, NULL, 250, 365, 'SEED'),
       ('Haferflocken', 'PANTRY', 370, 13, 7, 59, NULL, 500, 365, 'SEED'),
       ('Müsli', 'PANTRY', 370, 10, 6, 64, NULL, 750, 180, 'SEED'),
       ('Cornflakes', 'PANTRY', 380, 7, 1, 84, NULL, 375, 180, 'SEED'),
       ('Olivenöl', 'PANTRY', 884, 0, 100, 0, NULL, 750, 540, 'SEED'),
       ('Rapsöl', 'PANTRY', 884, 0, 100, 0, NULL, 1000, 365, 'SEED'),
       ('Sonnenblumenöl', 'PANTRY', 884, 0, 100, 0, NULL, 1000, 365, 'SEED'),
       ('Öl', 'PANTRY', 884, 0, 100, 0, NULL, 1000, 365, 'SEED'),
       ('Balsamicoessig', 'PANTRY', 88, 0.5, 0, 17, NULL, 500, 1095, 'SEED'),
       ('Essig', 'PANTRY', 20, 0, 0, 0.6, NULL, 750, 1095, 'SEED'),
       ('Sojasauce', 'PANTRY', 53, 8, 0.5, 5, NULL, 250, 730, 'SEED'),
       ('Senf', 'PANTRY', 66, 4, 4, 5, NULL, 200, 365, 'SEED'),
       ('Ketchup', 'PANTRY', 112, 1.2, 0.1, 26, NULL, 500, 365, 'SEED'),
       ('Mayonnaise', 'PANTRY', 680, 1, 75, 1.5, NULL, 250, 180, 'SEED'),
       ('Pesto', 'PANTRY', 460, 5, 46, 5, NULL, 190, 365, 'SEED'),
       ('Oliven', 'PANTRY', 140, 1, 14, 1, NULL, 200, 730, 'SEED'),
       ('Kapern', 'PANTRY', 23, 2.4, 0.9, 5, NULL, 100, 730, 'SEED'),
       ('Erdnussbutter', 'PANTRY', 590, 25, 50, 13, NULL, 350, 365, 'SEED'),
       ('Nuss-Nougat-Creme', 'PANTRY', 540, 6, 31, 57, NULL, 450, 365, 'SEED'),
       ('Marmelade', 'PANTRY', 250, 0.4, 0.1, 60, NULL, 450, 365, 'SEED'),
       ('Schokolade', 'PANTRY', 540, 7, 31, 57, NULL, 100, 365, 'SEED'),
       ('Walnüsse', 'PANTRY', 650, 15, 65, 7, NULL, 200, 365, 'SEED'),
       ('Mandeln', 'PANTRY', 600, 21, 52, 6, NULL, 200, 365, 'SEED'),
       ('Haselnüsse', 'PANTRY', 640, 14, 61, 10, NULL, 200, 365, 'SEED'),
       ('Cashewkerne', 'PANTRY', 580, 18, 46, 30, NULL, 200, 365, 'SEED'),
       ('Sonnenblumenkerne', 'PANTRY', 580, 21, 49, 12, NULL, 250, 365, 'SEED'),
       ('Rosinen', 'PANTRY', 300, 3, 0.5, 70, NULL, 250, 365, 'SEED'),
       ('Erbsen', 'FROZEN', 81, 5.4, 0.4, 14, NULL, 450, 180, 'SEED'),
       ('Pommes frites', 'FROZEN', 150, 2.5, 5, 24, NULL, 750, 180, 'SEED'),
       ('Tiefkühlpizza', 'FROZEN', 240, 10, 9, 29, 350, NULL, 180, 'SEED'),
       ('Fischstäbchen', 'FROZEN', 200, 12, 9, 17, 30, 450, 180, 'SEED'),
       ('Garnelen', 'FROZEN', 85, 18, 1, 0, NULL, 250, 180, 'SEED'),
       ('Beerenmischung', 'FROZEN', 45, 1, 0.3, 9, NULL, 300, 180, 'SEED'),
       ('Orangensaft', 'DRINKS', 45, 0.7, 0.2, 10, NULL, 1000, 7, 'SEED'),
       ('Apfelsaft', 'DRINKS', 46, 0.1, 0.1, 11, NULL, 1000, 7, 'SEED'),
       ('Mineralwasser', 'DRINKS', 0, 0, 0, 0, NULL, 1000, 365, 'SEED'),
       ('Weißwein', 'DRINKS', 82, 0.1, 0, 2.6, NULL, 750, 365, 'SEED'),
       ('Rotwein', 'DRINKS', 85, 0.1, 0, 2.6, NULL, 750, 365, 'SEED'),
       ('Bier', 'DRINKS', 43, 0.5, 0, 3.6, NULL, 500, 180, 'SEED'),
       ('Salz', 'SPICES', 0, 0, 0, 0, NULL, 500, 3650, 'SEED'),
       ('Pfeffer', 'SPICES', 250, 10, 3, 64, NULL, 50, 730, 'SEED'),
       ('Paprikapulver', 'SPICES', 280, 14, 13, 54, NULL, 50, 730, 'SEED'),
       ('Currypulver', 'SPICES', 325, 14, 14, 56, NULL, 50, 730, 'SEED'),
       ('Zimt', 'SPICES', 250, 4, 1.2, 81, NULL, 50, 730, 'SEED'),
       ('Oregano', 'SPICES', 265, 9, 4, 69, NULL, 20, 730, 'SEED'),
       ('Thymian', 'SPICES', 276, 9, 7, 64, NULL, 20, 730, 'SEED'),
       ('Rosmarin', 'SPICES', 330, 5, 15, 64, NULL, 20, 730, 'SEED'),
       ('Kreuzkümmel', 'SPICES', 375, 18, 22, 44, NULL, 50, 730, 'SEED'),
       ('Muskatnuss', 'SPICES', 525, 6, 36, 49, NULL, 20, 730, 'SEED'),
       ('Chiliflocken', 'SPICES', 300, 12, 14, 50, NULL, 30, 730, 'SEED'),
       ('Lorbeerblatt', 'SPICES', 313, 8, 8, 75, 0.2, 10, 730, 'SEED');
