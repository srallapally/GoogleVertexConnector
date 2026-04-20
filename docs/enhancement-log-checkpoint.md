# OPENICF Google Vertex AI Connector - Enhancement Log
# Checkpoint: 2026-04-19 — Live GCP testing complete; inventory job deployed and validated

## Enhancement Log

| ID | Enhancement | Status | Notes |
|----|-------------|--------|-------|
| OPENICF-4001 | Add `serviceAccount` object class | **DONE** | Steps 1-7 complete |
| OPENICF-4002 | Add IAM getIamPolicy for Vertex AI agents | **DONE** | SA IAM bindings + improved error handling |
| OPENICF-4003 | Org-wide scanning via Cloud Asset API | **DONE** | Implemented in 4001/4002 |
| OPENICF-4004 | Extract structured guardrails from safetySettings | **DONE** | Implemented |
| OPENICF-4005 | Multi-region support | **Merged into 4003** | |
| OPENICF-4006 | `agentIdentityBinding.agentId` misleading for SA bindings | **Parking Lot** | Low priority |
| OPENICF-4007 | GCS inventory reader; `identityBindingScanEnabled` semantic change | **DONE** | 2026-04-18 — 5 files |
| OPENICF-4008 | `agentIdentityBinding` schema alignment with Python job output | **DONE** | 2026-04-18 — 3 files |
| OPENICF-4009 | `serviceAccount` OC moves offline; Python job SA enrichment | **DONE** | 2026-04-18 — 4 files |
| OPENICF-4010 | New `agentToolCredential` OC; Python job collects tool credential metadata | **DONE** | 2026-04-17 — see section below |
| OPENICF-4011 | `toolAuthSummary` live attribute on agent OC | **DONE** | 2026-04-17 |
| OPENICF-4012 | Migrate `dialogflow.py` and `reasoning_engines.py` live collectors from `gcloud` CLI to direct REST | **DONE** | 2026-04-18 — 2 files |
| OPENICF-4013 | Migrate `iam.py` live collector from `gcloud` CLI to direct REST | **DONE** | 2026-04-18 — `iam.py` only |
| OPENICF-4014 | Add error logging on IAM collection failures; no silent empty on non-zero / HTTP error | **DONE** | 2026-04-18 — combined with 4013 |
| OPENICF-4015 | Fix `normalize_tool_credentials()` signature mismatch — drop caller-supplied agent context args; derive from webhook `name` field | **DONE** | 2026-04-18 — `tool_credentials.py` only |
| OPENICF-4016 | Replace `gcsInventoryBaseUrl` with 3 per-artifact pre-signed GCS URL properties (`gcsIdentityBindingsUrl`, `gcsServiceAccountsUrl`, `gcsToolCredentialsUrl`); fix BUG-6 bare array parsing | **DONE** | 2026-04-19 — 4 files |
| OPENICF-4017 | Implement `agentToolCredential` OC search/get in CrudService + connector routing | **DONE** | 2026-04-19 — 3 files |
| BUG-3 | Job `dialogflow.py` used global endpoint `https://dialogflow.googleapis.com/v3` — returns HTTP 400; must use regional `https://{location}-dialogflow.googleapis.com/v3` | **FIXED** | 2026-04-19 — `dialogflow.py` |
| BUG-4 | Job `webhooks.py` same global endpoint bug as BUG-3 | **FIXED** | 2026-04-19 — `webhooks.py`; location extracted from resource name path segment `[3]` |
| BUG-5 | Job `iam.py` CX getIamPolicy used global endpoint | **FIXED** | 2026-04-19 — `iam.py`; location derived from `agent.location` |
| RFE-1 | `agentApiFlavor=both` — discover CX agents AND reasoning engines via direct API calls without Cloud Asset API | **Backlog** | Cloud Asset API misses Flow+GenAppBuilder CX agents; single-instance dual-flavor needed |

---

## Architecture Decision: Live vs Offline Split

**Resolved 2026-04-17.** The connector and Python inventory job have distinct responsibilities:

### Connector (live, every reconciliation)
- Agents (Dialogflow CX + Vertex AI Agent Engine)
- Tools and webhooks (Dialogflow CX)
- Knowledge bases / data stores (Dialogflow CX)
- Guardrails (synthetic, parsed from agent safetySettings)
- Service accounts (moved offline in OPENICF-4007; read from GCS in OPENICF-4009)
- Tool auth summary (new, OPENICF-4011)

