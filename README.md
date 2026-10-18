# Bandy Akama

Bandy Akama is a Spring Boot conversational assistant powered by Google's Gemini API. It stores the assistant profile and registered HTTP tools in its own PostgreSQL database; backend projects no longer declare function descriptions in their source code.

## Configuration

Create a dedicated PostgreSQL database named `bandy_akama`, then configure:

| Variable | Purpose |
| --- | --- |
| `BANDY_DB_URL` | PostgreSQL JDBC URL; defaults to `jdbc:postgresql://localhost:5433/bandy_akama` |
| `BANDY_DB_USERNAME` | Database user; defaults to `postgres` |
| `BANDY_DB_PASSWORD` | Database password |
| `GEMINI_API_KEY` | Required Google Gemini API key |
| `GEMINI_MODEL` | Optional model; defaults to `gemini-2.5-flash` |
| `BANDY_REGISTRATION_KEY` | Required secret for administration and calls from trusted backends |
| `JWT_SECRET` | Same HS256 signing secret used by `assistant-rh-api`; required to validate user sessions |
| `BANDY_CORS_ALLOWED_ORIGINS` | Comma-separated allowed browser origins; defaults to `http://localhost:5173` |

The application creates its tables and initial assistant profile on startup. It does not create the PostgreSQL database itself.

For a local RH integration test, stop any existing Assistant RH API and Bandy instances, then run `.\scripts\start-local-assistant.ps1` from PowerShell. The script prompts securely for any missing Gemini, JWT, or administration secrets, starts both backends with the same environment, and registers `searchEmployees`. `JWT_SECRET` must be identical to the secret used to issue RH user tokens.

After signing in to the frontend, run `.\scripts\test-chat.ps1` in PowerShell to test the terminal chat. It prompts for the RH access token without echoing it, then sends the requested message to Bandy.

## Assistant profile

The initial profile is inserted into PostgreSQL only when the profile table is empty. Read or update it through `GET` or `PUT /api/assistant/profile`, with the `X-Bandy-Registration-Key` header:

```json
{
  "name": "Bandy Akama",
  "role": "Assistant RH conversationnel",
  "instructions": "Comprends l’intention. Utilise les outils enregistrés pour obtenir les données RH. Demande une précision si la demande est ambiguë et n’invente jamais de données."
}
```

The profile is loaded from the database for each chat request, so changes apply without restarting Bandy.

## Tool catalogue

Manage tools using `GET /api/tools`, `POST /api/tools`, `PUT /api/tools/{name}` and `DELETE /api/tools/{name}`. All require the registration key. A tool has a stable name, a description, guidance for when Gemini should use it, an HTTP method and URL, an enabled flag, and its argument schema. Definitions and parameters are persisted in PostgreSQL. A disabled tool remains manageable but is not exposed to Gemini or callable.

Supported argument types are `string`, `integer`, `number` and `boolean`; locations are `query`, `path` and `body`. GET arguments default to query parameters; other methods default to JSON body parameters. Path placeholders must be required parameters declared with `"location":"path"`. Supported methods are GET, POST, PUT, PATCH and DELETE.

Example: register the RH agent search function once in Bandy (use the actual reachable backend URL):

```json
{
  "name": "searchEmployees",
  "description": "Recherche des agents RH par nom, prénom, matricule, poste, service ou direction.",
  "whenToUse": "Utiliser lorsqu’un utilisateur cherche un ou plusieurs agents. Ne pas utiliser pour modifier un dossier.",
  "method": "GET",
  "url": "http://localhost:8080/api/employes/recherche",
  "enabled": true,
  "parameters": [
    {
      "name": "query",
      "type": "string",
      "description": "Nom, prénom, matricule, poste, service ou direction à rechercher.",
      "required": true,
      "location": "query"
    }
  ]
}
```

## Connecting the frontend and RH backend

Configure the frontend build with `VITE_BANDY_API_URL` pointing to Bandy's public base URL; the default is `http://localhost:8090`. The frontend calls Bandy's `/api/chat` directly and sends the user's RH bearer token. Configure Bandy's `JWT_SECRET` to the same signing secret as `assistant-rh-api` so Bandy can validate the existing user token. Add the frontend origin to `BANDY_CORS_ALLOWED_ORIGINS` (comma-separated for multiple origins).

The RH backend owns only RH data and endpoints. It does not call Bandy, store a Bandy URL or registration key, or need a Gemini API key. Bandy calls the registered RH function endpoint and forwards the user's bearer token so the RH API can apply its existing authentication checks.

Gemini receives the active tool descriptions and argument schemas, selects tools from natural-language intent, and supplies arguments. Bandy validates those arguments, forwards the user's bearer token to the tool endpoint when present, executes the HTTP call, and sends the result back to Gemini to compose the answer.

Only register trusted URLs: tool definitions can make Bandy issue HTTP requests to their configured endpoints.
