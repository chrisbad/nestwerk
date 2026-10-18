-- Putzplan heißt jetzt Haushaltsplan
UPDATE acl_actions SET label = 'Haushaltsplan ansehen'
WHERE object_type = 'CHORE' AND key = 'VIEW';

UPDATE permissions SET description = 'Tablets für den Haushaltsplan koppeln und wieder abmelden'
WHERE key = 'HOUSEHOLD_DEVICE_MANAGE';