### Offline job → GCS → Connector reads (batch, scheduled)
- Identity bindings (who can access the agent, via IAM policy analysis)
- Group expansion (not done in v1)
- SA IAM bindings (who can impersonate each SA)
- Tool credentials (new OC, OPENICF-4010)
- SA full metadata enrichment (OPENICF-4009)

### Integration point
- Connector reads GCS artifacts for offline OCs via three fetch methods on `GoogleVertexAIClient`: `fetchGcsIdentityBindings()`, `fetchGcsServiceAccounts()`, `fetchGcsToolCredentials()` (OPENICF-4007)
- Auth: pre-signed GCS URL only (`gcsInventoryBaseUrl` config property). No auth header sent — pre-signed URL is self-authenticating.
- `identityBindingScanEnabled`: gates all GCS reads (identity bindings, service accounts, tool credentials). Live IAM calls removed in OPENICF-4007.
- If `identityBindingScanEnabled=false`: connector skips all GCS reads and returns empty for those OCs.
- On GCS read failure (HTTP non-2xx or IOException): throws `ConnectorException`. Reconciliation aborts.

---

## Cross-System Review Findings (2026-04-17)

### Critical Bugs Found

| # | Finding | File | Status |
|---|---------|------|--------|
| BUG-1 | Cache permanently broken: `cacheLoadedAt` never updated. Fixed: set `Instant.now()` after `toolsCache`/`dataStoreCache` populate in `listTools()`/`listDataStores()`. | `GoogleVertexAIClient.java` | **FIXED 2026-04-17** |
| BUG-2 | Workload identity path dropped `organizationId`+`useCloudAssetApi`. Fixed: new 5-arg workload identity constructor; `createClient()` now passes both. | `GoogleVertexAIConnection.java`, `GoogleVertexAIClient.java` | **FIXED 2026-04-17** |

### POM / OSGi Issues

| # | Finding | Status |
|---|---------|--------|
| POM-1 | Dead GCP client deps — already absent from pom.xml. | **RESOLVED — pre-existing** |
| POM-2 | bundle packaging + maven-bundle-plugin — already configured in pom.xml. | **RESOLVED — pre-existing** |
| POM-3 | Hand-written MANIFEST.MF — META-INF directory is empty; file never existed. | **RESOLVED — pre-existing** |

### Schema Divergence (Connector vs Python Job)

| Area | Divergence | Resolution |
|------|-----------|------------|
| Identity binding UID | Connector: `{resourceName}:{role}:{member}` (unbounded). Job: `ib-{sha1[:16]}`. | Align to job format in OPENICF-4008 |
| `agentId` field | Connector: full resource name. Job: short agent ID (last path segment). | Align in OPENICF-4008 |
| `principal` | Connector: JSON blob. Job: normalized email string. | Align in OPENICF-4008 |
| Binding provenance | Connector: absent. Job: `sourceTag`, `confidence`. | Add in OPENICF-4008 |
| Project-level IAM fallback | Connector: none. Job: yes, MEDIUM confidence. | Job handles; connector reads job output |
| Self-binding exclusion | Connector: none. Job: excludes runtime identity. | Job handles |
| Role filtering | Connector: all roles. Job: invoke/manage only. | Job handles |
| SA schema | Connector: 12 fields. Job: 5 fields currently. | Job enriched in OPENICF-4009 |

### Minor Issues

| # | Finding | Priority |
|---|---------|----------|
| DEAD-1 | `GoogleVertexAIConfiguration`: `folderId` and `regions` fields declared with no getter, setter, or `@ConfigurationProperty`. Dead code. | Low |
| DEAD-2 | `searchIdentityBindings` conflates SA discovery with SA IAM binding discovery via `discoverServiceAccounts` flag. | Resolved by OPENICF-4007 (both removed from live path) |
| COUPLING-1 | Python `_run_gcloud_json()` swallows `OSError` silently. If `gcloud` not installed, live collection returns empty with no log output. | **RESOLVED** — `gcloud` removed from all collectors: OPENICF-4012 (dialogflow.py, reasoning_engines.py), OPENICF-4013 (iam.py) |

---

## Python Job Review — 2026-04-18

Review of uploaded source files against design spec and checkpoint log.

### Resolved since prior snapshot

| # | Finding | Status |
|---|---------|--------|
| R-1 | `tool_credentials_fixture_path` added to `config.py` | **RESOLVED** |
| R-2 | `write_tool_credentials_json()` and `toolCredentialCount` added to `json_writer.py` | **RESOLVED** |
| R-3 | `inventory/normalize/tool_credentials.py` present with `NormalizedToolCredential` normalizer | **RESOLVED** |
| R-4 | `inventory/collectors/webhooks.py` present; uses direct REST (`urllib.request` + `google.auth`) | **RESOLVED** |

