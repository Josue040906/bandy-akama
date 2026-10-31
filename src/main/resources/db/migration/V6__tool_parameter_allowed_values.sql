ALTER TABLE bandy_tool_parameter
    ADD COLUMN IF NOT EXISTS allowed_values TEXT;

UPDATE bandy_tool_parameter
SET allowed_values = '["LIST","COUNT"]'
WHERE tool_name = 'search_agents' AND name = 'resultMode';

UPDATE bandy_tool_parameter
SET allowed_values = '[
  "LIST_DIRECTIONS",
  "GET_DIRECTION",
  "LIST_SERVICES",
  "GET_SERVICE",
  "LIST_POSTES",
  "GET_POSTE",
  "GET_HIERARCHY",
  "GET_RESPONSIBLE",
  "LIST_RESPONSIBLES",
  "COUNT_DIRECTIONS",
  "COUNT_SERVICES",
  "COUNT_POSTES",
  "COUNT_EMPLOYEES_BY_STRUCTURE"
]'
WHERE tool_name = 'get_organization_info' AND name = 'operation';
