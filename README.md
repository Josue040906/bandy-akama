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
| `GEMINI_MODEL` | Optional model; defaults to `gemini-3.5-flash-lite` |
| `BANDY_GEMINI_SERVICE_TIER` | Gemini inference tier: `standard` (default, no Priority surcharge) or opt-in `priority` |
| `BANDY_GEMINI_THINKING_LEVEL` | Gemini thinking level: `low` (default), `medium`, or `high` |
| `BANDY_GEMINI_MAX_OUTPUT_TOKENS` | Maximum generated output tokens; defaults to `1024` |
| `BANDY_GEMINI_TIMEOUT_MILLIS` | Maximum duration of one Gemini HTTP attempt; defaults to `15000` |
| `BANDY_GEMINI_RETRY_ATTEMPTS` | Gemini HTTP attempts including the first call; defaults to `2` |
| `BANDY_MAX_TOOL_ROUNDS` | Maximum sequential tool rounds per user turn; defaults to `3` (search, reference lookup, then a prepared task) |
| `BANDY_TURN_BUDGET_MILLIS` | Overall processing budget for one conversation turn; defaults to `30000` |
| `BANDY_APPLICATION_ID` | Server-side consumer identifier for conversation isolation; defaults to `assistant-rh` |
| `BANDY_ALLOWED_APPLICATION_IDS` | Comma-separated JWT `aud` allowlist for multi-application deployments |
| `BANDY_REGISTRATION_KEY` | Required secret for administration and calls from trusted backends |
| `JWT_SECRET` | Same HS256 signing secret used by `assistant-rh-api`; required to validate user sessions |
| `BANDY_CORS_ALLOWED_ORIGINS` | Comma-separated allowed browser origins; defaults to `http://localhost:5173` |
| `BANDY_CONTEXT_RECENT_MESSAGES` | Number of recent user/assistant messages sent to Gemini as separate role-based turns; must be `8`-`10` and defaults to `10` |
| `BANDY_CONTEXT_MAX_SUMMARY_CHARACTERS` | Maximum extractive summary size; defaults to `4000` |
| `BANDY_CONTEXT_MAX_CHARACTERS` | Maximum assembled historical context; defaults to `18000` |
| `BANDY_TOOL_CONNECT_TIMEOUT_SECONDS` | Registered-tool connection timeout; defaults to `5` |
| `BANDY_TOOL_READ_TIMEOUT_SECONDS` | Registered-tool response timeout; defaults to `15` |

Keep `.env` local; it is ignored by Git. `GEMINI_API_KEY` belongs in this
Bandy project, not in `assistant-rh-api`. The RH backend and Bandy must use the
same `JWT_SECRET` to validate existing user sessions. This secret stays in
server-side configuration: the browser sends the signed-in user's bearer
token, never the JWT signing secret itself.

Interactive generation defaults to Gemini's `low` thinking level to reduce
reasoning overhead for routine chat and tool selection. Set
`BANDY_GEMINI_THINKING_LEVEL=medium` or `high` if evaluation shows that a
request needs deeper reasoning. `BANDY_GEMINI_SERVICE_TIER=priority` is an
explicit opt-in for the paid Priority inference tier; it can cost substantially
more than Standard (Google currently documents a 75-100% premium). Keep
`standard` unless the Google Cloud billing owner has approved that charge.
Priority is supported only for compatible models and accounts; validate it
with a measured pilot before production use.
Gemini receives every enabled tool of the catalogue; the Java code names no RH
tool, route or rule. Results of tools flagged `sensitive` in the catalogue are
not persisted as raw conversation references; the active arguments are retained
for follow-up questions. Responses are streamed as SSE `token`, `done` or
`error` events.

To compare the configured Flash model with stable Gemini 3.5 Flash-Lite without
changing Bandy's production model, run `.\scripts\benchmark-gemini-models.ps1`.
The script prompts for the Gemini API key without echoing it when
`GEMINI_API_KEY` is not already set. It makes three repetitions by default for
two representative RH tool-selection requests, alternates model order, leaves
the optional service-tier field unset to use Gemini's default Standard tier,
and fixes both requests to low thinking, one candidate and one HTTP attempt.
It reports latency, errors, token counts and expected tool selection. Set
`GEMINI_API_KEY` in the current terminal or pass `-Repetitions 1` for a smaller
pilot. These calls consume Gemini API quota and may incur charges according to
the account's current plan. The script only asks the model to select a tool:
it does not call the RH API, access employee data or measure database latency.
Use the same authenticated frontend conversation flow separately before
considering further model changes. The benchmark does not modify the configured
default model or service tier.