### Open Issues

| # | Finding | Severity | Assigned |
|---|---------|----------|----------|
| O-1 | `dialogflow.py` and `reasoning_engines.py` live collectors still use `gcloud` CLI via `subprocess`. Cloud Run with ADC has no `gcloud` available — silent empty output on every live run. | **RESOLVED** | OPENICF-4012 — 2026-04-18 |
| O-2 | `iam.py` live collector (`_collect_resource_policy`, project fallback) still uses `gcloud` CLI. Same failure mode as O-1. | **RESOLVED** | OPENICF-4013 — 2026-04-18 |
| O-3 | `iam.py` `_run_gcloud_json()` logs nothing on non-zero returncode. `stderr` is captured but discarded. IAM collection failure produces empty bindings with no diagnostic trace — indistinguishable from a clean zero-binding run. | **RESOLVED** | OPENICF-4014 — 2026-04-18 |
| O-4 | `normalize_tool_credentials()` signature requires four args (`webhooks`, `agent_resource_name`, `project_id`, `location`), but `main.py` calls it as `normalize_tool_credentials(raw_webhooks)` — missing three required positional args. This is a `TypeError` at runtime; the live and fixture paths both fail. Fix: drop `agent_resource_name`, `project_id`, `location` from the public signature and derive all three inside `_normalize_one` by parsing the webhook `name` field (`projects/{p}/locations/{l}/agents/{a}/webhooks/{w}`). | **RESOLVED** | OPENICF-4015 — 2026-04-18 |
| O-5 | `roles/iam.serviceAccountTokenCreator` absent from `ROLE_PERMISSION_MAP`. Principals who can impersonate the agent's runtime SA via token creator are excluded from identity bindings. Governance gap. | **Medium** | Backlog |
| O-6 | `requirements.txt` not uploaded — cannot confirm `google-auth[requests]` is explicit. | **RESOLVED** | Confirmed present: `google-auth[requests]>=2.0.0,<3.0.0` |

---

## OPENICF-4011: toolAuthSummary (DONE 2026-04-17)

### What was done
New multi-valued attribute `toolAuthSummary` on the agent OC. Each value is a JSON string:
```json
{"toolId": "projects/.../agents/.../webhooks/123", "toolType": "WEBHOOK", "authType": "SERVICE_ACCOUNT", "credentialRef": "sa@project.iam.gserviceaccount.com"}
```

Auth type derivation for Dialogflow CX webhooks:
- `genericWebService.serviceAccount` present → `SERVICE_ACCOUNT`, credentialRef = SA email
- `genericWebService.oauthConfig` present → `OAUTH`
- `genericWebService.requestHeaders` non-empty → `API_KEY`
- `serviceDirectory` webhook → `SERVICE_ACCOUNT`
- else → `NONE`

CX tools (non-webhook): `authType=NONE` (no auth config at tool level).
Vertex AI agents: `toolAuthSummary` absent (no tool sub-resources exposed).

### Files modified

| File | Change | Lines (before→after) |
|------|--------|----------------------|
| `GoogleVertexToolDescriptor.java` | Added `authType`, `credentialRef` fields; updated constructor | 55→72 |
| `GoogleVertexAIConstants.java` | Added `ATTR_TOOL_AUTH_SUMMARY` | 119→121 |
| `GoogleVertexAIClient.java` | Updated `parseWebhookNode()` to extract auth; `parseToolNode()` gets `NONE` | 1638→1660 |
| `GoogleVertexAIConnector.java` | Added `toolAuthSummary` multi-valued attribute to agent OC schema | 466→470 |
| `GoogleVertexAICrudService.java` | Build `toolAuthSummary` entries alongside `toolIds` in `toAgentConnectorObject()` | 676→695 |

---

## OPENICF-4010: Tool Credential Collection (IN PROGRESS — 2026-04-17)

### Goal
Collect tool credential metadata from Dialogflow CX webhooks in the offline Python job and emit
`tool-credentials.json` for downstream connector ingestion (OPENICF-4007). Add `toolKey` attribute
consistently across Python job output, connector `agentTool` OC schema, and `toolAuthSummary` entries.

### Decisions

