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
| `BANDY_GEMINI_TIMEOUT_MILLIS` | Maximum duration of one Gemini HTTP attempt; defaults to `15000` |
| `BANDY_GEMINI_RETRY_ATTEMPTS` | Gemini HTTP attempts including the first call; defaults to `2` |
| `BANDY_MAX_TOOL_ROUNDS` | Maximum sequential tool rounds per user turn; defaults to `2` |
| `BANDY_TURN_BUDGET_MILLIS` | Overall processing budget for one conversation turn; defaults to `30000` |
| `BANDY_APPLICATION_ID` | Server-side consumer identifier for conversation isolation; defaults to `assistant-rh` |
| `BANDY_ALLOWED_APPLICATION_IDS` | Comma-separated JWT `aud` allowlist for multi-application deployments |
| `BANDY_REGISTRATION_KEY` | Required secret for administration and calls from trusted backends |
| `JWT_SECRET` | Same HS256 signing secret used by `assistant-rh-api`; required to validate user sessions |
| `BANDY_CORS_ALLOWED_ORIGINS` | Comma-separated allowed browser origins; defaults to `http://localhost:5173` |
| `BANDY_CONTEXT_RECENT_MESSAGES` | Number of recent text messages given to Gemini; defaults to `8` |
| `BANDY_CONTEXT_MAX_SUMMARY_CHARACTERS` | Maximum extractive summary size; defaults to `4000` |
| `BANDY_CONTEXT_MAX_CHARACTERS` | Maximum assembled historical context; defaults to `18000` |
| `BANDY_TOOL_CONNECT_TIMEOUT_SECONDS` | Registered-tool connection timeout; defaults to `5` |
| `BANDY_TOOL_READ_TIMEOUT_SECONDS` | Registered-tool response timeout; defaults to `15` |

Keep `.env` local; it is ignored by Git. `GEMINI_API_KEY` belongs in this
Bandy project, not in `assistant-rh-api`. The RH backend and Bandy must use the
same `JWT_SECRET` to validate existing user sessions. This secret stays in
server-side configuration: the browser sends the signed-in user's bearer
token, never the JWT signing secret itself.

Each synchronous Gemini SDK request is capped by
`BANDY_GEMINI_TIMEOUT_MILLIS`. The SDK retry policy is explicitly bounded by
`BANDY_GEMINI_RETRY_ATTEMPTS` (1-5 total attempts) and a short exponential
backoff capped at two seconds. The default is one retry after the initial
attempt. `BANDY_MAX_TOOL_ROUNDS` bounds sequential tool execution to 1-5 rounds
per user turn; the default of two preserves a short multi-step sequence while
preventing extended loops. The Gemini and registered-tool timeouts are bounded by the remaining turn
budget, and automatic Gemini retries are disabled for a budgeted turn. The
budget includes conversation preparation and persistence as well as model and
tool work; it is not a hard cancellation guarantee for database operations or
remote work that has already started. Registered backend calls also have a
15-second default read timeout when no shorter budget remains.

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
includes the complete field/operator guidance for Gemini. It also registers
`searchEmployeesApproximately` against
`GET /api/recherche/agents/approximative?query=...` for a person name with
uncertain spelling or typos. Prefer that single fuzzy lookup over repeated
`CONTAINS` searches with guessed spellings; use `searchEmployees` for
multi-criteria searches. If the approximate tool returns no reliable candidate,
ask for clarification instead of looping through name variants.

## Connecting the frontend and RH backend

Configure the frontend build with `VITE_BANDY_API_URL` pointing to Bandy's public base URL; the default is `http://localhost:8090`. The conversation UI creates conversations through `POST /api/conversations`, then sends messages as `{"message":"…"}` to `POST /api/conversations/{id}/messages`, including the user's RH bearer token. Configure Bandy's `JWT_SECRET` to the same signing secret as `assistant-rh-api` so Bandy can validate the existing user token. Add the frontend origin to `BANDY_CORS_ALLOWED_ORIGINS` (comma-separated for multiple origins).

The RH backend owns only RH data and endpoints. It does not call Bandy, store a Bandy URL or registration key, or need a Gemini API key. Bandy calls the registered RH function endpoint and forwards the user's bearer token so the RH API can apply its existing authentication checks.

