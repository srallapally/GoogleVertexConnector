# Google Vertex AI Connector — System Design & Operations Guide

**Version:** 1.2 — OPENICF-4012 through 4017, RFE-1, RFE-2 complete  
**Date:** 2026-04-19  
**Platform:** PingOne IDM / OpenICF / GCP  
**Status:** Released — all planned enhancements complete

---

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope)
2. [Architecture Overview](#2-architecture-overview)
3. [Object Classes and Schema](#3-object-classes-and-schema)
4. [GCS Artifact Schemas](#4-gcs-artifact-schemas)
5. [Inventory Job — Build and Deployment](#5-inventory-job--build-and-deployment)
6. [Connector Configuration Reference](#6-connector-configuration-reference)
7. [GCP Permission Requirements](#7-gcp-permission-requirements)
8. [Known Limitations and Operational Notes](#8-known-limitations-and-operational-notes)
9. [Enhancement Roadmap](#9-enhancement-roadmap)

---

## 1. Purpose and Scope

This document describes the end-to-end design of the Google Vertex AI OpenICF connector and its companion offline inventory job. It is the authoritative reference for system architects, operations engineers, and developers who build, deploy, or maintain this system.

The system has two distinct components:

- **Google Vertex AI Connector** — a Java-based OpenICF bundle deployed in PingOne IDM. It performs live reconciliation of AI agents, tools, knowledge bases, and guardrails, and reads identity binding and service account data from pre-computed GCS artifacts.
- **Vertex Tools Inventory Job** — a Python batch job packaged as a Docker container and executed on Cloud Run. It collects IAM policies and service account metadata offline and emits normalized JSON artifacts to Google Cloud Storage for downstream connector ingestion.

This document covers:

- System architecture and component responsibilities
- Object classes and attribute schemas
- Live vs. offline data split and rationale
- GCS artifact schemas
- Inventory job build, deployment, and scheduling
- Connector configuration reference
- GCP permission requirements
- Known limitations and operational guidance

---

## 2. Architecture Overview

### 2.1 System Components

The system follows a split-responsibility architecture. Agent discovery and tool/KB/guardrail collection run live in the connector during each IDM reconciliation. Identity binding and service account collection are moved offline to a scheduled batch job that writes artifacts to GCS. The connector reads those artifacts during reconciliation without making any IAM API calls itself.

| Component | Technology | Responsibility |
|---|---|---|
| Google Vertex AI Connector | Java, OpenICF, OSGi bundle | Live agent/tool/KB/guardrail discovery; reads GCS artifacts for identity bindings and service accounts |
| Vertex Tools Inventory Job | Python 3.12, Docker, Cloud Run Job | Offline IAM policy collection, identity binding normalization, service account extraction; writes artifacts to GCS |
| Google Cloud Storage | GCS bucket | Durable artifact store; connector reads from `latest/` prefix; job writes to `runs/` and `latest/` |
| Cloud Scheduler | GCP managed service | Triggers the Cloud Run Job on a schedule (recommended: hourly or every 6 hours) |
| PingOne IDM | ForgeRock/Ping IDM runtime | Hosts the OpenICF connector; drives reconciliation and policy evaluation |

### 2.2 Data Flow

The following describes the data flow during a reconciliation cycle:

1. Cloud Scheduler fires and triggers the Cloud Run Job execution.
2. The inventory job collects Dialogflow CX and Vertex AI Agent Engine IAM policies and service account metadata from GCP APIs.
3. The job normalizes the data and writes five JSON artifacts (`agents.json`, `identity-bindings.json`, `service-accounts.json`, `tool-credentials.json`, `manifest.json`) to a timestamped run prefix in GCS, then promotes them to the `latest/` prefix.
4. PingOne IDM triggers a reconciliation of the Google Vertex AI connector.
5. The connector calls Dialogflow CX and Vertex AI APIs to discover agents, tools, knowledge bases, and guardrails (live).
6. When `identityBindingScanEnabled=true`, the connector fetches `identity-bindings.json`, `service-accounts.json`, and `tool-credentials.json` from the GCS `latest/` prefix via pre-signed URL (unauthenticated HTTPS GET).
7. The connector maps all collected data to OpenICF ConnectorObjects and returns them to IDM for reconciliation processing.

### 2.3 Live vs. Offline Split

The split between live and offline collection is deliberate. IAM `getIamPolicy` calls are expensive at scale — N+1 API calls per agent per reconciliation. Moving IAM collection offline eliminates this overhead and decouples IAM staleness from reconciliation latency. The connector's required GCP permission footprint is also reduced: it no longer needs `getIamPolicy` permissions.

| Data Type | Collection Method | Rationale |
|---|---|---|
| Agents (Dialogflow CX + Vertex AI) | Live — connector calls regional APIs | Agents must be current at time of reconciliation; creation/deletion is high-signal |
| Tools and webhooks | Live — connector fetches per-agent | Tool configuration changes are governance-relevant and must be current |
| Knowledge bases / data stores | Live — connector fetches per-agent | Same rationale as tools |
| Guardrails (safety settings) | Live — parsed from agent response | Derived from live agent payload; no separate API call needed |
| Tool auth summary | Live — derived from webhook auth fields | Credential type classification requires live webhook details |
| Identity bindings | Offline — inventory job writes to GCS | Avoids N+1 `getIamPolicy` calls during reconciliation; decouples IAM freshness from IDM scheduling |
| Service accounts | Offline — inventory job writes to GCS | Same rationale; SA enrichment (keys, linkedAgentIds) is expensive to compute live |
| Tool credentials | Offline — inventory job writes to GCS | Webhook credential metadata collected by job; avoids duplicate API calls |

---

## 3. Object Classes and Schema

The connector exposes six object classes to PingOne IDM. All are read-only (SearchOp only).

### 3.1 Agent (`__ACCOUNT__`)

Represents a Dialogflow CX agent or a Vertex AI Agent Engine (reasoningEngine). UID = full resource name.

| Attribute | Type | Source | Notes |
|---|---|---|---|
| platform | String | Connector | Always `GOOGLE_VERTEX_AI` |
| agentId | String | Live API | Full resource name |
| description | String | Live API | |
| foundationModel | String | Live API | `generativeModel` from `generativeSettings` |
| defaultLanguageCode | String | Live API | Dialogflow CX only |
| timeZone | String | Live API | Dialogflow CX only |
| startFlow | String | Live API | Dialogflow CX flow-based agents only |
| startPlaybook | String | Live API | Dialogflow CX playbook-based agents only |
| createdAt | String | Live API | ISO-8601 |
| updatedAt | String | Live API | ISO-8601 |
| safetySettings | String | Live API | Raw JSON from `generativeSafetySettings` |
| agentFramework | String | Live API | Vertex AI only — `google-adk`, `langchain`, etc. |
| serviceAccount | String | Live API | Vertex AI only — deployment SA email |
| toolIds | String[] | Live API | Resource names of all tools + webhooks |
| toolsRaw | String | Live API | JSON array of tool names |
| toolAuthSummary | String[] | Live (derived) | JSON entries per tool: `toolId`, `toolKey`, `toolType`, `authType`, `credentialRef` |
| knowledgeBaseIds | String[] | Live API | Resource names of data store connections |

### 3.2 Agent Tool (`agentTool`)

Represents a Dialogflow CX tool or webhook. UID = full resource name. Returns empty for Vertex AI agents.

| Attribute | Type | Source | Notes |
|---|---|---|---|
| platform | String | Connector | Always `GOOGLE_VERTEX_AI` |
| agentId | String | Live API | Parent agent resource name |
| toolType | String | Live API | `TOOL` or `WEBHOOK` |
| toolEndpoint | String | Live API | Endpoint URL for webhooks |
| toolKey | String | Live API | `toolId` with `/` replaced by `_`; safe for search indexing |
| description | String | Live API | |

### 3.3 Agent Knowledge Base (`agentKnowledgeBase`)

Represents a Dialogflow CX data store connection. UID = full resource name. Returns empty for Vertex AI agents.

| Attribute | Type | Source | Notes |
|---|---|---|---|
| platform | String | Connector | Always `GOOGLE_VERTEX_AI` |
| agentId | String | Live API | Parent agent resource name |
| knowledgeBaseId | String | Live API | Same as UID |
| dataStoreType | String | Live API | From API response |
| knowledgeBaseState | String | Live API | Status field if present |

### 3.4 Agent Guardrail (`agentGuardrail`)

Synthetic object derived from an agent's `safetySettings`. One guardrail per agent that has non-empty safety settings. UID = `{agentResourceName}:guardrail`.

| Attribute | Type | Source | Notes |
|---|---|---|---|
| platform | String | Connector | Always `GOOGLE_VERTEX_AI` |
| agentId | String | Derived | Parent agent resource name |
| safetyEnforcement | String | Derived | `BLOCK_NONE` / `BLOCK_FEW` / `BLOCK_SOME` / `BLOCK_MOST` |
| bannedPhrases | String[] | Derived | From `generativeSafetySettings.bannedPhrases` |
| defaultBannedPhrases | String[] | Derived | From root `defaultBannedPhrases` |
| rawSettingsJson | String | Derived | Original `safetySettings` JSON |

### 3.5 Agent Identity Binding (`agentIdentityBinding`)

Represents a caller-access binding derived from IAM policies by the offline inventory job. Read from `identity-bindings.json` in GCS. Only populated when `identityBindingScanEnabled=true`. UID = `id` field from job output (`ib-{sha1[:16]}`).

| Attribute | Type | Source | Notes |
|---|---|---|---|
| platform | String | Connector | Always `GOOGLE_VERTEX_AI` |
| agentId | String | GCS artifact | Short agent ID (last path segment) |
| agentVersion | String | GCS artifact | Always `latest` in v1 |
| principal | String | GCS artifact | Normalized email / domain / public token |
| kind | String | GCS artifact | `USER` / `GROUP` / `SERVICE_ACCOUNT` / `DOMAIN` |
| iamMember | String | GCS artifact | Original IAM member string (`user:...`, `group:...`, etc.) |
| iamRole | String | GCS artifact | Source IAM role (e.g. `roles/dialogflow.client`) |
| permissions | String[] | GCS artifact | `invoke` / `read` / `manage` |
| scope | String | GCS artifact | `AGENT_RESOURCE` or `PROJECT` — mapped from job `scopeType` field |
| scopeResourceName | String | GCS artifact | Resource where binding applies |
| sourceTag | String | GCS artifact | `DIRECT_RESOURCE_BINDING` / `INHERITED_PROJECT_BINDING` / `UNEXPANDED_GROUP` |
| confidence | String | GCS artifact | `HIGH` or `MEDIUM` |
| flavor | String | GCS artifact | `dialogflowcx` or `vertexai` |
| expanded | Boolean | GCS artifact | `false` when group member expansion was not performed |

### 3.6 Service Account (`serviceAccount`)

Represents a GCP service account that serves as the runtime identity of one or more agents. Read from `service-accounts.json` in GCS. Only populated when `identityBindingScanEnabled=true`. UID = `projects/{projectId}/serviceAccounts/{email}`.

| Attribute | Type | Source | Notes |
|---|---|---|---|
| platform | String | Connector | Always `GOOGLE_VERTEX_AI` |
| email | String | GCS artifact | Service account email |
| displayName | String | GCS artifact | Human-readable name if enriched |
| description | String | GCS artifact | |
| projectId | String | GCS artifact | Project containing the SA |
| uniqueId | String | GCS artifact | GCP unique ID (OPENICF-4009 enrichment) |
| createTime | String | GCS artifact | ISO-8601 (OPENICF-4009 enrichment) |
| oauth2ClientId | String | GCS artifact | OAuth2 client ID (OPENICF-4009 enrichment) |
| disabled | Boolean | GCS artifact | Whether the SA is disabled |
| keysJson | String | GCS artifact | JSON array of key metadata |
| keyCount | Integer | GCS artifact | Count of active keys |
| linkedAgentIds | String[] | GCS artifact | Agent IDs using this SA as runtime identity |

---

## 4. GCS Artifact Schemas

The inventory job writes five JSON artifacts to GCS. The connector reads three of them (`identity-bindings.json`, `service-accounts.json`, and `tool-credentials.json`). All artifacts are written to both a timestamped run prefix and a stable `latest/` prefix.

### 4.1 Artifact Layout

```
gs://<BUCKET>/<PREFIX>/runs/<TIMESTAMP>/
  agents.json
  identity-bindings.json
  service-accounts.json
  tool-credentials.json
  manifest.json

gs://<BUCKET>/<PREFIX>/latest/
  agents.json
  identity-bindings.json
  service-accounts.json
  tool-credentials.json
  manifest.json
```

The connector reads exclusively from the `latest/` prefix. The run-specific prefix is retained for auditability and rollback.

### 4.2 `identity-bindings.json`

Array of normalized identity binding objects. Only caller-access roles (`invoke`, `manage`) are included. Self-bindings (agent runtime SA) are excluded.

| Field | Type | Description |
|---|---|---|
| id | string | Deterministic binding ID: `ib-{sha1[:16]}` of `(agentId\|member\|role\|scopeResourceName)` |
| agentId | string | Short agent ID (last path segment of resource name) |
| agentVersion | string | Always `latest` in v1 |
| principal | string | Normalized principal value (email, domain, or public token) |
| principalType | string | `USER` / `GROUP` / `SERVICE_ACCOUNT` / `DOMAIN` / `PUBLIC` / `AUTHENTICATED_PUBLIC` |
| iamMember | string | Original IAM member string (`user:...`, `group:...`, etc.) |
| iamRole | string | Source IAM role (e.g. `roles/dialogflow.client`) |
| permissions | string[] | Normalized: `invoke`, `read`, and/or `manage` |
| scope | string | `resource` or `project` |
| scopeType | string | `AGENT_RESOURCE` or `PROJECT` |
| scopeResourceName | string | Full resource name where binding applies |
| sourceTag | string | `DIRECT_RESOURCE_BINDING` / `INHERITED_PROJECT_BINDING` / `UNEXPANDED_GROUP` |
| confidence | string | `HIGH` (resource-level) or `MEDIUM` (project fallback) |
| kind | string | Same as `principalType`; for downstream schema compatibility |
| flavor | string | `dialogflowcx` or `vertexai` |
| expanded | boolean | `false` when group member expansion was not performed |

### 4.3 `service-accounts.json`

Array of deduplicated runtime service accounts extracted from agent `runtimeIdentity` fields.

| Field | Type | Description |
|---|---|---|
| id | string | Canonical key: `projects/{projectId}/serviceAccounts/{email}` |
| platform | string | Always `GOOGLE_VERTEX_AI` |
| email | string | Service account email address |
| projectId | string | GCP project ID |
| linkedAgentIds | string[] | All agent IDs using this SA as runtime identity |
| displayName | string | Human-readable name (OPENICF-4009 enrichment, may be absent in v1) |
| disabled | boolean | Whether the SA is disabled (OPENICF-4009 enrichment) |
| uniqueId | string | GCP unique numeric ID (OPENICF-4009 enrichment) |
| createTime | string | ISO-8601 creation timestamp (OPENICF-4009 enrichment) |
| oauth2ClientId | string | OAuth2 client ID (OPENICF-4009 enrichment) |
| keysJson | string | JSON array of key metadata objects (OPENICF-4009 enrichment) |
| keyCount | integer | Count of active SA keys (OPENICF-4009 enrichment) |

> **Note:** Enrichment fields (`displayName`, `description`, `uniqueId`, `oauth2ClientId`, `disabled`, `createTime`, `keysJson`, `keyCount`) are populated by the inventory job via `GET .../serviceAccounts/{email}` and `GET .../serviceAccounts/{email}/keys`. If the SA cannot be fetched, the job logs a WARNING and emits only the base fields (`id`, `platform`, `email`, `projectId`, `linkedAgentIds`). The connector handles absent enrichment fields gracefully.

### 4.4 `manifest.json`

Single object describing the run. The connector does not read this file directly; it is used for operational validation and monitoring.

| Field | Type | Description |
|---|---|---|
| generatedAt | string | UTC timestamp: `YYYY-MM-DDTHH:MM:SSZ` |
| schemaVersion | string | Always `1.0` |
| platform | string | Always `GOOGLE_VERTEX_AI` |
| collectionMode | string | `fixtures` or `live` |
| flavorsIncluded | string[] | `dialogflowcx`, `vertexai`, or both |
| projectIdsScanned | string[] | Distinct GCP project IDs in the run |
| locationsScanned | string[] | Distinct regions in the run |
| agentCount | number | Records in `agents.json` |
| identityBindingCount | number | Records in `identity-bindings.json` |
| serviceAccountCount | number | Records in `service-accounts.json` |
| artifacts | object | File + count map for each artifact |
| toolCredentialCount | number | Records in `tool-credentials.json` |
| warnings | string[] | Warning conditions emitted during the run |

---

## 5. Inventory Job — Build and Deployment

The Vertex Tools Inventory Job is a Python 3.12 application packaged as a Docker container. It is designed to run as a Google Cloud Run Job on a schedule.

### 5.1 Job Configuration

The job is configured via a JSON file mounted or baked into the container. The config file path is passed via `--config` argument.

**Fixture mode** (local development and CI):

```json
{
  "flavor": "both",
  "fixtures": true,
  "dialogflow_fixture_path": "tests/fixtures/dialogflow_agent.json",
  "vertex_fixture_path": "tests/fixtures/reasoning_engine.json",
  "iam_fixture_path": "tests/fixtures/iam_policies.json",
  "output_dir": "./out"
}
```

**Live mode** (production):

```json
{
  "flavor": "both",
  "fixtures": false,
  "projectIds": ["YOUR_GCP_PROJECT_ID"],
  "locations": ["us-central1"],
  "output_dir": "/tmp/out",
  "bucketName": "your-inventory-bucket",
  "bucketPrefix": "vertex-inventory",
  "writeLatest": true
}
```

| Config Field | Type | Required | Description |
|---|---|---|---|
| flavor | string | Yes | `dialogflowcx`, `vertexai`, or `both` |
| fixtures | boolean | Yes | `true` = use fixture files; `false` = call live GCP APIs |
| projectIds | string[] | Live only | GCP project IDs to scan. Falls back to `GOOGLE_CLOUD_PROJECT` env var. |
| locations | string[] | Live only | GCP regions to scan. Defaults to `['us-central1']`. |
| output_dir | string | No | Local artifact output path. Default: `/tmp/out` |
| bucketName | string | No | GCS bucket for artifact upload. Omit to skip GCS upload. |
| bucketPrefix | string | No | GCS object path prefix inside the bucket. Default: empty. |
| writeLatest | boolean | No | Whether to maintain a stable `latest/` prefix. Default: `false`. |
| dialogflow_fixture_path | string | Fixture mode | Path to Dialogflow CX fixture JSON |
| vertex_fixture_path | string | Fixture mode | Path to Vertex AI fixture JSON |
| iam_fixture_path | string | Fixture mode | Path to IAM policies fixture JSON |

### 5.2 Build the Container Image

Build and push the container image to Google Container Registry or Artifact Registry.

**Build using Cloud Build (recommended for GCP):**

```bash
gcloud builds submit \
  --tag gcr.io/YOUR_PROJECT_ID/vertex-tools-inventory:latest \
  --project YOUR_PROJECT_ID
```

**Build locally for testing:**

```bash
docker build -t vertex-tools-inventory:local .
```

**Run locally to verify artifact output:**

```bash
docker run --rm \
  -v "$(pwd)/config/job-config.json:/app/config/job-config.json:ro" \
  -v "$(pwd)/out:/tmp/out" \
  vertex-tools-inventory:local
```

After a local run, verify that `/tmp/out` contains `agents.json`, `identity-bindings.json`, `service-accounts.json`, and `manifest.json`.

### 5.3 GCP Setup

#### 5.3.1 Create the Job Service Account

Create a dedicated service account for the Cloud Run Job. Do not reuse the connector's service account.

```bash
gcloud iam service-accounts create vertex-inventory-job \
  --display-name="Vertex Inventory Job" \
  --project=YOUR_PROJECT_ID
```

#### 5.3.2 Grant GCS Write Access

```bash
gcloud storage buckets add-iam-policy-binding \
  gs://YOUR_INVENTORY_BUCKET \
  --member="serviceAccount:vertex-inventory-job@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/storage.objectAdmin"
```

#### 5.3.3 Grant GCP Read Permissions

Required permissions for live mode:

- `dialogflow.agents.list`, `dialogflow.agents.get` — list and read Dialogflow CX agents
- `dialogflow.agents.getIamPolicy` — resource-level IAM for Dialogflow CX agents
- `aiplatform.reasoningEngines.list`, `aiplatform.reasoningEngines.get` — Vertex AI Agent Engine
- `aiplatform.reasoningEngines.getIamPolicy` — resource-level IAM for reasoning engines
- `resourcemanager.projects.getIamPolicy` — project-level IAM fallback
- `iam.serviceAccounts.list`, `iam.serviceAccounts.get` — required for SA enrichment (keys and metadata)
- `iam.serviceAccountKeys.list` — required for SA key enumeration

```bash
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:vertex-inventory-job@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/dialogflow.viewer"

gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:vertex-inventory-job@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/aiplatform.viewer"

gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:vertex-inventory-job@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/iam.securityReviewer"
```

### 5.4 Create the Cloud Run Job

```bash
gcloud run jobs create vertex-tools-inventory \
  --image gcr.io/YOUR_PROJECT_ID/vertex-tools-inventory:latest \
  --region us-central1 \
  --service-account vertex-inventory-job@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --memory 1Gi \
  --cpu 1 \
  --max-retries 2 \
  --task-timeout 600s \
  --set-env-vars INVENTORY_CONFIG_JSON='{
    "flavor":"both",
    "fixtures":false,
    "projectIds":["YOUR_PROJECT_ID"],
    "locations":["us-central1"],
    "output_dir":"/tmp/out",
    "bucketName":"YOUR_INVENTORY_BUCKET",
    "bucketPrefix":"vertex-inventory",
    "writeLatest":true
  }'
```

> **Note:** If passing config via `INVENTORY_CONFIG_JSON`, update `main.py` to read from this environment variable when `--config` is not provided. Alternatively, bake the config file into the image or mount it from a GCS-backed volume.

### 5.5 Execute the Job Manually

Execute the job manually to verify that artifacts land in the bucket before enabling the scheduler.

```bash
gcloud run jobs execute vertex-tools-inventory \
  --region us-central1 \
  --wait
```

Verify the artifacts after execution:

```bash
gcloud storage ls gs://YOUR_INVENTORY_BUCKET/vertex-inventory/latest/
gcloud storage cat gs://YOUR_INVENTORY_BUCKET/vertex-inventory/latest/manifest.json
```

Check that `manifest.json` shows the expected `agentCount`, `identityBindingCount`, and `serviceAccountCount`. Verify that `warnings` is empty or contains only expected entries.

### 5.6 Schedule with Cloud Scheduler

Grant the scheduler the Cloud Run Invoker role on the job:

```bash
gcloud run jobs add-iam-policy-binding vertex-tools-inventory \
  --region us-central1 \
  --member="serviceAccount:vertex-inventory-job@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/run.invoker"
```

Create the scheduler (hourly example):

```bash
gcloud scheduler jobs create http vertex-tools-inventory-hourly \
  --location us-central1 \
  --schedule "0 * * * *" \
  --uri "https://run.googleapis.com/v2/projects/YOUR_PROJECT_ID/locations/us-central1/jobs/vertex-tools-inventory:run" \
  --http-method POST \
  --oauth-service-account-email vertex-inventory-job@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --time-zone "UTC"
```

Recommended schedule frequencies:

- **Hourly** (`0 * * * *`) — maximum freshness; suitable for high-change environments
- **Every 6 hours** (`0 */6 * * *`) — balanced freshness; recommended for most deployments
- **Daily** (`0 6 * * *`) — minimum freshness; suitable for stable environments

### 5.7 Validate After Deployment

After the first scheduled execution, verify:

1. Cloud Run Job execution logs show SUCCESS status with no unhandled exceptions.
2. The timestamped run prefix exists in the GCS bucket.
3. All four artifact files are present in the run prefix.
4. The `latest/` prefix has been updated (if `writeLatest=true`).
5. `manifest.json` `agentCount`, `identityBindingCount`, and `serviceAccountCount` are non-zero.
6. `manifest.json` `warnings` contains only expected entries (group expansion warning is expected in v1).
7. The connector successfully reads `identity-bindings.json` and `service-accounts.json` after a reconnect or reconciliation trigger.

---

## 6. Connector Configuration Reference

All properties map to `GoogleVertexAIConfiguration` fields.

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| projectId | String | Yes | — | GCP project ID where agents are deployed |
| location | String | Yes | — | GCP region (e.g. `us-central1` or `global`) |
| agentApiFlavor | String | No | `dialogflowcx` | `dialogflowcx`, `vertexai`, or `both` — controls which API is called for agent discovery. `both` calls Dialogflow CX and Vertex AI Agent Engine APIs directly and merges results. |
| useWorkloadIdentity | Boolean | Yes | `true` | If true, use Application Default Credentials. If false, `serviceAccountKeyJson` is required. |
| serviceAccountKeyJson | GuardedString | Conditional | — | Full JSON key file content. Required when `useWorkloadIdentity=false`. |
| identityBindingScanEnabled | Boolean | No | `false` | When true, reads identity binding, service account, and tool credential artifacts from GCS. Requires `gcsIdentityBindingsUrl`, `gcsServiceAccountsUrl`, and `gcsToolCredentialsUrl`. |
| gcsIdentityBindingsUrl | String | Conditional | — | Pre-signed GCS URL for `identity-bindings.json`. Required when `identityBindingScanEnabled=true`. |
| gcsServiceAccountsUrl | String | Conditional | — | Pre-signed GCS URL for `service-accounts.json`. Required when `identityBindingScanEnabled=true`. |
| gcsToolCredentialsUrl | String | Conditional | — | Pre-signed GCS URL for `tool-credentials.json`. Required when `identityBindingScanEnabled=true`. |
| organizationId | String | Conditional | — | GCP organization ID for org-wide agent discovery via Cloud Asset API. Required when `useCloudAssetApi=true`. |
| useCloudAssetApi | Boolean | No | `false` | When true, discovers agents org-wide via Cloud Asset API instead of project-scoped discovery. |
| agentNameFilterRegex | String | No | — | Optional regex to filter agents by `displayName`. |

### 6.1 Generating Pre-Signed GCS URLs

The connector reads each GCS artifact via its own pre-signed URL. Each URL must be generated externally, set in the corresponding configuration property, and points to a specific GCS object — not a prefix or directory. The URL encodes all authentication in its query parameters; the connector sends no `Authorization` header.

Generate one URL per artifact using the Google Cloud CLI (valid for 7 days):

```bash
BUCKET=gen-lang-client-0559379892-vertex-inventory
PREFIX=vertex-inventory/latest
KEY=/path/to/connector-sa-key.json
SA=CONNECTOR_SA_EMAIL

gcloud storage sign-url gs://$BUCKET/$PREFIX/identity-bindings.json \
  --duration=7d --private-key-file=$KEY --service-account=$SA

gcloud storage sign-url gs://$BUCKET/$PREFIX/service-accounts.json \
  --duration=7d --private-key-file=$KEY --service-account=$SA

gcloud storage sign-url gs://$BUCKET/$PREFIX/tool-credentials.json \
  --duration=7d --private-key-file=$KEY --service-account=$SA
```

Set each resulting URL in `gcsIdentityBindingsUrl`, `gcsServiceAccountsUrl`, and `gcsToolCredentialsUrl` respectively.

> **Note:** Pre-signed URLs expire. Set a rotation process (cron job or Secret Manager rotation) to regenerate all three URLs before expiry and update the connector configuration.

---

## 7. GCP Permission Requirements

### 7.1 Connector Service Account

With OPENICF-4007, IAM `getIamPolicy` permissions are no longer required on the connector SA — those are now held by the inventory job SA.

| Permission / Role | Scope | Required For |
|---|---|---|
| `roles/dialogflow.reader` | Project | Listing and reading Dialogflow CX agents, tools, webhooks, data stores |
| `roles/aiplatform.viewer` | Project | Listing and reading Vertex AI Agent Engine reasoningEngines |
| `cloudasset.assets.searchAllResources` | Organization | Org-wide agent discovery via Cloud Asset API (only when `useCloudAssetApi=true`) |
| `storage.objects.get` | GCS bucket | Reading GCS artifacts (only when `identityBindingScanEnabled=true` and using SA-auth instead of pre-signed URL) |

Custom role definition (organization-level, used in the test environment):

```bash
gcloud iam roles create aiConnectorReadOnly \
  --organization=YOUR_ORG_ID \
  --title="AI Connector Read-Only" \
  --permissions="\
dialogflow.agents.list,dialogflow.agents.get,\
dialogflow.tools.list,dialogflow.webhooks.list,\
aiplatform.reasoningEngines.list,aiplatform.reasoningEngines.get,\
cloudasset.assets.searchAllResources"
```

### 7.2 Inventory Job Service Account

| Permission / Role | Scope | Required For |
|---|---|---|
| `roles/dialogflow.viewer` | Project | Reading Dialogflow CX agents and IAM policies |
| `roles/aiplatform.viewer` | Project | Reading Vertex AI reasoning engines and IAM policies |
| `roles/iam.securityReviewer` | Project | Reading project-level IAM policy for project fallback bindings |
| `dialogflow.agents.getIamPolicy` | Project | Resource-level IAM for Dialogflow CX agents |
| `aiplatform.reasoningEngines.getIamPolicy` | Project | Resource-level IAM for Vertex AI reasoning engines |
| `roles/storage.objectAdmin` | GCS bucket | Writing artifacts to the inventory bucket |
| `iam.serviceAccounts.list`, `iam.serviceAccounts.get` | Project | SA metadata enrichment — full SA GET |
| `iam.serviceAccountKeys.list` | Project | SA key enumeration |

---

## 8. Known Limitations and Operational Notes

### 8.1 v1 Limitations

- **Group expansion not performed.** Bindings for `group:...` principals have `expanded=false`. IDM receives the group principal; individual member resolution is not available in v1.
- **Vertex AI Agent Engine tools and knowledge bases return empty lists.** These sub-resources are not exposed by the Vertex AI API at the reasoning engine level.
- **No delta sync.** Every reconciliation is a full discovery pass. High agent counts will generate proportional API calls.
- **No retry logic.** HTTP errors on GCP API calls are not retried — they fail the reconciliation immediately.
- **Token refresh race condition.** Token caching is not protected against concurrent refresh under high load.

### 8.2 Pre-Signed URL Rotation

The three pre-signed GCS URLs (`gcsIdentityBindingsUrl`, `gcsServiceAccountsUrl`, `gcsToolCredentialsUrl`) have a finite expiry. If any URL expires between connector configuration updates, `identityBindingScanEnabled` reads will fail with `ConnectorException`, aborting reconciliation. Implement a rotation process to regenerate all three URLs before expiry.

### 8.3 GCS Read Failure Behavior

All three GCS fetch methods (`fetchGcsIdentityBindings`, `fetchGcsServiceAccounts`, `fetchGcsToolCredentials`) throw `ConnectorException` immediately on HTTP non-2xx or `IOException`. There is no silent empty fallback. A GCS read failure aborts the full reconciliation run. This is intentional — silent empty returns would cause identity bindings and service accounts to disappear from IDM without any visible error.

### 8.4 Inventory Freshness

The identity binding and service account data surfaced by the connector is only as fresh as the last successful inventory job run. If the job fails or has not run recently, the connector will serve stale GCS data. Monitor Cloud Run Job execution status and set alerting on job failures.

### 8.5 Verified Test Environment

| Parameter | Value |
|---|---|
| Organization ID | `321497704104` |
| Project ID | `gen-lang-client-0559379892` |
| Region | `us-central1` |
| Connector Service Account | `273041378232-compute@developer.gserviceaccount.com` |
| Custom Role | `organizations/321497704104/roles/aiConnectorReadOnly` |

---

## 9. Enhancement Roadmap

| ID | Description | Status | Depends On |
|---|---|---|---|
| OPENICF-4007 | GCS inventory reader; `identityBindingScanEnabled` semantics change; live IAM/SA removal | **DONE** | — |
| OPENICF-4008 | `agentIdentityBinding` schema alignment with Python job output | **DONE** | OPENICF-4007 |
| OPENICF-4009 | `serviceAccount` OC Python job enrichment (`disabled`, `uniqueId`, `createTime`, keys) | **DONE** | OPENICF-4007 |
| OPENICF-4010 | `agentToolCredential` OC; Python job webhook credential collection | **DONE** | — |
| OPENICF-4011 | `toolAuthSummary` live attribute on agent OC | **DONE** | — |
| OPENICF-4012 | Migrate `dialogflow.py` and `reasoning_engines.py` live collectors from `gcloud` CLI to direct REST | **DONE** | — |
| OPENICF-4013 | Migrate `iam.py` live collector from `gcloud` CLI to direct REST | **DONE** | — |
| OPENICF-4014 | Add error logging on IAM collection failures; no silent empty on HTTP error | **DONE** | OPENICF-4013 |
| OPENICF-4015 | Fix `normalize_tool_credentials()` signature mismatch | **DONE** | — |
| OPENICF-4016 | Replace `gcsInventoryBaseUrl` with 3 per-artifact pre-signed URL properties | **DONE** | OPENICF-4007 |
| OPENICF-4017 | `agentToolCredential` OC search/get in CrudService + connector routing | **DONE** | OPENICF-4016 |
| RFE-1 | `agentApiFlavor=both` — dual-flavor discovery without Cloud Asset API | **DONE** | — |
| RFE-2 | `startPlaybook` attribute on agent OC for playbook-based CX agents | **DONE** | — |
| RFE-3 | Emit init warning when `agentApiFlavor=both` and `useCloudAssetApi=true` (Cloud Asset overrides both) | **Backlog** | — |
| RFE-4 | Cloud Asset API misses flow-based (`startFlow` + `genAppBuilderSettings`) CX agents; single-project workaround | **Backlog** | — |
| RFE-5 | Read `spec.effectiveIdentity` for Vertex AI Agent Engine agents in `serviceAccount` OC (`runtimeIdentity` always null in v1 API) | **Backlog** | — |
| O-5 | `roles/iam.serviceAccountTokenCreator` absent from `ROLE_PERMISSION_MAP` in Python job | **Backlog** | — |