| ID | Decision |
|----|----------|
| Q1 | Collection: direct REST (`dialogflow.googleapis.com/v3`) preferred; `gcloud` CLI as fallback only |
| Q2 | Fixture mode: standalone `tests/fixtures/tool_credentials_fixture.json` (consistent with existing pattern) |
| Q3 | Auth derivation: match connector exactly — `SERVICE_ACCOUNT / OAUTH / API_KEY / NONE` (richer metadata tracked as backlog item) |
| Q4 | `toolId`: full resource name (e.g. `projects/p/locations/l/agents/a/webhooks/w`) |
| Q5 | Exclude `authType=NONE` entries from `tool-credentials.json` (include-NONE tracked as backlog item) |
| Q6 | No config flag — collection runs automatically when `flavor` includes `dialogflowcx` |
| Q7 | `toolKey`: `toolId` with `/` replaced by `_` — added to avoid forward-slash issues in search indexing |
| Q8 | `toolKey` scope: present in `tool-credentials.json`, `agentTool` OC schema, and `toolAuthSummary` JSON entries on agent OC |
| Q9 | `credentialRef` for OAUTH: opaque marker `"oauth"`. For API_KEY: opaque marker `"api-key"`. Never the secret value. |

### `tool-credentials.json` schema

Array of objects. Only entries where `authType != NONE` are emitted.

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Deterministic `tc-{sha1[:16]}` of `toolId` |
| `toolId` | string | Full webhook resource name |
| `toolKey` | string | `toolId` with `/` replaced by `_` — safe for search indexing |
| `toolType` | string | Always `WEBHOOK` for credentialed tools in CX |
| `agentId` | string | Full agent resource name |
| `authType` | string | `SERVICE_ACCOUNT`, `OAUTH`, or `API_KEY` |
| `credentialRef` | string | SA email, `"oauth"`, or `"api-key"` |
| `projectId` | string | GCP project ID |
| `location` | string | GCP region |

### Manifest additions

- `toolCredentialCount` — count of records in `tool-credentials.json`
- Entry added to `artifacts`: `{"file": "tool-credentials.json", "count": N}`

### File plan (12 files, one at a time)

| # | File | Change | Status |
|---|------|--------|--------|
| 1 | `inventory/models.py` | Add `NormalizedToolCredential` dataclass | **DONE** |
| 2 | `inventory/normalize/tool_credentials.py` | Auth derivation + `toolKey` derivation + ID hashing | **DONE** |
| 3 | `inventory/collectors/webhooks.py` | REST collector (live via google-auth) + fixture path | **DONE** |
| 4 | `tests/fixtures/tool_credentials_fixture.json` | Fixture data covering all 3 auth types + NONE exclusion case | **DONE** |
| 5 | `inventory/writers/json_writer.py` | Add `write_tool_credentials_json()`; add `tool_credential_count` to manifest | **DONE** |
| 6 | `inventory/config.py` | Add `tool_credentials_fixture_path` field + parsing + optional validation | **DONE** |
| 7 | `main.py` | Wire webhook collection + normalization + writing + manifest update | **DONE** |
| 8 | `tests/test_tool_credentials.py` | 23 unit tests — all passing | **DONE** |
| 9 | `GoogleVertexAIConstants.java` | Add `ATTR_TOOL_KEY` | **DONE** |
| 10 | `GoogleVertexAIConnector.java` | Add `toolKey` to `agentTool` OC schema | **DONE** |
| 11 | `GoogleVertexToolDescriptor.java` | Add `toolKey` field + constructor arg + getter | **DONE** |
| 12 | `GoogleVertexAIClient.java` | Add `toolKey` to both `parseToolNode()` + `parseWebhookNode()` constructor calls; fix `requestHeaders` isArray→isObject bug | **DONE** |
| 13 | `GoogleVertexAICrudService.java` | Add `toolKey` to `toolAuthSummary` JSON entries + `toToolConnectorObject()` | **DONE** |

### Backlog items (not in scope for this issue)

- **TOOL-CRED-B1:** Richer auth metadata — OAuth scopes, header key names (not values) for API_KEY
- **TOOL-CRED-B2:** Include `authType=NONE` entries in `tool-credentials.json`

### Implementation notes (decisions made during coding)

| Note | Detail |
|------|--------|
| Collector renamed | `collectors/tool_credentials.py` → `collectors/webhooks.py` to avoid identical filename collision with `normalize/tool_credentials.py` |
| Normalizer input shape | Takes flat list of raw Dialogflow CX webhook API dicts; derives `agentId`, `projectId`, `location` from `name` field — no per-agent args needed |
| `config.py` added to plan | `tool_credentials_fixture_path` added as optional field (file 6 split into config.py + main.py) — total now 13 files |
| Token acquisition | `google.auth.default(scopes=[cloud-platform])` with `google.auth.transport.requests.Request` for refresh; credentials reused across all agents per `collect_webhooks_live()` call |
| `requirements.txt` | Needs `google-auth[requests]` added explicitly (currently transitive via `google-cloud-storage`); flagged for file 8 |

