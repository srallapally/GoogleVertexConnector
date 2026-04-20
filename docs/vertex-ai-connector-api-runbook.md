# Google Vertex AI Connector — API Runbook

**Version:** 1.2  
**Date:** 2026-04-19  
**Scope:** All GCP APIs invoked by the Google Vertex AI connector and the Vertex Tools Inventory Job

---

## Table of Contents

1. [Overview](#1-overview)
2. [Connector APIs (Live)](#2-connector-apis-live)
   - 2.1 [Dialogflow CX — List Agents](#21-dialogflow-cx--list-agents)
   - 2.2 [Dialogflow CX — Get Agent](#22-dialogflow-cx--get-agent)
   - 2.3 [Dialogflow CX — List Tools](#23-dialogflow-cx--list-tools)
   - 2.4 [Dialogflow CX — List Webhooks](#24-dialogflow-cx--list-webhooks)
   - 2.5 [Dialogflow CX — List Data Stores](#25-dialogflow-cx--list-data-stores)
   - 2.6 [Vertex AI Agent Engine — List Reasoning Engines](#26-vertex-ai-agent-engine--list-reasoning-engines)
   - 2.7 [Vertex AI Agent Engine — Get Reasoning Engine](#27-vertex-ai-agent-engine--get-reasoning-engine)
   - 2.8 [Cloud Asset API — Search All Resources (Agents)](#28-cloud-asset-api--search-all-resources-agents)
   - 2.9 [GCS — Fetch Inventory Artifacts](#29-gcs--fetch-inventory-artifacts)
3. [Offline Job APIs](#3-offline-job-apis)
   - 3.1 [Dialogflow CX — List Agents](#31-dialogflow-cx--list-agents)
   - 3.2 [Dialogflow CX — Get IAM Policy (Agent)](#32-dialogflow-cx--get-iam-policy-agent)
   - 3.3 [Vertex AI Agent Engine — List Reasoning Engines](#33-vertex-ai-agent-engine--list-reasoning-engines)
   - 3.4 [Vertex AI Agent Engine — Get IAM Policy (Reasoning Engine)](#34-vertex-ai-agent-engine--get-iam-policy-reasoning-engine)
   - 3.5 [Resource Manager — Get Project IAM Policy](#35-resource-manager--get-project-iam-policy)
   - 3.6 [GCS — Upload Artifacts](#36-gcs--upload-artifacts)
   - 3.7 [IAM API — Service Account Enrichment](#37-iam-api--service-account-enrichment)
   - 3.8 [Dialogflow CX — List Webhooks (Job)](#38-dialogflow-cx--list-webhooks-job)
4. [Permission Summary](#4-permission-summary)
   - 4.1 [Connector Service Account](#41-connector-service-account)
   - 4.2 [Inventory Job Service Account](#42-inventory-job-service-account)
5. [Authentication](#5-authentication)
6. [Error Handling Reference](#6-error-handling-reference)

---

## 1. Overview

Two service accounts are involved in this system. They have distinct permission sets and must not be conflated.

| Service Account | Used By | Purpose |
|---|---|---|
| Connector SA (e.g. `273041378232-compute@developer.gserviceaccount.com`) | OpenICF connector in PingOne IDM | Live agent/tool/KB/guardrail discovery; read GCS artifacts |
| Job SA (e.g. `vertex-inventory-job@PROJECT.iam.gserviceaccount.com`) | Vertex Tools Inventory Job (Cloud Run) | Offline IAM policy collection; write artifacts to GCS |

The connector authenticates using a service account key JSON (or workload identity when enabled). The inventory job authenticates using the Cloud Run Job execution identity (Application Default Credentials).

---

## 2. Connector APIs (Live)

These APIs are called by the connector during every IDM reconciliation. All calls use the connector service account's bearer token.

---

### 2.1 Dialogflow CX — List Agents

**Invoked by:** Connector — `listAgents()` / `listAgentsPaginated()`  
**When:** Every reconciliation; also during org-wide Cloud Asset fallback detail fetch  
**Flavor:** `dialogflowcx`

**Endpoint:**
```
GET https://{location}-dialogflow.googleapis.com/v3/projects/{projectId}/locations/{location}/agents
    ?pageSize=50
    &pageToken={token}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `dialogflow.agents.list` | `roles/dialogflow.reader` |

**Scope:** Project

**Response key:** `agents[]`  
**Pagination:** `nextPageToken` in response body; connector loops until token is absent.

**Error behavior:**
- HTTP 403 → `ConnectorException` (insufficient permission)
- HTTP 404 → `ConnectorException` (project or location not found)
- Non-2xx → `RuntimeException` propagated as `ConnectorException`

---

### 2.2 Dialogflow CX — Get Agent

**Invoked by:** Connector — `getAgent()`, `getAgentWithLocation()`  
**When:** Single-object GET by UID; also called per-agent during Cloud Asset org-wide discovery to fetch full details  
**Flavor:** `dialogflowcx`

**Endpoint:**
```
GET https://{location}-dialogflow.googleapis.com/v3/{agentResourceName}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `dialogflow.agents.get` | `roles/dialogflow.reader` |

**Scope:** Project

**Error behavior:**
- HTTP 404 → returns `null`; caller handles as `UnknownUidException`
- Non-2xx → `RuntimeException`

**Notes — confirmed via live GCP testing (2026-04-19):**
- Agents created in the current "Conversational Agents" console do **not** include `generativeSettings`, `generativeSafetySettings`, or safety-related fields in the GET response. The `foundationModel` and `safetySettings` connector attributes will be null for these agents.
- Agents may have either `startFlow` (Flow-based) or `startPlaybook` (Playbook-based) — both are valid CX agent types.
- Flow-based agents include `genAppBuilderSettings` referencing a Discovery Engine resource.
- The agent GET response for the current console contains: `name`, `displayName`, `defaultLanguageCode`, `timeZone`, `startFlow`/`startPlaybook`, `advancedSettings`, `genAppBuilderSettings` (Flow only), `satisfiesPzi`. No generative AI fields.

---

### 2.3 Dialogflow CX — List Tools

**Invoked by:** Connector — `fetchTools()` called from `listTools()`  
**When:** Per-agent during reconciliation; results cached for TTL (5 minutes)  
**Flavor:** `dialogflowcx`

**Endpoint:**
```
GET https://{location}-dialogflow.googleapis.com/v3/{agentResourceName}/tools
    ?pageSize=100
    &pageToken={token}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `dialogflow.tools.list` | `roles/dialogflow.reader` |

**Scope:** Project

**Response key:** `tools[]`  
**Pagination:** `nextPageToken`; connector loops until absent.

**Error behavior:**
- Non-2xx (including 404 when agent has no tools) → loop breaks; empty list returned. Not treated as fatal.

**Notes — confirmed via live GCP testing (2026-04-19):**
- All OpenAPI-type tools (created via "OpenAPI" UI option) return `toolType: "CUSTOMIZED_TOOL"` in the API response, regardless of the UI label. The `openApiSpec` field is present alongside `toolType`.
- Every CX agent automatically has a `code-interpreter` built-in tool with `toolType: "BUILTIN_TOOL"` and `name: "{agentResourceName}/tools/df-code-interpreter-tool"`. This appears in `agentTool` connector output for every CX agent.
- Data store tools return `toolType: "CUSTOMIZED_TOOL"` with a `dataStoreSpec.dataStoreConnections[]` field. The connector infers `DATA_STORE_TOOL` from the presence of `dataStoreSpec`.

---

### 2.4 Dialogflow CX — List Webhooks

**Invoked by:** Connector — `fetchWebhooks()` called from `listTools()`  
**When:** Per-agent during reconciliation; results cached alongside tools  
**Flavor:** `dialogflowcx`

**Endpoint:**
```
GET https://{location}-dialogflow.googleapis.com/v3/{agentResourceName}/webhooks
    ?pageSize=100
    &pageToken={token}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `dialogflow.webhooks.list` | `roles/dialogflow.reader` |

**Scope:** Project

**Response key:** `webhooks[]`  
**Pagination:** `nextPageToken`; connector loops until absent.

**Error behavior:**
- Non-2xx → loop breaks; empty list returned. Not treated as fatal.

**Notes:** Webhook auth fields (`genericWebService.serviceAccount`, `genericWebService.oauthConfig`, `genericWebService.requestHeaders`) are parsed from the response to populate `toolAuthSummary` on the agent OC.

---

### 2.5 Dialogflow CX — List Data Stores

**Invoked by:** Connector — `listDataStores()`  
**When:** Per-agent during reconciliation; results cached for TTL (5 minutes)  
**Flavor:** `dialogflowcx`

**Endpoint:**
```
GET https://{location}-dialogflow.googleapis.com/v3/{agentResourceName}/dataStores
    ?pageSize=100
    &pageToken={token}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `dialogflow.agents.get` | `roles/dialogflow.reader` |

**Scope:** Project

**Response key:** `dataStores[]`  
**Pagination:** `nextPageToken`; connector loops until absent.

**Error behavior:**
- Non-2xx → loop breaks; empty list returned. Not treated as fatal.

**Notes — confirmed via live GCP testing (2026-04-19):**
- Returns **empty for all agents created in the current "Conversational Agents" console**. Agent-level data store connections have been removed from this console version.
- Data stores are now linked through Data store-type Tools, discoverable via `GET .../tools` (§2.3). The `agentKnowledgeBase` object class returns zero records for current console agents.

---

### 2.6 Vertex AI Agent Engine — List Reasoning Engines

**Invoked by:** Connector — `listAgentsPaginated()`  
**When:** Every reconciliation when `agentApiFlavor=vertexai` or `agentApiFlavor=both`  
**Flavor:** `vertexai`, `both`

**Endpoint:**
```
GET https://{location}-aiplatform.googleapis.com/v1/projects/{projectId}/locations/{location}/reasoningEngines
    ?pageSize=50
    &pageToken={token}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `aiplatform.reasoningEngines.list` | `roles/aiplatform.viewer` |

**Scope:** Project

**Response key:** `reasoningEngines[]`  
**Pagination:** `nextPageToken`; connector loops until absent.

**Error behavior:**
- Non-2xx → `RuntimeException` propagated as `ConnectorException`

---

### 2.7 Vertex AI Agent Engine — Get Reasoning Engine

**Invoked by:** Connector — `getAgent()`, `getAgentWithLocation()`  
**When:** Single-object GET by UID; also called per-agent during Cloud Asset org-wide discovery  
**Flavor:** `vertexai`, `both`

**Endpoint:**
```
GET https://{location}-aiplatform.googleapis.com/v1/{reasoningEngineResourceName}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `aiplatform.reasoningEngines.get` | `roles/aiplatform.viewer` |

**Scope:** Project

**Error behavior:**
- HTTP 404 → returns `null`
- Non-2xx → `RuntimeException`

**Notes — confirmed via live GCP testing (2026-04-19):**
- Response `spec.agentFramework` populates the `agentFramework` attribute. ✅
- `deploymentSpec.serviceAccount` is **not a valid field in the v1 API** — including it in the request body returns HTTP 400. The `serviceAccount` connector attribute will always be null for reasoning engine agents.
- The API does return `spec.effectiveIdentity` — a GCP-managed service account auto-assigned at creation (e.g. `service-273041378232@gcp-sa-aiplatform-re.iam.gserviceaccount.com`). This is shared across all reasoning engines in a project, not per-engine. The connector currently does not read this field.

---

### 2.8 Cloud Asset API — Search All Resources (Agents)

**Invoked by:** Connector — `listAgentsViaCloudAsset()`  
**When:** When `useCloudAssetApi=true` and `organizationId` is set; replaces project-scoped agent listing  
**Flavor:** Both (discovers Dialogflow CX and Vertex AI agents in one call)

**Endpoint:**
```
GET https://cloudasset.googleapis.com/v1/organizations/{organizationId}:searchAllResources
    ?assetTypes=dialogflow.googleapis.com/Agent
    &assetTypes=aiplatform.googleapis.com/ReasoningEngine
    &pageSize=100
    &pageToken={token}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `cloudasset.assets.searchAllResources` | `roles/cloudasset.viewer` |

**Scope:** Organization

**Response key:** `results[]`  
**Pagination:** `nextPageToken`; connector loops until absent.

**Error behavior:**
- Non-2xx → `RuntimeException` (logged as ERROR); reconciliation aborts
- Each result asset triggers a follow-up `getAgent()` / `getAgentWithLocation()` call to fetch full details (see 2.2, 2.7)

**Notes:** Location is extracted from the resource name to route the detail fetch to the correct regional endpoint. This enables automatic multi-region support.

**Notes — confirmed via live GCP testing (2026-04-19):**
- The Cloud Asset API only indexes **Playbook-type** Dialogflow CX agents (`startPlaybook` field). Flow-based agents with `genAppBuilderSettings` (`startFlow` + Gen App Builder backing) are **not indexed** and will not appear in Cloud Asset API results, even at project scope.
- In practice, agents created in the current "Conversational Agents" console may be either type. This means `useCloudAssetApi=true` will silently miss Flow-based agents.
- **Recommended:** Use `useCloudAssetApi=false` for single-project deployments. Direct project-scoped `GET .../agents` (§2.1) returns all agent types reliably. Cloud Asset API is only beneficial for multi-project org-wide discovery.
- **RFE-1 (DONE 2026-04-19):** `agentApiFlavor=both` discovers both CX agents and reasoning engines via direct API calls without Cloud Asset API. `listFlavorDirect()` is called separately for each flavor and results are merged. `useCloudAssetApi=true` takes precedence if both are set (see RFE-3).

---

### 2.9 GCS — Fetch Inventory Artifacts

**Invoked by:** Connector — `fetchGcsIdentityBindings()`, `fetchGcsServiceAccounts()`, `fetchGcsToolCredentials()`  
**When:** During reconciliation when `identityBindingScanEnabled=true`  
**Auth:** None — pre-signed URL is self-authenticating

**Endpoints (OPENICF-4016 — one URL per artifact):**
```
GET {gcsIdentityBindingsUrl}
GET {gcsServiceAccountsUrl}
GET {gcsToolCredentialsUrl}
```

**Required Permission:**  
None on the connector SA. The pre-signed URL carries all credentials in its query string. Sending an `Authorization` header alongside a pre-signed URL causes GCS to return `400 InvalidAuthenticationInfo`.

**Error behavior:**
- HTTP non-2xx → `ConnectorException` thrown immediately; reconciliation aborts
- `IOException` / `InterruptedException` → `ConnectorException` thrown; reconciliation aborts
- No silent empty fallback

**Notes:** Each URL is a full pre-signed URL for a specific GCS object path — a single URL cannot cover multiple artifacts (OPENICF-4016). All three URLs must be regenerated before expiry and updated in the connector configuration. Recommended validity: 7 days with automated rotation.

---

## 3. Offline Job APIs

These APIs are called by the Vertex Tools Inventory Job running as a Cloud Run Job. All calls use the job service account's Application Default Credentials.

---

### 3.1 Dialogflow CX — List Agents

**Invoked by:** Job — `collect_dialogflow_agents_live()`  
**When:** Live mode; `flavor` is `dialogflowcx` or `both`  
**Per:** Each `(projectId, location)` combination in config

**REST endpoint:**
```
GET https://{location}-dialogflow.googleapis.com/v3/projects/{projectId}/locations/{location}/agents
Authorization: Bearer {adc_token}
```

> **Bug fixed 2026-04-19:** Original code used global endpoint `https://dialogflow.googleapis.com/v3/...` which returns HTTP 400. Fixed to use regional endpoint `https://{location}-dialogflow.googleapis.com/v3/...` (location extracted from config).

**Pagination:** `nextPageToken`; caller loops until absent.

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `dialogflow.agents.list` | `roles/dialogflow.viewer` |

**Scope:** Project

**Error behavior:**
- HTTP non-2xx → `urllib.error.HTTPError` raised; empty list returned for that `(projectId, location)` pair; run continues

---

### 3.2 Dialogflow CX — Get IAM Policy (Agent)

**Invoked by:** Job — `_get_resource_policy()` in `collectors/iam.py`  
**When:** Live mode; called per Dialogflow CX agent to get resource-level IAM bindings  
**Per:** Each discovered agent

**REST endpoint:**
```
POST https://{location}-dialogflow.googleapis.com/v3/{agentResourceName}:getIamPolicy
Authorization: Bearer {adc_token}
Content-Type: application/json

{}
```

> **Bug fixed 2026-04-19:** Original code used global endpoint `https://dialogflow.googleapis.com/v3/...` which returns HTTP 400. Fixed to use regional endpoint derived from `agent.location`.

**Notes — confirmed via live GCP testing (2026-04-19):**
- Returns **HTTP 404** for all agents created in the current "Conversational Agents" console. Resource-level IAM policy is not supported for these agent types. The job logs a WARNING and produces no bindings for CX agents — the project-level fallback (§3.5) is NOT triggered on 404 (only on 2xx with empty bindings).
- `setIamPolicy` also returns 404 for these agents — IAM bindings can only be set at project level.
- Vertex AI reasoning engines (§3.4) do support resource-level `getIamPolicy` and `setIamPolicy`.

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `dialogflow.agents.getIamPolicy` | Custom role or predefined role that includes this permission |

**Scope:** Project

**Binding precedence:** If resource-level policy is non-empty, project-level fallback (3.5) is skipped for this agent.

**Error behavior:**
- HTTP non-2xx → WARNING logged with HTTP status and response body; `None` returned; **no project fallback** (permission failure should not be masked)
- HTTP 2xx with empty `bindings` → WARNING logged with agent resource name; falls through to project-level fallback (3.5)

---

### 3.3 Vertex AI Agent Engine — List Reasoning Engines

**Invoked by:** Job — `collect_reasoning_engines_live()`  
**When:** Live mode; `flavor` is `vertexai` or `both`  
**Per:** Each `(projectId, location)` combination in config

**REST endpoint:**
```
GET https://{location}-aiplatform.googleapis.com/v1/projects/{projectId}/locations/{location}/reasoningEngines
Authorization: Bearer {adc_token}
```

**Pagination:** `nextPageToken`; caller loops until absent.

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `aiplatform.reasoningEngines.list` | `roles/aiplatform.viewer` |

**Scope:** Project

**Error behavior:**
- HTTP non-2xx → `urllib.error.HTTPError` raised; empty list returned for that `(projectId, location)` pair; run continues

---

### 3.4 Vertex AI Agent Engine — Get IAM Policy (Reasoning Engine)

**Invoked by:** Job — `_get_resource_policy()` in `collectors/iam.py`  
**When:** Live mode; called per Vertex AI reasoning engine to get resource-level IAM bindings  
**Per:** Each discovered reasoning engine

**REST endpoint:**
```
POST https://{location}-aiplatform.googleapis.com/v1/{reasoningEngineResourceName}:getIamPolicy
Authorization: Bearer {adc_token}
Content-Type: application/json

{}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `aiplatform.reasoningEngines.getIamPolicy` | Custom role or predefined role that includes this permission |

**Scope:** Project

**Notes:** HTTP 404 from `getIamPolicy` on reasoning engines is expected behavior — the API does not support resource-level IAM on all engine types. When this call returns empty, the job falls through to project-level fallback (3.5) with `MEDIUM` confidence.

**Error behavior:**
- HTTP non-2xx → WARNING logged with HTTP status and response body; `None` returned; **no project fallback**
- HTTP 2xx with empty `bindings` → WARNING logged; falls through to project-level fallback (3.5)

---

### 3.5 Resource Manager — Get Project IAM Policy

**Invoked by:** Job — `_collect_project_policy()` in `collectors/iam.py`  
**When:** Live mode; called per project when no resource-level IAM policy was found for an agent (fallback path)  
**Per:** Each distinct `projectId` across all collected agents; deduplicated — only called once per project

**REST endpoint:**
```
POST https://cloudresourcemanager.googleapis.com/v1/projects/{projectId}:getIamPolicy
Authorization: Bearer {adc_token}
Content-Type: application/json

{}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `resourcemanager.projects.getIamPolicy` | `roles/iam.securityReviewer` or `roles/viewer` |

**Scope:** Project

**Binding precedence:** Bindings derived from project-level policy are tagged `INHERITED_PROJECT_BINDING` with `confidence=MEDIUM`. Role filtering and self-binding exclusion still apply.

**Error behavior:**
- HTTP non-2xx → WARNING logged with HTTP status and response body; no project-level bindings emitted for this project

---

### 3.6 GCS — Upload Artifacts

**Invoked by:** Job — `upload_directory_to_gcs()` via `google-cloud-storage` Python client  
**When:** End of every successful job run when `bucketName` is configured  
**Per:** Each artifact file; written to both `runs/{timestamp}/` and optionally `latest/`

**Operation:** `Blob.upload_from_filename()` — HTTP PUT to GCS XML API

**Endpoint (internal to client library):**
```
PUT https://storage.googleapis.com/upload/storage/v1/b/{bucketName}/o?uploadType=multipart
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `storage.objects.create` | `roles/storage.objectAdmin` |
| `storage.objects.delete` | `roles/storage.objectAdmin` (for overwrite of `latest/`) |

**Scope:** GCS bucket

**Artifacts uploaded per run:**
| File | Run prefix | Latest prefix |
|---|---|---|
| `agents.json` | Yes | Yes (if `writeLatest=true`) |
| `identity-bindings.json` | Yes | Yes (if `writeLatest=true`) |
| `service-accounts.json` | Yes | Yes (if `writeLatest=true`) |
| `tool-credentials.json` | Yes | Yes (if `writeLatest=true`) |
| `manifest.json` | Yes | Yes (if `writeLatest=true`) |

**Error behavior:**
- Any upload failure raises an exception from the storage client; job exits non-zero; Cloud Run Job marks the task as failed and triggers retry (up to `--max-retries`)

---


---

### 3.7 IAM API — Service Account Enrichment

**Invoked by:** Job — `collect_service_accounts_live()` in `collectors/service_accounts.py`  
**When:** Live mode; called per deduplicated SA after `normalize_service_accounts(agents)`  
**Per:** Each unique SA email across all collected agents

**REST endpoints:**
```
GET https://iam.googleapis.com/v1/projects/{projectId}/serviceAccounts/{email}
Authorization: Bearer {adc_token}

GET https://iam.googleapis.com/v1/projects/{projectId}/serviceAccounts/{email}/keys
Authorization: Bearer {adc_token}
```

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `iam.serviceAccounts.list`, `iam.serviceAccounts.get` | `roles/iam.serviceAccountViewer` or custom |
| `iam.serviceAccountKeys.list` | Same |

**Scope:** Project

**Fields used from SA GET:** `name`, `displayName`, `description`, `uniqueId`, `oauth2ClientId`, `disabled`, `createTime`

**Fields used from keys LIST:** `name`, `keyType`, `validAfterTime`, `validBeforeTime`, `keyAlgorithm` — all key types included; serialized as `keysJson` JSON string.

**Error behavior:**
- SA GET HTTP non-2xx → WARNING logged; SA enrichment skipped; sparse record emitted (`id`, `platform`, `email`, `projectId`, `linkedAgentIds` only)
- Keys LIST HTTP non-2xx → WARNING logged; SA metadata included but `keysJson=null`, `keyCount=0`

---

### 3.8 Dialogflow CX — List Webhooks (Job)

**Invoked by:** Job — `collect_webhooks_live()` in `collectors/webhooks.py`  
**When:** Live mode; `flavor` is `dialogflowcx` or `both`  
**Per:** Each discovered Dialogflow CX agent

**REST endpoint:**
```
GET https://{location}-dialogflow.googleapis.com/v3/{agentResourceName}/webhooks
Authorization: Bearer {adc_token}
```

> **Bug fixed 2026-04-19:** Original code used global endpoint `https://dialogflow.googleapis.com/v3/...` which returns HTTP 400. Fixed to use regional endpoint — location extracted from position `[3]` of the resource name path segments (`projects/{p}/locations/{location}/agents/{id}`).

**Pagination:** `nextPageToken`; caller loops until absent.

**Required Permission:**
| Permission | Role (predefined) |
|---|---|
| `dialogflow.webhooks.list` | `roles/dialogflow.viewer` |

**Scope:** Project

**Purpose:** Collects webhook credential metadata for the `agentToolCredential` object class. Auth fields parsed: `genericWebService.serviceAccount`, `genericWebService.oauthConfig`, `genericWebService.requestHeaders`.

**Error behavior:**
- HTTP non-2xx → empty list for that agent; run continues

---

## 4. Permission Summary

### 4.1 Connector Service Account

| Permission | API | Scope | When Required |
|---|---|---|---|
| `dialogflow.agents.list` | Dialogflow CX | Project | When `agentApiFlavor=dialogflowcx` or `both` |
| `dialogflow.agents.get` | Dialogflow CX | Project | When `agentApiFlavor=dialogflowcx` or `both` |
| `dialogflow.tools.list` | Dialogflow CX | Project | When `agentApiFlavor=dialogflowcx` or `both` |
| `dialogflow.webhooks.list` | Dialogflow CX | Project | When `agentApiFlavor=dialogflowcx` or `both` |
| `aiplatform.reasoningEngines.list` | Vertex AI | Project | When `agentApiFlavor=vertexai` or `both` |
| `aiplatform.reasoningEngines.get` | Vertex AI | Project | When `agentApiFlavor=vertexai` or `both` |
| `cloudasset.assets.searchAllResources` | Cloud Asset | Organization | When `useCloudAssetApi=true` |
| `storage.objects.get` | GCS | Bucket | When `identityBindingScanEnabled=true` and not using pre-signed URL |

**Minimum predefined roles:**
- `roles/dialogflow.reader` at project scope
- `roles/aiplatform.viewer` at project scope (Vertex AI flavor only)
- `roles/cloudasset.viewer` at organization scope (org-wide mode only)

**No IAM `getIamPolicy` permissions required** — these were removed from the connector in OPENICF-4007.

### 4.2 Inventory Job Service Account

| Permission | API | Scope | When Required |
|---|---|---|---|
| `dialogflow.agents.list` | Dialogflow CX | Project | Live mode, `dialogflowcx` or `both` flavor |
| `dialogflow.agents.getIamPolicy` | Dialogflow CX | Project | Live mode; resource-level IAM collection |
| `aiplatform.reasoningEngines.list` | Vertex AI | Project | Live mode, `vertexai` or `both` flavor |
| `aiplatform.reasoningEngines.getIamPolicy` | Vertex AI | Project | Live mode; resource-level IAM collection |
| `resourcemanager.projects.getIamPolicy` | Resource Manager | Project | Live mode; project-level IAM fallback |
| `iam.serviceAccounts.list` | IAM | Project | Live mode; SA metadata enrichment |
| `iam.serviceAccounts.get` | IAM | Project | Live mode; SA metadata enrichment |
| `iam.serviceAccountKeys.list` | IAM | Project | Live mode; SA key enumeration |
| `storage.objects.create` | GCS | Bucket | Always (artifact upload) |
| `storage.objects.delete` | GCS | Bucket | When `writeLatest=true` (overwrite latest/) |

**Minimum predefined roles:**
- `roles/dialogflow.viewer` at project scope
- `roles/aiplatform.viewer` at project scope
- `roles/iam.securityReviewer` at project scope (for project IAM policy reads)
- `roles/storage.objectAdmin` on the GCS bucket

---

## 5. Authentication

### Connector

The connector authenticates using one of two modes, controlled by `useWorkloadIdentity`:

| Mode | `useWorkloadIdentity` | Mechanism |
|---|---|---|
| Service account key | `false` | Connector parses `serviceAccountKeyJson` (GuardedString), builds a JWT signed with the private key, and exchanges it for a bearer token at `https://oauth2.googleapis.com/token`. Token is cached until 60 seconds before expiry. |
| Workload identity / ADC | `true` | Not yet implemented. Currently throws `IllegalStateException`. Intended for future integration with `google-auth-library-java`. |

Token scope: `https://www.googleapis.com/auth/cloud-platform`

### Inventory Job

The job authenticates using Application Default Credentials (ADC) via the Cloud Run Job execution identity. The job service account is set at job creation time via `--service-account`. No explicit credential management is required in the job code.

---

## 6. Error Handling Reference

### Connector API Calls

| HTTP Status | Handling |
|---|---|
| 200–299 | Parse response; continue |
| 404 | Agent/resource not found — return `null` or skip; not fatal for list operations |
| 401 / 403 | `ConnectorException` — token invalid or permission denied; reconciliation aborts |
| 429 | No retry logic — `ConnectorException`; reconciliation aborts |
| 5xx | `ConnectorException`; reconciliation aborts |
| `IOException` / `InterruptedException` | Wrapped as `RuntimeException` or `ConnectorException`; reconciliation aborts |

### Connector GCS Reads

| Condition | Handling |
|---|---|
| HTTP non-2xx | `ConnectorException` thrown immediately; reconciliation aborts |
| `IOException` | `ConnectorException` thrown immediately; reconciliation aborts |
| Empty artifact (`{}` or `[]`) | Parsed as empty; no bindings/SAs returned; no exception |

### Inventory Job API Calls (direct REST)

| Condition | Handling |
|---|---|
| HTTP non-2xx on agent/engine list | `urllib.error.HTTPError`; empty list for that `(projectId, location)`; run continues |
| HTTP non-2xx on `getIamPolicy` (resource) | WARNING logged; `None` returned; no fallback |
| HTTP 2xx, empty `bindings` on `getIamPolicy` | WARNING logged; falls through to project fallback |
| HTTP non-2xx on project IAM fallback | WARNING logged; no project bindings emitted |
| HTTP non-2xx on SA GET | WARNING logged; sparse SA record emitted; enrichment skipped |
| HTTP non-2xx on SA keys LIST | WARNING logged; `keysJson=null`, `keyCount=0` |
| `urllib.error.URLError` (network) | WARNING logged; same handling as HTTP error for that call |

### Inventory Job GCS Upload

| Condition | Handling |
|---|---|
| Upload failure | Exception raised by storage client; job exits non-zero; Cloud Run retries up to `--max-retries` |
