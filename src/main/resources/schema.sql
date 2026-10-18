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
       'Assistant conversationnel indépendant',
       'Comprends l’intention de l’utilisateur, réponds dans sa langue et de façon naturelle. Utilise les outils enregistrés lorsque la demande correspond à leur description et à leurs consignes d’utilisation. Extrais les paramètres présents dans la demande et demande une précision lorsqu’un paramètre obligatoire manque ou que la cible est ambiguë. N’invente jamais de données d’entreprise ou de résultats d’outils. Présente fidèlement les résultats reçus.'
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
