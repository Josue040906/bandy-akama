-- Un outil peut décrire un vrai langage de requête (champs, listes, opérateurs) :
-- ses consignes et la description de ses paramètres ont besoin de plus de place.
ALTER TABLE bandy_tool ALTER COLUMN when_to_use TYPE VARCHAR(4000);
ALTER TABLE bandy_tool_parameter ALTER COLUMN description TYPE VARCHAR(2000);