Gemini receives the active tool descriptions and argument schemas, selects tools from natural-language intent, and supplies structured criteria. Bandy forwards the user's bearer token to the tool endpoint, executes the HTTP call, and sends the result back to Gemini to compose the answer. The RH backend validates every field and operator against an allowlist and constructs parameterized SQL; model-generated SQL is never accepted.

The RH tools also include `getMyProfile`, which reads only the authenticated
agent's professional profile and career path; the caller cannot choose a user
ID or matricule. Its response includes name, account email, matricule, current
assignment, employment type, and career steps, but excludes birth date, address,
phone number, national ID, and photo. The profile tool is marked sensitive: its
raw structured result is used for the immediate answer but is omitted from
Bandy's saved tool messages, result references, idempotency replay payload, and
public tool-result list. The assistant's user-facing answer remains in the
conversation history. Run `.\scripts\register-rh-tools.ps1` after deploying
both services to register or refresh the search and self-profile tools.

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

Gemini receives a bounded context assembled from the saved extractive summary,
recent user/assistant turns, structured state, and up to five prior tool-result
references when the request continues the same intent. A new independent intent
receives neither the previous filters nor its result references, while the
conversation transcript remains available as history. The context budget
prioritizes active state and usable result references over the older summary;
the full transcript remains in PostgreSQL and is not resent. Summary updates
are incremental and deterministic (older text turns are compacted), and a
missing memory record can be rebuilt from saved messages, including intent
resets.
For a continuing call to the same tool, Bandy merges `criteriaJson.filters` by
their `field`: omitted filters remain active, a supplied field replaces its
previous filter, and a filter with `remove:true` explicitly removes it before
the backend call. Arguments do not carry across different tool names. The backend classifies common French follow-ups and explicit new requests before
calling Gemini; ambiguous short messages trigger a clarification instead of
carrying filters into an uncertain request. A newly named organizational scope
that differs from the active scope starts a new intent rather than inheriting
old filters. This deterministic language rule set is intentionally conservative
and should be extended with localized test cases when adding supported
languages.
Tool calls continue to use the registered HTTP contract, forward the caller's
bearer token and idempotency key, and now use bounded connection/read timeouts.
The current `searchEmployees` endpoint is a read-only POST. Any future
state-changing RH tool must also enforce `Idempotency-Key` on the RH side.

### Performance telemetry

Bandy writes structured `bandy_perf` INFO events to its application log.
`event=stage` records conversation creation, request/memory setup, intent
resolution, context preparation, assistant preparation, persistence, and total
conversation-service time. `event=model_call` records the duration and outcome
of each synchronous Gemini SDK call; `event=model_call_failure` records the
exception class, bounded credential-redacted error message, and a limited
credential-redacted cause chain when one fails.
`event=tool_call` records each registered
tool execution without its arguments or result. `event=assistant_turn` reports
the total assistant duration and model-call, tool-call, and tool-round counts.
Separate stage events identify assistant profile and tool catalog loading.
`event=context_size` reports character counts for historical context, system
instructions, the current message, and registered tool descriptions; these are
payload-size indicators, not exact Gemini token counts. Durations are in
microseconds and share the technical idempotency/request UUID for correlation.

These events contain no prompt text, bearer token, tool arguments, tool results,
or user identity. Gemini failure diagnostics redact common API-key and bearer
token formats, and do not include full exception stack traces.
`gemini_max_attempts` reports the configured SDK limit; the
SDK does not expose the actual retries taken for an individual call, and they
are included in the measured SDK-call duration. Aggregate events from the
deployed application logs to calculate percentiles. Restart Bandy after
deployment to apply runtime settings and collect new events; historical latency
measurements are not available from this instrumentation.
For registered tool calls, Bandy forwards the same UUID as `X-Request-ID`.
The RH API logs `rh_perf event=api_request` with method, status, and total
request duration, plus `rh_perf event=db_query` for structured and approximate
agent searches. The JDBC duration includes result transfer and row mapping; it
is not PostgreSQL execution time alone. These events contain no route, SQL,
arguments, or response data. Aggregate the application logs to calculate
cross-service timings and percentiles.

Enabled tool definitions and parameters are loaded in two database queries per
assistant turn, rather than one parameter query per tool. The same loaded
definitions are used to validate and execute the model's tool calls for that
turn, avoiding a second database lookup for each call.
An identical tool name and argument set requested again within the same turn
reuses its first result instead of repeating the backend request; this is
limited to that turn and is reported as `tool_cache_hit`.

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
