# Bandy Akama

Bandy Akama is a Spring Boot conversational assistant powered by Google's Gemini API. It stores the assistant profile and registered HTTP tools in its own PostgreSQL database; backend projects no longer declare function descriptions in their source code.

## Configuration

Create a dedicated PostgreSQL database named `bandy_akama`, copy `.env.example`
to `.env`, and fill in the local values:

| Variable | Purpose |
| --- | --- |
| `BANDY_DB_URL` | PostgreSQL JDBC URL; defaults to `jdbc:postgresql://localhost:5433/bandy_akama` |
| `BANDY_DB_USERNAME` | Database user; defaults to `postgres` |
| `BANDY_DB_PASSWORD` | Database password |
| `GEMINI_API_KEY` | Required Google Gemini API key |
| `GEMINI_MODEL` | Optional model; defaults to `gemini-3.8-flash` |
| `BANDY_APPLICATION_ID` | Server-side consumer identifier for conversation isolation; defaults to `assistant-rh` |
| `BANDY_ALLOWED_APPLICATION_IDS` | Comma-separated JWT `aud` allowlist for multi-application deployments |
| `BANDY_REGISTRATION_KEY` | Required secret for administration and calls from trusted backends |
| `JWT_SECRET` | Same HS256 signing secret used by `assistant-rh-api`; required to validate user sessions |
| `BANDY_CORS_ALLOWED_ORIGINS` | Comma-separated allowed browser origins; defaults to `http://localhost:5173` |
| `BANDY_CONTEXT_RECENT_MESSAGES` | Number of recent text messages given to Gemini; defaults to `8` |
| `BANDY_CONTEXT_MAX_SUMMARY_CHARACTERS` | Maximum extractive summary size; defaults to `4000` |
| `BANDY_CONTEXT_MAX_CHARACTERS` | Maximum assembled historical context; defaults to `18000` |
| `BANDY_TOOL_CONNECT_TIMEOUT_SECONDS` | Registered-tool connection timeout; defaults to `5` |
| `BANDY_TOOL_READ_TIMEOUT_SECONDS` | Registered-tool response timeout; defaults to `30` |

Keep `.env` local; it is ignored by Git. `GEMINI_API_KEY` belongs in this
Bandy project, not in `assistant-rh-api`. The RH backend and Bandy must use the
same `JWT_SECRET` to validate existing user sessions. This secret stays in
server-side configuration: the browser sends the signed-in user's bearer
token, never the JWT signing secret itself.

The application creates its tables and initial assistant profile on startup.
It does not create the PostgreSQL database itself. Start the projects separately
in their own IDE run configurations or integrated terminals, in this order:

1. Start PostgreSQL with the `assistant_rh` and `bandy_akama` databases available
   on port `5433`.
2. Start `assistant-rh-api` on port `8080`. Set `JWT_SECRET` in its IDE run
   configuration to the same value as `JWT_SECRET` in Bandy's `.env`. From its
   integrated PowerShell terminal, run `.\mvnw.cmd spring-boot:run`.
3. Start Bandy on port `8090`. From `D:\Projets\bandy-akama`, run
   `.\scripts\start-bandy.ps1` to enter any missing secrets securely in the
   terminal and start it. Alternatively, configure `.env` and run
   `mvn spring-boot:run`.
4. Start `chatfronty` on port `5173` with `npm run dev`.

Once all three applications are running, register or update the RH structured
search tool from the Bandy PowerShell terminal with
`.\scripts\register-rh-tools.ps1`. After signing in to the frontend,
`.\scripts\test-chat.ps1` tests chat from a terminal; it
prompts for the RH access token without echoing it. The
`.\scripts\start-local-assistant.ps1` convenience script is optional and starts
both backend processes itself.

Run project checks separately: `.\mvnw.cmd test` in `assistant-rh-api`,
`mvn test` in Bandy, and `npm run lint` plus `npm run build` in `chatfronty`.

## Assistant profile