### Open issues

- `requirements.txt`: add `google-auth[requests]` as explicit dependency (currently transitive only) — **FIXED**
- **BUG fixed during 4010:** `parseWebhookNode()` checked `requestHeaders` with `isArray()` — Dialogflow CX REST API returns it as an object/map, not an array. Fixed to `isObject()` in `GoogleVertexAIClient.java`. Python normalizer was already correct.

---

## Next Steps (ordered by dependency)

### Immediate: Fix critical bugs (independent of other work)

~~**BUG-1: Fix cache**~~ DONE 2026-04-17


~~**BUG-2: Fix workload identity org config loss**~~ DONE 2026-04-17


~~**POM-1/2/3: Fix bundle packaging**~~ RESOLVED — already clean (verified 2026-04-17)

### OPENICF-4015: Fix normalize_tool_credentials() signature mismatch
- `main.py` calls `normalize_tool_credentials(raw_webhooks)` with one arg
- Current signature requires four: `webhooks, agent_resource_name, project_id, location`
- Runtime `TypeError` — all tool credential output is broken in both fixture and live mode
- Fix: remove `agent_resource_name`, `project_id`, `location` parameters
- Derive all three inside `_normalize_one` by splitting `webhook["name"]`:
  - Format: `projects/{project}/locations/{location}/agents/{agent}/webhooks/{webhook}`
  - `project_id` = segment index 1, `location` = segment index 3, agent resource name = first 6 segments joined
- Update docstring and any tests that pass the old signature

### OPENICF-4012: Migrate dialogflow.py and reasoning_engines.py to direct REST
- Replace `_run_gcloud_json()` + `subprocess` with `urllib.request` + `google.auth.default()` pattern from `webhooks.py`
- Dialogflow CX agents: `GET https://dialogflow.googleapis.com/v3/projects/{project}/locations/{location}/agents`
- Vertex AI reasoning engines: `GET https://{location}-aiplatform.googleapis.com/v1/projects/{project}/locations/{location}/reasoningEngines`
- Handle pagination (`nextPageToken`) for both endpoints
- Remove `_run_gcloud_json` from both files once replaced

### OPENICF-4013 + OPENICF-4014: Migrate iam.py to direct REST + error logging

**DONE 2026-04-18. Combined into single delivery — `iam.py` only.**

#### What changed

| Item | Detail |
|------|--------|
| `subprocess` / `gcloud` | Removed entirely. `_run_gcloud_json` deleted. |
| Auth | Single `google.auth.default(scopes=[...])` call at top of `collect_iam_policies_live`; credential object passed through and refreshed per call (matches `dialogflow.py` pattern) |
| Resource-level IAM | `POST https://dialogflow.googleapis.com/v3/{resourceName}:getIamPolicy` (Dialogflow CX) or `POST https://{location}-aiplatform.googleapis.com/v1/{resourceName}:getIamPolicy` (Vertex AI) |
| Project fallback | `POST https://cloudresourcemanager.googleapis.com/v1/projects/{projectId}:getIamPolicy`; deduped — only fetched once per project |
| Non-2xx on resource call | Log WARNING (HTTP status + first 500 chars of response body), return `None`, **no fallback** (governance decision: 403 = permission misconfiguration, should not be masked by project fallback) |
| Empty bindings on 2xx | Log WARNING with `resourceName`, fall through to project fallback |
| Project fallback triggered | Log INFO with `projectId` before fetch |
| Project fallback non-2xx | Log WARNING with status + response body, skip |
| Network errors | `urllib.error.URLError` caught, log WARNING with reason |
| Logger | `logging.getLogger(__name__)` — first collector to have explicit logging |

### OPENICF-4007: GCS inventory reader + remove live IAM/SA from connector

**DONE 2026-04-18.**

#### Files modified

