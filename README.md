# Google Vertex AI OpenICF Connector

A read-only Java OpenICF connector for PingOne IDM that discovers and inventories AI agents,
tools, knowledge bases, guardrails, and identity bindings across Google Cloud AI platforms.

Supports **Dialogflow CX** and **Vertex AI Agent Engine** (reasoning engines).

---

## What it does

The connector performs live reconciliation of AI governance objects during each IDM
reconciliation cycle. IAM identity binding and service account collection are handled by a
companion offline batch job (see [Vertex Tools Inventory Job](#vertex-tools-inventory-job)).

**Live (connector):** agents, tools, knowledge bases, guardrails, tool auth summaries  
**Offline (Python job → GCS):** identity bindings, service accounts, tool credentials

---

## Object Classes

| Object Class | Description |
|---|---|
| `agent` (`__ACCOUNT__`) | Dialogflow CX agent or Vertex AI reasoning engine |
| `agentTool` | Tool or webhook attached to an agent |
| `agentKnowledgeBase` | Data store connection (Dialogflow CX only) |
| `agentGuardrail` | Synthetic object derived from an agent's safety settings |
| `agentIdentityBinding` | IAM caller-access binding (offline; requires `identityBindingScanEnabled`) |
| `serviceAccount` | Runtime service account for an agent (offline; requires `identityBindingScanEnabled`) |
| `agentToolCredential` | Webhook credential metadata (offline; requires `identityBindingScanEnabled`) |

All object classes are read-only (`SearchOp` only).

---

## Architecture

```
PingOne IDM
  └── OpenICF Connector (Java, OSGi)
        ├── Live: Dialogflow CX API  ──────────────────────► agents, tools, KBs, guardrails
        ├── Live: Vertex AI Agent Engine API  ──────────────► agents
        └── GCS (pre-signed URLs, optional)  ───────────────► identity bindings, SAs, tool creds
                                                   ▲
                                         Cloud Run Job (Python)
                                           writes artifacts hourly
```

---

## Prerequisites

- PingOne IDM with OpenICF remote connector server (RCS)
- GCP service account key JSON for the connector SA
- Connector SA granted the following GCP permissions:

**Project-scoped:**

```
roles/dialogflow.reader
roles/aiplatform.viewer
```

**Optional (org-scoped, for Cloud Asset API discovery):**

```
cloudasset.assets.searchAllResources
```

The connector SA does **not** need `getIamPolicy` permissions — IAM collection is handled by
the inventory job.

---

## Building

Requires Java 11+ and Maven 3.6+.

```bash
mvn clean package -DskipTests
```

Output: `target/google-vertex-ai-connector-*.jar` (OSGi bundle, ready for RCS deployment).

---

## Connector Configuration

All properties are set in the IDM connector configuration JSON.

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `projectId` | String | Yes | — | GCP project ID |
| `location` | String | Yes | — | GCP region (e.g. `us-central1`) |
| `agentApiFlavor` | String | No | `dialogflowcx` | `dialogflowcx`, `vertexai`, or `both` |
| `useWorkloadIdentity` | Boolean | Yes | `true` | If `false`, `serviceAccountKeyJson` is required |
| `serviceAccountKeyJson` | GuardedString | Conditional | — | Full SA key JSON. Required when `useWorkloadIdentity=false` |
| `identityBindingScanEnabled` | Boolean | No | `false` | When `true`, reads identity binding artifacts from GCS |
| `gcsIdentityBindingsUrl` | String | Conditional | — | Pre-signed GCS URL for `identity-bindings.json` |
| `gcsServiceAccountsUrl` | String | Conditional | — | Pre-signed GCS URL for `service-accounts.json` |
| `gcsToolCredentialsUrl` | String | Conditional | — | Pre-signed GCS URL for `tool-credentials.json` |
| `organizationId` | String | Conditional | — | GCP org ID. Required when `useCloudAssetApi=true` |
| `useCloudAssetApi` | Boolean | No | `false` | Discover agents org-wide via Cloud Asset API |
| `agentNameFilterRegex` | String | No | — | Regex to filter agents by `displayName` |

### Generating pre-signed GCS URLs

The connector reads GCS artifacts via per-object pre-signed URLs (no `Authorization` header).
Generate one URL per artifact; each is valid for up to 7 days.

```bash
BUCKET=your-inventory-bucket
PREFIX=vertex-inventory/latest
KEY=/path/to/connector-sa-key.json
SA=connector-sa@your-project.iam.gserviceaccount.com

gcloud storage sign-url gs://$BUCKET/$PREFIX/identity-bindings.json \
  --duration=7d --private-key-file=$KEY --service-account=$SA

gcloud storage sign-url gs://$BUCKET/$PREFIX/service-accounts.json \
  --duration=7d --private-key-file=$KEY --service-account=$SA

gcloud storage sign-url gs://$BUCKET/$PREFIX/tool-credentials.json \
  --duration=7d --private-key-file=$KEY --service-account=$SA
```

Set each resulting URL in `gcsIdentityBindingsUrl`, `gcsServiceAccountsUrl`, and
`gcsToolCredentialsUrl`. Rotate before expiry — an expired URL aborts reconciliation.

---

## Vertex Tools Inventory Job

The offline inventory job collects IAM policies and service account metadata and writes five
JSON artifacts to GCS. It runs as a Cloud Run Job on a schedule.

**Artifacts written:**

```
gs://<BUCKET>/<PREFIX>/latest/
  agents.json
  identity-bindings.json
  service-accounts.json
  tool-credentials.json
  manifest.json
```

The connector reads exclusively from `latest/`. Run-specific prefixes (`runs/<TIMESTAMP>/`)
are retained for auditing.

### Job service account permissions

The job SA (separate from the connector SA) requires:

```
roles/dialogflow.viewer          # project-scoped
roles/aiplatform.viewer          # project-scoped
roles/iam.securityReviewer       # project-scoped (getIamPolicy)
roles/storage.objectAdmin        # GCS bucket
```

### Deploy to Cloud Run

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

### Schedule with Cloud Scheduler

```bash
gcloud scheduler jobs create http vertex-tools-inventory-hourly \
  --location us-central1 \
  --schedule "0 * * * *" \
  --uri "https://run.googleapis.com/v2/projects/YOUR_PROJECT_ID/locations/us-central1/jobs/vertex-tools-inventory:run" \
  --http-method POST \
  --oauth-service-account-email vertex-inventory-job@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --time-zone "UTC"
```

---

## Known Limitations

- **No delta sync.** Every reconciliation is a full scan. API call count scales linearly with agent count.
- **No retry logic.** HTTP errors on GCP API calls fail reconciliation immediately.
- **GCS failure aborts reconciliation.** No silent empty fallback — a failed GCS read throws `ConnectorException`.
- **`agentKnowledgeBase` returns zero records** for agents created via the current Dialogflow CX console. Data store tools appear as `agentTool` with `toolType=DATA_STORE_TOOL` instead.
- **`agentGuardrail` returns zero records** for current console agents. The `generativeSettings` field is absent from the agent GET response even when safety settings are configured in the UI.
- **Group principals are not expanded.** Bindings for `group:...` principals have `expanded=false`; individual member resolution requires extending `normalize/bindings.py`.
- **Custom IAM roles produce no bindings** unless added to `ROLE_PERMISSION_MAP` in `normalize/roles.py`.
- **Token refresh is not concurrency-safe.** Race conditions possible under high parallel load.
- **Pre-signed URLs expire.** Implement rotation before the 7-day TTL or reconciliation will fail.

---

## Project Structure

```
src/main/java/.../googlevertexai/
  GoogleVertexAIConnector.java        # OpenICF entry point (SearchOp, SchemaOp, TestOp)
  GoogleVertexAIConfiguration.java    # Connector configuration properties
  GoogleVertexAIConnection.java       # Token management, HTTP client
  operations/
    GoogleVertexAICrudService.java    # Object class dispatch and search logic
    GoogleVertexAIClient.java         # All GCP REST API calls
  utils/
    GoogleVertexAIConstants.java      # Attribute and OC name constants
    GoogleVertexAIToolDescriptor.java # Tool/webhook parsing helpers
    AgentDescriptor.java              # Agent normalization model
```

---

## Further Reading

- `vertex-ai-connector-design.md` — full system design, schema reference, GCS artifact schemas
- `vertex-ai-connector-api-runbook.md` — every GCP API endpoint called, with request/response details
- `vertex-tools-inventory-job-design.md` — offline job architecture, collector design, failure modes