Each synchronous Gemini SDK request is capped by
`BANDY_GEMINI_TIMEOUT_MILLIS`. The SDK retry policy is explicitly bounded by
`BANDY_GEMINI_RETRY_ATTEMPTS` (1-5 total attempts) and a short exponential
backoff capped at two seconds. The default is one retry after the initial
attempt. Only a transient Gemini overload (HTTP 503) is retried; a quota
refusal (429) is not, since retrying it would fail again and consume quota. `BANDY_MAX_TOOL_ROUNDS` bounds sequential tool execution to 1-5 rounds
per user turn; the default of three allows a search, a reference lookup and a
prepared task while preventing extended loops. The Gemini and registered-tool timeouts are bounded
by the remaining turn budget, which also bounds each retry attempt. PostgreSQL statements receive a JDBC query timeout bounded by
the remaining budget (rounded up to whole seconds, as required by JDBC); an
expired budget prevents the next statement from running. Registered backend
calls also have a 15-second default read timeout when no shorter budget remains.

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

Once all three applications are running, apply the assistant profile and the
tool catalogue from the Bandy PowerShell terminal with
`.\scripts\configure-assistant.ps1 -RemoveLegacyTools`. The texts live in
`scripts/config/assistant-profile.json` and `scripts/config/tools.json`
(UTF-8); the script checks that Bandy stored them unchanged and removes tools
absent from the catalogue file. After signing in to the frontend,
`.\scripts\test-chat.ps1` tests chat from a terminal; it
prompts for the RH access token without echoing it. The
`.\scripts\start-local-assistant.ps1` convenience script is optional and starts
both backend processes itself.

Run project checks separately: `.\mvnw.cmd test` in `assistant-rh-api`,
`mvn test` in Bandy, and `npm run lint` plus `npm run build` in `chatfronty`.

## Assistant profile

The initial profile is inserted into PostgreSQL only when the profile table is empty. Update the active database profile by editing `scripts/config/assistant-profile.json` and running `.\scripts\configure-assistant.ps1`; the script sends a `PUT /api/assistant/profile`. The profile is also manageable through `GET` or `PUT /api/assistant/profile` with the `X-Bandy-Registration-Key` header:

```json
{
  "name": "Bandy Akama",
  "role": "Assistant RH conversationnel",
  "instructions": "Le profil initial est créé par src/main/resources/db/migration/V1__assistant_and_tool_catalog.sql et le profil complet est appliqué par scripts/configure-assistant.ps1."
}
```

The initial profile is inserted by `src/main/resources/db/migration/V1__assistant_and_tool_catalog.sql` only when the profile table is empty. The update script applies the full Bandy persona and behavior to an existing profile. The profile is loaded from the database for each chat request, so changes apply
without restarting Bandy. A single joined query loads the profile and every
enabled tool definition with its parameters, avoiding separate round trips
without introducing stale cross-instance caches. The system instruction is the
profile followed by generic engine rules (follow-ups, refusals, honesty); it
contains no application-specific tool or field.

## Tool catalogue

Manage tools using `GET /api/tools`, `POST /api/tools`, `PUT /api/tools/{name}` and `DELETE /api/tools/{name}`. All require the registration key. A tool has a stable name, a description, guidance for when Gemini should use it, an HTTP method and URL, an enabled flag, and its argument schema. Definitions and parameters are persisted in PostgreSQL. A disabled tool remains manageable but is not exposed to Gemini or callable.

Supported argument types are `string`, `integer`, `number` and `boolean`; locations are `query`, `path` and `body`. GET arguments default to query parameters; other methods default to JSON body parameters. Path placeholders must be required parameters declared with `"location":"path"`. Supported methods are GET, POST, PUT, PATCH and DELETE.

A tool also carries `sensitive` (its raw result is never persisted) and an
`actionLevel` (see the action channel below). The current catalogue, kept in
`scripts/config/tools.json`:

| Tool | Level | Target |
| --- | --- | --- |
| `search_agents` | READ | `POST /api/recherche/agents/avancee` — boolean AST (`must`/`should`/`must_not`), fuzzy identity, `LIST` or exact `COUNT` |
| `get_agent_profile` | READ | `GET /api/profil/agent?query=` — detailed profile of an identified agent |
| `get_organization_info` | READ | `GET /api/assistant/organization` — directions, services, posts |
| `get_my_profile` | READ | `GET /api/profil/agent/mon-profil` — the signed-in user's profile |
| `get_my_activities` | READ | `GET /api/activites` — the signed-in user's actions |
| `list_document_types` | READ | `GET /api/documents/types` — active document types |
| `open_agent_record` | NAVIGATE | `/agents/{agentId}` |
| `open_page` | NAVIGATE | `/{page}` with `agents`, `documents` or `activites` |
| `prepare_new_document` | PREPARE | `/documents/nouveau` form |
| `prepare_agent_update` | PREPARE | `/agents/{agentId}` edit form |