| File | Change | Lines (before→after) |
|------|--------|----------------------|
| `GoogleVertexAIConfiguration.java` | Added `gcsInventoryBaseUrl` + `@ConfigurationProperty` (order 9); removed `discoverServiceAccounts`, `includeServiceAccountKeys`, dead `folderId`/`regions` fields (DEAD-1); updated `validate()` | 272→238 |
| `GoogleVertexAIClient.java` | Added `fetchGcsIdentityBindings()`, `fetchGcsServiceAccounts()`, `fetchGcsToolCredentials()`, private `fetchGcsArtifact()`; removed all live IAM getIamPolicy methods, live SA list/get/key methods, SA JSON parsers | 1675→1274 |
| `GoogleVertexAICrudService.java` | Replaced live SA/IB paths with GCS-backed reads; new `gcsText()` helper; removed `isAttributeRequested()`, `countKeysFromJson()` (orphaned) | 697→688 |
| `GoogleVertexAIConnector.java` | No changes — schema and routing unchanged | 473→473 |
| `Messages.properties` | Updated `connector.help`, `identityBindingScanEnabled.help`; removed `discoverServiceAccounts`/`includeServiceAccountKeys` entries; added `gcsInventoryBaseUrl.display/help` | — |

#### Key implementation notes

| Note | Detail |
|------|--------|
| GCS auth | Pre-signed URL only — no `Authorization` header sent. Self-authenticating. Sending Bearer alongside pre-signed URL returns 400 InvalidAuthenticationInfo (same pattern as Copilot connector). |
| Failure behavior | `fetchGcsArtifact()` throws `ConnectorException` on HTTP non-2xx or `IOException`. No silent empty fallback. |
| `identityBindingScanEnabled` semantics | Now gates GCS reads for identity bindings, service accounts, and tool credentials. Previously gated live IAM calls (which are now removed). |
| String literals in `toIdentityBindingConnectorObject` | Resolved in OPENICF-4008 — all four promoted to constants. |
| `GoogleVertexAIConnector.java` | Routing to `searchServiceAccounts`/`getServiceAccount` on crud service unchanged — connector has no knowledge of how those are implemented. |

### OPENICF-4008: agentIdentityBinding schema alignment

**DONE 2026-04-18. 3 files.**

#### Files modified

| File | Change |
|------|--------|
| `GoogleVertexAIConstants.java` | Added 5 constants: `ATTR_SCOPE_RESOURCE_NAME`, `ATTR_SOURCE_TAG`, `ATTR_CONFIDENCE`, `ATTR_FLAVOR`, `ATTR_EXPANDED` |
| `GoogleVertexAICrudService.java` | `toIdentityBindingConnectorObject` rewritten: added `agentVersion`, `iamMember`, `iamRole`, `expanded`; fixed scope field (`scopeType` → `ATTR_SCOPE`, `scope` dropped); replaced 4 string literal attribute names with constants |
| `GoogleVertexAIConnector.java` | Added 6 attributes to `agentIdentityBinding` schema block: `agentVersion`, `scopeResourceName`, `sourceTag`, `confidence`, `flavor` (String), `expanded` (Boolean) |

#### Key decisions
- `scope` field dropped from connector output — `scopeType` (`AGENT_RESOURCE`/`PROJECT`) surfaced under `ATTR_SCOPE` instead
- `expanded` is Boolean, not String
- UID (`ib-{sha1[:16]}`), `agentId` (short ID), and `principal` (normalized email) were already correct from OPENICF-4007 GCS read path

### OPENICF-4009: serviceAccount OC — Python job SA enrichment

**DONE 2026-04-18. 4 files.**

#### Files modified

| File | Change |
|------|--------|
| `inventory/models.py` | `NormalizedServiceAccount` — added 8 optional fields with safe defaults: `name`, `displayName`, `description`, `uniqueId`, `oauth2ClientId`, `disabled=False`, `createTime`, `keysJson`, `keyCount=0` |
| `inventory/collectors/service_accounts.py` | New file — `collect_service_accounts_live()` calls `GET .../serviceAccounts/{email}` + `GET .../keys` per SA; key objects filtered to 5 metadata fields, serialized as `keysJson`; SA GET failure → WARNING + skip; keys GET failure → WARNING + empty key list |
| `inventory/normalize/service_accounts.py` | `normalize_service_accounts()` gains optional `enrichment: dict[str, dict] | None` param; applies enrichment after dedup pass; fixture mode passes no enrichment, behaviour unchanged |
| `main.py` | Live path: normalize once to get SA list → collect enrichment → re-normalize with enrichment; fixture path untouched |

#### Key decisions
- `json_writer.py` unchanged — `write_service_accounts_json` uses `to_dict()` which picks up new fields via `dataclasses.asdict()` automatically
- Keys: all key types included; per-key fields: `name`, `keyType`, `validAfterTime`, `validBeforeTime`, `keyAlgorithm`
- SA not found via API → WARNING + continue with sparse record (email + linkedAgentIds only)

---

## GCP Permission Requirements

