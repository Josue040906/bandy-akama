-- Un paramètre peut viser un objet du corps (« body.donnees ») : l'emplacement a besoin de plus de place.
ALTER TABLE bandy_tool_parameter ALTER COLUMN location TYPE VARCHAR(64);