Every rule about how to use a tool (search AST, fields, dates, follow-ups,
acronyms) lives in its `description`, `whenToUse` and parameter descriptions.
API refusals (4xx) are returned to Gemini as data (`{ok:false, status,
message}`) so it explains them; the API, not Bandy, decides rights.

`.\scripts\configure-assistant.ps1` registers or updates the tools of
`scripts/config/tools.json` and verifies that their texts, URL, flags and
parameter names were stored unchanged. Other tool definitions are reported and
left unchanged unless `-RemoveLegacyTools` is explicitly supplied. The engine
prompt holds only generic rules; every tool-specific rule (search AST, fields,
dates, follow-ups) lives in the tool's description in this catalogue. The
registration step requires a reachable Bandy instance and the registration key.

## Connecting the frontend and RH backend

Configure the frontend build with `VITE_BANDY_API_URL` pointing to Bandy's public base URL; the default is `http://localhost:8090`. The conversation UI creates conversations through `POST /api/conversations`, then sends messages as `{"message":"…"}` to `POST /api/conversations/{id}/messages`, including the user's RH bearer token. Configure Bandy's `JWT_SECRET` to the same signing secret as `assistant-rh-api` so Bandy can validate the existing user token. Add the frontend origin to `BANDY_CORS_ALLOWED_ORIGINS` (comma-separated for multiple origins).

The RH backend owns only RH data and endpoints. It does not call Bandy, store a Bandy URL or registration key, or need a Gemini API key. Bandy calls the registered RH profile endpoint and forwards the user's bearer token so the RH API can apply its existing authentication checks.

Results of sensitive tools are excluded from conversation history, memory and
idempotent response replay; only their arguments are retained for continuity.
For the immediate `search_agents` response, Bandy still sends the frontend a
strict card projection (internal navigation id, name, first name, post,
service, direction and similarity score). This projection is the last
tool-specific code in Bandy; it stays until the frontend uses the action
channel.
This is targeted retrieval of structured RH data, not vector-database or
document-search RAG. Run `.\scripts\configure-assistant.ps1` after deploying
both APIs to apply the profile and the tool definitions.

Tool definitions make Bandy issue HTTP requests carrying the user's bearer token, so their destinations are restricted by `BANDY_TOOL_ALLOWED_ORIGINS` (property `bandy.tools.allowed-origins`, comma-separated `scheme://host[:port]` origins, default `http://localhost:8080`). Registering a tool outside these origins returns 400, and the executor checks the final URL again before sending anything, so the token never reaches another origin. An empty list allows no destination.

## Action channel (contract for the frontend)

Each tool has an `actionLevel` in the catalogue: `READ` (Bandy calls the API and
Gemini explains the result), `NAVIGATE`, `PREPARE` or `CONFIRM`. A forbidden
action is simply not registered. For `NAVIGATE` and `PREPARE` the tool URL is a
frontend route (for example `/agents/{agentId}`): Bandy sends no request and
returns an action next to `toolResults`, whose shape is unchanged:

```json
"actions": [
  { "type": "NAVIGATE", "tool": "open_agent_record", "route": "/agents/101" },
  { "type": "PREPARE", "tool": "prepare_agent_update", "route": "/agents/101",
    "fields": { "telephone": "034 12 345 67" } }
]
```

- `NAVIGATE`: the frontend offers to open `route`; its route guard and the API
  still decide access.
- `PREPARE`: the frontend opens the form at `route` and prefills it with
  `fields`. `fields` holds only the values Bandy proposes, so the form can
  highlight them (for an edit, the ones that differ from the current value).
  Nothing is saved until the user validates; the frontend then calls the API
  with the user's own token.

Path arguments are strictly encoded, so an argument cannot add a segment or
leave the catalogue route. The same `actions` are returned by the streaming
`done` event and by idempotent replays.

`CONFIRM` tools target an API route (allowed origins apply) but nothing runs
during the turn. Bandy stores a pending request (15 minutes,
`bandy.confirmations.ttl-minutes`) and returns:

```json
{ "type": "CONFIRM", "tool": "submit_leave_request",
  "confirmationId": "7f1c…", "fields": { "motif": "Congé annuel" } }
```

- `POST /api/conversations/{id}/confirmations/{confirmationId}` with the user's
  bearer token is the « Confirmer » click. The request runs once (an atomic
  `PENDING → EXECUTING` transition); Bandy forwards the user's token and sends
  `Idempotency-Key: {confirmationId}`. The response is
  `{ "confirmationId", "status": "CONFIRMED" | "FAILED", "result" }`; a second
  click returns the same outcome without running again. A 4xx from the API gives
  `FAILED` with the API's message; an outage returns 502 and the request stays
  confirmable (a retry carries the same idempotency key).