### Required APIs
```bash
gcloud services enable dialogflow.googleapis.com --project=$PROJECT_ID
gcloud services enable aiplatform.googleapis.com --project=$PROJECT_ID
gcloud services enable iam.googleapis.com --project=$PROJECT_ID
gcloud services enable cloudasset.googleapis.com --project=$PROJECT_ID
```

### Custom Role: `aiConnectorReadOnly`
```bash
gcloud iam roles create aiConnectorReadOnly \
  --organization=$ORG_ID \
  --title="AI Connector Read-Only" \
  --permissions="\
dialogflow.agents.list,\
dialogflow.agents.get,\
dialogflow.agents.getIamPolicy,\
dialogflow.tools.list,\
dialogflow.webhooks.list,\
aiplatform.reasoningEngines.list,\
aiplatform.reasoningEngines.get,\
aiplatform.reasoningEngines.getIamPolicy,\
iam.serviceAccounts.list,\
iam.serviceAccounts.get,\
iam.serviceAccounts.getIamPolicy,\
iam.serviceAccountKeys.list,\
cloudasset.assets.searchAllResources"
```

### Verified Test Environment
- **Organization ID:** 321497704104
- **Project ID:** gen-lang-client-0559379892
- **Service Account:** 273041378232-compute@developer.gserviceaccount.com
- **Custom Role:** organizations/321497704104/roles/aiConnectorReadOnly

---

## Questions / Decisions Log

| ID | Question | Decision |
|----|----------|----------|
| Q1 | Scope: project only or org-wide? | Org-wide via Cloud Asset API |
| Q2 | SA → agent reverse mapping? | Yes, lazy-load |
| Q3 | Discover SA keys? | Yes, full metadata |
| Q4–Q16 | Implementation confirmations | Completed |
| Q17 | Constructor pattern for organizationId? | Backward-compatible overloads |
| Q18 | Lazy-load keys/linkedAgentIds? | Check `OperationOptions.getAttributesToGet()` |
| Q19 | SA IAM bindings OC? | Reuse `agentIdentityBinding` |
| Q20 | SA IAM binding lazy-load? | No, always fetch when enabled |
| Q21 | 5xx from getIamPolicy? | Log ERROR, return empty |
| Q22 | Agent discovery offline? | No — stays live |
| Q23 | Integration point connector↔job? | Connector reads GCS artifacts for offline OCs |
| Q24 | identityBindingScanEnabled semantics? | Controls GCS reads only; live IAM removed |
| Q25 | Tool credential detail level? | New OC, full metadata, never secret value |
| Q26 | toolAuthSummary structure? | Multi-valued JSON strings on agent OC |
| Q27 | Pagination for agent list endpoints? | Yes — `nextPageToken` handled in `_list_agents` (dialogflow.py) and `_list_engines` (reasoning_engines.py) |
| Q28 | Credential sharing across project/location iterations? | One `google.auth.default()` call per `collect_*_live()` invocation; credentials object passed through and refreshed per-page as needed |

---

## Live GCP Testing Findings — 2026-04-19

Confirmed via direct REST API calls against project `gen-lang-client-0559379892`.

### Dialogflow CX API — "Conversational Agents" Console Architecture

| Finding | Impact |
|---------|--------|
| Agent GET response does NOT include `generativeSettings`, `generativeSafetySettings`, or any safety fields | `agentGuardrail` OC returns 0 records; `foundationModel` and `safetySettings` attributes null for all current console agents |
| `GET .../agents/{id}/dataStores` returns empty body | `agentKnowledgeBase` OC returns 0 records; data stores now surface via Data store-type Tools |
| `getIamPolicy` on CX agents returns HTTP 404 | No `DIRECT_RESOURCE_BINDING` records for CX agents; project-level fallback NOT triggered on 404 (only on 2xx+empty) |
| `setIamPolicy` on CX agents returns HTTP 404 | IAM bindings can only be set at project level for current console CX agents |
| All OpenAPI tools return `toolType: "CUSTOMIZED_TOOL"` | UI label "OpenAPI" does not match API field value |
| Every CX agent has auto-created `code-interpreter` tool with `toolType: "BUILTIN_TOOL"` | Adds 1 tool per CX agent to connector output |
| Data store tools: `toolType: "CUSTOMIZED_TOOL"` + `dataStoreSpec` field present | Connector correctly infers `DATA_STORE_TOOL` from `dataStoreSpec` presence |
| Flow-based agents (`startFlow` + `genAppBuilderSettings`) NOT indexed by Cloud Asset API | `useCloudAssetApi=true` silently misses these agents; use `useCloudAssetApi=false` for single-project |
| Playbook-based agents (`startPlaybook`) ARE indexed by Cloud Asset API | Only one agent type works with org-wide Cloud Asset discovery |
| `gcloud alpha dialogflow agents` subcommand does not exist | All Dialogflow CX operations require REST API directly |
| `gcloud alpha ai reasoning-engines` subcommand does not exist | All Vertex AI reasoning engine operations require REST API directly |

