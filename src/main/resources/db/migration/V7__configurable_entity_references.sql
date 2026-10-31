ALTER TABLE bandy_tool_parameter
    ADD COLUMN IF NOT EXISTS reference_type VARCHAR(40);

ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS result_entity_type VARCHAR(40);

ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS result_collection_key VARCHAR(64);

-- Existing organization result shape is now declared by its tool metadata.
UPDATE bandy_tool
SET result_entity_type = 'DIRECTION',
    result_collection_key = NULL
WHERE name = 'get_organization_info';

-- These identifiers belong to the organization lookup contract only.
-- Other tools may reuse parameter names without referring to the same entity.
UPDATE bandy_tool_parameter
SET reference_type = CASE name
    WHEN 'directionId' THEN 'DIRECTION'
    WHEN 'serviceId' THEN 'SERVICE'
END
WHERE tool_name = 'get_organization_info'
  AND name IN ('directionId', 'serviceId');

UPDATE bandy_tool_parameter
SET reference_type = 'STRUCTURE'
WHERE tool_name = 'get_organization_info' AND name = 'id';