- `DELETE` on the same URL cancels it. Another user gets 404; a cancelled,
  expired or already running request gets 409.
- The outcome is added to the conversation memory, so Gemini knows on the next
  turn what was done.

Contract for the RH API (its sub-project): write routes used by `CONFIRM` tools
must treat `Idempotency-Key` as a deduplication key, check rights from the
forwarded token, and answer refusals with a 4xx and a readable `message`.

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
| `POST /api/conversations/{id}/messages/stream` | Same, streamed as SSE |
| `POST /api/conversations/{id}/confirmations/{confirmationId}` | Confirm a pending task |
| `DELETE /api/conversations/{id}/confirmations/{confirmationId}` | Cancel a pending task |

The send routes accept an optional UUID `Idempotency-Key`; reuse the same key
for a network retry to replay a completed response without duplicating messages.
Bandy stores a SHA-256 fingerprint of the submitted message and returns `409`
if a caller reuses that key for different content.
The response contains `message`, `toolResults` (each with its `arguments`),
`conversationId` and `actions`.

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
the ten most recent user/assistant messages by default as separate role-based
turns, along with structured state and
relevant prior tool-result references when the request continues the same
intent. The recent-message limit is configurable with
`bandy.conversations.context.recent-messages`. A new independent intent does
not inherit the previous tool state or result references, while the full
transcript remains stored in PostgreSQL and is not resent. Summary updates are
incremental and deterministic (older text turns are compacted), and a missing
memory record can be rebuilt from saved messages.

The active tool state records the arguments of the last tool call. Gemini always
receives it with the recent turns and decides itself whether a message continues
the subject or opens a new one; the Java code never reads the user's text to
decide. Sensitive results such as profiles are not kept between turns, so Gemini
calls the tool again when a follow-up needs data that is no longer in the
conversation. Navigations and prepared tasks do not replace the active state.
Messages of failed turns stay stored but are not sent back to Gemini.
Tool calls use the registered HTTP contract, forward the caller's bearer token
and idempotency key, and use bounded connection/read timeouts.

### Performance telemetry

Bandy writes structured `bandy_perf` INFO events to its application log.
`event=stage` records conversation creation, request/memory setup, context
preparation, assistant preparation, persistence, and total conversation-service
time. `event=model_call` records the duration and outcome
of each synchronous Gemini SDK call; `event=model_call_failure` records the
exception class, bounded credential-redacted error message, and a limited
credential-redacted cause chain when one fails.
`event=tool_call` records each registered
tool execution without its arguments or result. `event=assistant_turn` reports
the total assistant duration and model-call, tool-call, and tool-round counts.
The `assistant_configuration_load` stage records the combined live profile and
tool-catalog query. This keeps administrative changes immediately visible and
avoids a process-local cache that could become stale across instances.
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

`chatfronty` is not modified in this repository. It should retain the returned
`conversationId`, load the list and paginated messages after sign-in, send new
messages to `POST /api/conversations/{id}/messages` with one UUID idempotency
key per submission (reused only for retries), and handle `actions`: offer the
`NAVIGATE` route, prefill and highlight `PREPARE` fields, and show
« Confirmer » / « Annuler » buttons for `CONFIRM` actions.

For `assistant-rh-api`: the organization route used by `get_organization_info`
must exist, empty search criteria must be refused with a 4xx and a readable
message, and any write route used by a `CONFIRM` tool must deduplicate on the
forwarded `Idempotency-Key`. Bandy never accesses RH tables directly.

## Conversation bench

`ConversationBenchTest` measures what Gemini understands (tool chosen,
arguments, follow-ups) on fictitious data, with stubbed tools and the real
model. It never runs during `mvn test`:

```powershell
$env:BANDY_BENCH_DB_URL = "jdbc:postgresql://localhost:5433/bandy_akama_v2"
$env:GEMINI_API_KEY = "…"
$env:BANDY_BENCH_SCENARIOS = "S19,S20"   # optional subset
mvn test -Dtest=ConversationBenchTest -Dbench=true
```

It uses the profile and catalogue stored in that database, paces calls
(`BANDY_BENCH_CALLS_PER_MINUTE`, default 12), stops at `BANDY_BENCH_MAX_CALLS`
(default 50) and writes a report to `target/bench`. Scenarios and fixtures are
in `src/test/resources/bench`; `BANDY_BENCH_DRY_RUN=true` only prints the
expected number of calls.