### Vertex AI Agent Engine API

| Finding | Impact |
|---------|--------|
| `deploymentSpec.serviceAccount` is not a valid v1 API field — HTTP 400 if included | `serviceAccount` connector attribute always null for reasoning engine agents |
| API returns `spec.effectiveIdentity` (GCP-managed SA, shared across all engines in project) | Not currently read by connector; future enhancement candidate |
| `spec.agentFramework` returned correctly (`langgraph`, `google-adk`) | `agentFramework` attribute populates correctly |
| `getIamPolicy` and `setIamPolicy` work on reasoning engines | `DIRECT_RESOURCE_BINDING` / `confidence=HIGH` records produced for Vertex AI agents |
| Resource name uses project number not project ID (`projects/273041378232/...`) | Use project number in IAM REST calls |

### Inventory Job

| Finding | Impact |
|---------|--------|
| Job `dialogflow.py` used global endpoint (HTTP 400) | Fixed BUG-3 — regional endpoint required |
| Job `webhooks.py` same bug | Fixed BUG-4 |
| Job `iam.py` CX getIamPolicy same bug | Fixed BUG-5 |
| `tool-credentials.json` authType for request-header webhooks: `API_KEY` (not `HEADER`) | Tutorial §4.7 updated; `credentialRef` is generic marker `"api-key"` not header key name |
| `service-accounts.json` empty — `runtimeIdentity` null for all agents | Expected; `deploymentSpec` not in v1 API |
| `identity-bindings.json`: 6 records, Vertex AI only | CX agents produce no bindings (404 on getIamPolicy) |
| GCS bucket: `gen-lang-client-0559379892-vertex-inventory` in `us-central1` | Live; job SA: `vertex-inventory-job@gen-lang-client-0559379892.iam.gserviceaccount.com` |

### Connector Config (current working state)

```json
{
  "agentApiFlavor": "dialogflowcx",
  "useCloudAssetApi": false,
  "identityBindingScanEnabled": false,
  "projectId": "gen-lang-client-0559379892",
  "location": "us-central1",
  "organizationId": "321497704104"
}
```

`identityBindingScanEnabled=false` until per-artifact GCS URLs configured (Issue 5 closed 2026-04-19 — OPENICF-4016).

### Open Issues (post-testing)

| # | Issue | Priority |
|---|-------|----------|
| Issue 4 | Blank agent attributes in IDM — **CLOSED 2026-04-19**: `description`/`createdAt`/`updatedAt` absent from GCP API response (connector correctly omits via `addIfPresent`); `defaultLanguageCode` IS sent by connector — blank in IDM due to missing IDM object type property mapping (fix in IDM config, not connector) | Closed |
| Issue 5 | Connector needs per-artifact pre-signed GCS URLs — **CLOSED 2026-04-19**: OPENICF-4016 replaced `gcsInventoryBaseUrl` with 3 per-artifact URL properties; BUG-6 fixed bare array parsing; signed URLs generated and validated; identity bindings and service accounts OCs confirmed populated | Closed |
| BUG-6 | `toIdentityBindingConnectorObject` and `searchServiceAccounts`/`getServiceAccount` called `root.get("identityBindings")`/`root.get("serviceAccounts")` — GCS artifacts are bare JSON arrays not wrapped objects; returns null on every read. Fixed: use `root` directly. | **FIXED 2026-04-19** — `GoogleVertexAICrudService.java` |
| RFE-1 | `agentApiFlavor=both` — dual-flavor discovery without Cloud Asset API | Backlog |
| RFE-2 | Add `startPlaybook` attribute to agent OC schema and `toAgentConnectorObject` mapping — GCP API returns `startPlaybook` for Playbook-based CX agents but connector only maps `startFlow`; 3 of 4 live agents use `startPlaybook` | Backlog |
| OPENICF-4017 | `agentToolCredential` OC search/get implemented — `searchToolCredentials`/`getToolCredential` in CrudService; `OC_TOOL_CREDENTIAL` branch in `executeQuery`; `toToolCredentialConnectorObject` mapper; `fetchGcsToolCredentials` wired | **CLOSED 2026-04-19** |
| O-5 | `roles/iam.serviceAccountTokenCreator` absent from `ROLE_PERMISSION_MAP` | Backlog |