The initial profile is inserted into PostgreSQL only when the profile table is empty. Update the active database profile from PowerShell with `.\scripts\update-assistant-profile.ps1`; the script securely prompts for the registration key and sends a `PUT /api/assistant/profile`. The profile is also manageable through `GET` or `PUT /api/assistant/profile` with the `X-Bandy-Registration-Key` header:

```json
{
  "name": "Bandy Akama",
  "role": "Assistant RH conversationnel",
  "instructions": "Le profil initial est créé par src/main/resources/db/migration/V1__assistant_and_tool_catalog.sql et le profil complet peut être appliqué par scripts/update-assistant-profile.ps1."
}
```

The initial profile is inserted by `src/main/resources/db/migration/V1__assistant_and_tool_catalog.sql` only when the profile table is empty. The update script applies the full Bandy persona and behavior to an existing profile. The profile is loaded from the database for each chat request, so changes apply without restarting Bandy.

## Tool catalogue

Manage tools using `GET /api/tools`, `POST /api/tools`, `PUT /api/tools/{name}` and `DELETE /api/tools/{name}`. All require the registration key. A tool has a stable name, a description, guidance for when Gemini should use it, an HTTP method and URL, an enabled flag, and its argument schema. Definitions and parameters are persisted in PostgreSQL. A disabled tool remains manageable but is not exposed to Gemini or callable.

Supported argument types are `string`, `integer`, `number` and `boolean`; locations are `query`, `path` and `body`. GET arguments default to query parameters; other methods default to JSON body parameters. Path placeholders must be required parameters declared with `"location":"path"`. Supported methods are GET, POST, PUT, PATCH and DELETE.

Register or refresh the structured RH search function using the actual reachable
backend URL:

```json
{
  "name": "searchEmployees",
  "description": "Recherche multicritère d’agents RH à partir de critères structurés.",
  "whenToUse": "Interpréter la demande naturelle en critères autorisés. Demander une durée si « récemment » ou « depuis longtemps » est imprécis.",
  "method": "POST",
  "url": "http://localhost:8080/api/recherche/agents/criteres",
  "enabled": true,
  "parameters": [
    {
      "name": "criteriaJson",
      "type": "string",
      "description": "JSON sérialisé avec filters. Exemple : {\"filters\":[{\"field\":\"age\",\"operator\":\"LT\",\"value\":30}]}",
      "required": true,
      "location": "body"
    }
  ]
}
```

The `.\scripts\register-rh-tools.ps1` helper registers or updates this tool and
includes the complete field/operator guidance for Gemini.

## Connecting the frontend and RH backend

Configure the frontend build with `VITE_BANDY_API_URL` pointing to Bandy's public base URL; the default is `http://localhost:8090`. The frontend calls Bandy's `/api/chat` directly and sends the user's RH bearer token. Configure Bandy's `JWT_SECRET` to the same signing secret as `assistant-rh-api` so Bandy can validate the existing user token. Add the frontend origin to `BANDY_CORS_ALLOWED_ORIGINS` (comma-separated for multiple origins).

The RH backend owns only RH data and endpoints. It does not call Bandy, store a Bandy URL or registration key, or need a Gemini API key. Bandy calls the registered RH function endpoint and forwards the user's bearer token so the RH API can apply its existing authentication checks.

Gemini receives the active tool descriptions and argument schemas, selects tools from natural-language intent, and supplies structured criteria. Bandy forwards the user's bearer token to the tool endpoint, executes the HTTP call, and sends the result back to Gemini to compose the answer. The RH backend validates every field and operator against an allowlist and constructs parameterized SQL; model-generated SQL is never accepted.

Only register trusted URLs: tool definitions can make Bandy issue HTTP requests to their configured endpoints.

## Persistent conversations

Conversation history and memory live in Bandy's PostgreSQL database, not in
`assistant_rh`. Flyway applies ordered migrations in
`src/main/resources/db/migration/`; existing profile and tool tables are not
dropped or recreated. A database previously initialized by `schema.sql` is
baselined at version 1, then receives the conversation migrations and additive
compatibility columns. The conversation schema stores an application-scoped
owner, ordered messages, tool results, a compact memory and an idempotency
record. Structured memory and message metadata use PostgreSQL `JSONB`.
Conversations are archived rather than hard-deleted; no automatic retention
period is configured, so operators must define a retention policy for their
deployment.

