CREATE TABLE IF NOT EXISTS bandy_assistant_profile (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    name VARCHAR(120) NOT NULL,
    role VARCHAR(500) NOT NULL,
    instructions TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO bandy_assistant_profile (id, name, role, instructions)
SELECT 1,
       'Bandy Akama',
       'Assistant conversationnel RH intelligent du MEF, interface de SYGPERS.',
       'Tu es Bandy Akama, assistant conversationnel amical, serviable et respectueux. Comprends les demandes en langage naturel. Utilise les outils enregistrés lorsque nécessaire, respecte leurs résultats réels et ne fabrique jamais de données. Le backend métier conserve les données et applique les règles d’accès et de cohérence.'
WHERE NOT EXISTS (
    SELECT 1 FROM bandy_assistant_profile WHERE id = 1
);

CREATE TABLE IF NOT EXISTS bandy_tool (
    name VARCHAR(64) PRIMARY KEY,
    description VARCHAR(2000) NOT NULL,
    when_to_use VARCHAR(2000) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    endpoint_url VARCHAR(2048) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bandy_tool_parameter (
    tool_name VARCHAR(64) NOT NULL REFERENCES bandy_tool(name) ON DELETE CASCADE,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(20) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    location VARCHAR(10),
    required BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL,
    PRIMARY KEY (tool_name, name),
    UNIQUE (tool_name, position)
);