All conversation endpoints require the existing `Authorization: Bearer <JWT>`
header. Ownership is read from the validated, numeric JWT `sub` claim. The
client cannot submit an owner ID:

| Method and path | Purpose |
| --- | --- |
| `POST /api/conversations` | Create an empty conversation; returns its ID |
| `GET /api/conversations?page=0&size=20` | List the caller's conversations |
| `GET /api/conversations/{id}` | Read conversation metadata |
| `GET /api/conversations/{id}/messages?page=0&size=50` | Read ordered messages |
| `PATCH /api/conversations/{id}` | Rename with `{"label":"..."}` |
| `POST /api/conversations/{id}/archive` | Archive the conversation |
| `POST /api/conversations/{id}/messages` | Send `{"message":"..."}` to a conversation |
| `POST /api/chat` | Backward-compatible chat; accepts optional `conversationId` |

`POST /api/chat` creates a conversation when the old client omits
`conversationId`, and returns that ID in the response. New clients should create
or select a conversation explicitly, then send messages to its messages route.
Both send routes accept an optional UUID `Idempotency-Key`; reuse the same key
for a network retry to replay a completed response without duplicating messages.
Bandy stores a SHA-256 fingerprint of the submitted message and returns `409`
if a caller reuses that key for different content.
The response shape remains `message` and `toolResults`, with additive
`conversationId`; each tool result also returns its `arguments`.

The application identifier is never accepted from a request header or body.
By default it is configured by the service (`BANDY_APPLICATION_ID`), which
isolates users in deployments dedicated to separate client applications. For a
shared Bandy deployment, configure `BANDY_ALLOWED_APPLICATION_IDS` and have the
trusted JWT issuer include the corresponding application identifier as the
signed JWT `aud` claim. Bandy then validates the audience against that allowlist
and uses it in the conversation ownership key; a missing audience keeps the
legacy configured application ID, and an unknown audience is rejected. The
current `assistant-rh-api` token contract does not add that application
audience, so shared multi-application use requires updating its token issuer
and deployments to keep the audience allowlist synchronized.

Gemini receives the saved extractive summary, the last configured number of
user/assistant messages, structured state from the most recent successful tool
call, and up to five prior tool-result references. The assembled context is
capped; the full transcript remains in PostgreSQL and is not resent. Summary
updates are incremental and deterministic (older text turns are compacted).
For a continuing call to the same tool, Bandy merges `criteriaJson.filters` by
their `field`: omitted filters remain active, a supplied field replaces its
previous filter, and a filter with `remove:true` explicitly removes it before
the backend call. Arguments do not carry across different tool names. The backend classifies common French follow-ups and explicit new requests before
calling Gemini; ambiguous short messages trigger a clarification instead of
carrying filters into an uncertain request. This deterministic language rule
set is intentionally conservative and should be extended with localized test
cases when adding supported languages.
Tool calls continue to use the registered HTTP contract, forward the caller's
bearer token and idempotency key, and now use bounded connection/read timeouts.
The current `searchEmployees` endpoint is a read-only POST. Any future
state-changing RH tool must also enforce `Idempotency-Key` on the RH side.

### Client integration changes

`chatfronty` is not modified in this repository. To enable resume after logout
or refresh, it should retain the returned `conversationId`, load the list and
paginated messages after sign-in, and send new messages to
`POST /api/conversations/{id}/messages`. Generate one UUID idempotency key per
user submission and reuse it only for retries. The legacy `/api/chat` call
continues to work but starts a new conversation for each request without an ID.

No changes are required in `assistant-rh-api` for the existing read-only
structured search tool. If future registered tools perform writes, the RH API
must implement idempotency for the forwarded key. The live conversation
controller continues to pass the user's token to the configured tool endpoint;
it does not access RH tables directly.
