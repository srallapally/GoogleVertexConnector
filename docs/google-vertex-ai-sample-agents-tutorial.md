# Google Vertex AI Connector - Sample Agents Tutorial

This tutorial walks through creating 6 AI agents (4 Dialogflow CX + 2 Vertex AI Agent Engine) that demonstrate all object classes discoverable by the OpenICF connector.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Organization: 321497704104                          │
├─────────────────────────────────────────────────────────────────────────────┤
│  Project: gen-lang-client-0559379892 | Region: us-central1                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  │
│  │ DIALOGFLOW CX       │  │ DIALOGFLOW CX       │  │ DIALOGFLOW CX       │  │
│  │ Agent 1: Customer   │  │ Agent 2: HR         │  │ Agent 3: IT Help    │  │
│  │ Service Bot         │  │ Assistant           │  │ Desk                │  │
│  ├─────────────────────┤  ├─────────────────────┤  ├─────────────────────┤  │
│  │ Tools:              │  │ Tools:              │  │ Tools:              │  │
│  │  • OrderLookup      │  │  • EmployeeDir      │  │  • TicketCreate     │  │
│  │  • RefundProcessor  │  │  • PTOBalance       │  │  • AssetLookup      │  │
│  │ Webhooks:           │  │ Webhooks:           │  │ Webhooks:           │  │
│  │  • PaymentGateway   │  │  • WorkdaySync      │  │  • ServiceNowAPI    │  │
│  │ DataStores: NONE*   │  │ DataStores: NONE*   │  │ DataStores: NONE*   │  │
│  │ Guardrail: NONE*    │  │ Guardrail: NONE*    │  │ Guardrail: NONE*    │  │
│  └─────────┬───────────┘  └─────────┬───────────┘  └─────────┬───────────┘  │
│            │                        │                        │              │
│            ▼                        ▼                        ▼              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    IAM BINDINGS (per agent)                         │    │
│  │  • user:jnelson@unfinishedlife.org → roles/dialogflow.admin         │    │
│  │  • group:ai_platform_users@unfinishedlife.org → roles/dialogflow.viewer  │    │
│  │  • serviceAccount:cicd-bot@...→ roles/dialogflow.reader             │    │
│  │  • domain:unfinishedlife.org → roles/dialogflow.client              │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
│  * DataStores: agent-level data store connections removed in current console│
│  * Guardrail: generativeSettings not returned by agent GET API in current console│
│                                                                             │
│  ┌─────────────────────┐  ┌─────────────────────────────────────────────┐   │
│  │ DIALOGFLOW CX       │  │ SERVICE ACCOUNTS                            │   │
│  │ Agent 4: Sales      │  ├─────────────────────────────────────────────┤   │
│  │ Qualification       │  │ shared-agent-sa@...iam.gserviceaccount.com  │   │
│  ├─────────────────────┤  │  └─► Used by: (none — deploymentSpec not     │   │
│  │ Tools:              │  │       supported in v1 API)                   │   │
│  │  • LeadScoring      │  │  └─► IAM: user, group can impersonate       │   │
│  │  • CRMIntegration   │  │ sales-agent-sa@...iam.gserviceaccount.com   │   │
│  │ Webhooks:           │  │  └─► Used by: (none - Vertex AI only)       │   │
│  │  • SalesforceSync   │  │  └─► IAM: cicd-bot can create tokens        │   │
│  │ DataStores: NONE*   │  │                                             │   │
│  │ Guardrail: NONE*    │  │ cicd-bot@...iam.gserviceaccount.com         │   │
│  └─────────────────────┘  │  └─► Used by: (none - CI/CD automation)     │   │
│                           │  └─► IAM: admin group can manage            │   │
│                           └─────────────────────────────────────────────┘   │
│  ┌─────────────────────┐  ┌─────────────────────┐                           │
│  │ VERTEX AI AGENT     │  │ VERTEX AI AGENT     │                           │
│  │ ENGINE              │  │ ENGINE              │                           │
│  │ Agent 5: Research   │  │ Agent 6: Code       │                           │
│  │ Assistant           │  │ Review Bot          │                           │
│  ├─────────────────────┤  ├─────────────────────┤                           │
│  │ Framework: langgraph│  │ Framework: google-adk│                          │
│  │ ServiceAccount:     │  │ ServiceAccount:     │                           │
│  │  null (deploymentSpec│  │  null (deploymentSpec│                          │
│  │  not in v1 API)     │  │  not in v1 API)     │                           │
│  │ (No sub-resources)  │  │ (No sub-resources)  │                           │
│  └─────────────────────┘  └─────────────────────┘                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Prerequisites

```bash
# Set environment variables
export PROJECT_ID="gen-lang-client-0559379892"
export REGION="us-central1"
export ORG_ID="321497704104"

# Enable required APIs
gcloud services enable dialogflow.googleapis.com --project=$PROJECT_ID
gcloud services enable aiplatform.googleapis.com --project=$PROJECT_ID
gcloud services enable iam.googleapis.com --project=$PROJECT_ID
gcloud services enable discoveryengine.googleapis.com --project=$PROJECT_ID
gcloud services enable secretmanager.googleapis.com --project=$PROJECT_ID

# Authenticate
gcloud auth login
gcloud config set project $PROJECT_ID
```

---

## Part 1: Service Accounts (Create First)

Create service accounts before agents so we can reference them.

### 1.1 Shared Agent Service Account

Used by multiple Vertex AI agents to demonstrate `linkedAgentIds` relationship.

```bash
# Create the service account
gcloud iam service-accounts create shared-agent-sa \
  --display-name="Shared AI Agent Service Account" \
  --description="Service account shared by multiple Vertex AI agents for demo purposes"

# User binding
gcloud iam service-accounts add-iam-policy-binding \
  shared-agent-sa@${PROJECT_ID}.iam.gserviceaccount.com \
  --member="user:jnelson@unfinishedlife.org" \
  --role="roles/iam.serviceAccountUser"

# Group binding
gcloud iam service-accounts add-iam-policy-binding \
  shared-agent-sa@${PROJECT_ID}.iam.gserviceaccount.com \
  --member="group:ai_platform_users@unfinishedlife.org" \
  --role="roles/iam.serviceAccountUser"

# NOTE: The serviceAccount binding for cicd-bot is added in §1.2 after cicd-bot is created.
```

### 1.2 CI/CD Bot Service Account

Used for automation, demonstrates SA with its own IAM bindings.

```bash
gcloud iam service-accounts create cicd-bot \
  --display-name="CI/CD Automation Bot" \
  --description="Service account for CI/CD pipeline automation"

# Grant admin group ability to manage this SA
gcloud iam service-accounts add-iam-policy-binding \
  cicd-bot@${PROJECT_ID}.iam.gserviceaccount.com \
  --member="group:ai_platform_users@unfinishedlife.org" \
  --role="roles/iam.serviceAccountAdmin"

# Create a key for this SA (will appear in keys attribute)
gcloud iam service-accounts keys create ~/cicd-bot-key.json \
  --iam-account=cicd-bot@${PROJECT_ID}.iam.gserviceaccount.com

# Now that cicd-bot exists, bind it to shared-agent-sa
# (cicd-bot can create tokens as shared-agent-sa)
gcloud iam service-accounts add-iam-policy-binding \
  shared-agent-sa@${PROJECT_ID}.iam.gserviceaccount.com \
  --member="serviceAccount:cicd-bot@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountTokenCreator"
```

### 1.3 Sales Agent Service Account

Dedicated SA for sales-related agents.

```bash
gcloud iam service-accounts create sales-agent-sa \
  --display-name="Sales AI Agent Service Account" \
  --description="Dedicated service account for sales AI agents"

# Domain-wide delegation (all users in domain can use)
gcloud iam service-accounts add-iam-policy-binding \
  sales-agent-sa@${PROJECT_ID}.iam.gserviceaccount.com \
  --member="domain:unfinishedlife.org" \
  --role="roles/iam.serviceAccountUser"
```

---

### 1.4 Project-Level IAM Bindings (primary path — resource-level not supported)

> **Confirmed:** The current "Conversational Agents" console does not support resource-level IAM policies on Dialogflow CX agents. The `getIamPolicy` endpoint returns 404 for all agents. All agent identity bindings are therefore set at project level and will appear in the offline job output as `sourceTag=INHERITED_PROJECT_BINDING`, `confidence=MEDIUM`.

Set all four binding types at project level. These apply to all Dialogflow CX agents in the project.

```bash
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="user:jnelson@unfinishedlife.org" \
  --role="roles/dialogflow.admin"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="group:ai_platform_users@unfinishedlife.org" \
  --role="roles/dialogflow.viewer"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:cicd-bot@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/dialogflow.reader"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:cicd-bot@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/dialogflow.client"
```

**What this produces in `identity-bindings.json` (per agent, for all 4 CX agents):**

| Field | Value |
|---|---|
| `sourceTag` | `INHERITED_PROJECT_BINDING` |
| `scope` / `scopeType` | `project` / `PROJECT` |
| `scopeResourceName` | `projects/gen-lang-client-0559379892` |
| `confidence` | `MEDIUM` |

> `domain:` member type is not supported in project-level IAM bindings. `DOMAIN` kind records will not appear. `DIRECT_RESOURCE_BINDING` records are not producible with the current console.

---

## Part 2: Dialogflow CX Agents

### 2.1 Agent 1: Customer Service Bot

**Purpose**: Handles customer inquiries, order lookups, and refunds.

#### Create the Agent

1. Go to [Dialogflow CX Console](https://dialogflow.cloud.google.com/cx)
2. Select project `gen-lang-client-0559379892`
3. Click **Create Agent**

| Field | Value |
|-------|-------|
| Display name | `customer-service-bot` |
| Location | `us-central1` |
| Time zone | `America/Los_Angeles` |
| Default language | `en` |

4. Click **Create**

#### Configure Generative Settings (for Guardrail and foundationModel)

1. In Agent Settings → **Generative AI** → **Model**
2. Set **Generative model**: `gemini-1.5-flash`

   > This populates the `foundationModel` attribute on the connector's `agent` object class. The connector reads `generativeSettings.generativeModel` from the Dialogflow CX agent API response.

3. In Agent Settings → **Generative AI** → **Safety Settings**
4. Set **Safety Enforcement**: `BLOCK_SOME`
5. Add **Banned Phrases**:
   - `competitor pricing` (en)
   - `internal discount codes` (en)
   - `employee names` (en)

#### Create Tools

**Tool 1: OrderLookup**

1. Go to **Manage** → **Tools** → **Create**
2. Fill in the form:

| Field | Value |
|-------|-------|
| Tool name | `OrderLookup` |
| Type | `OpenAPI` |
| Description | `Retrieves order status and details by order ID` |
| Authentication type | `Service agent token` |

3. In the **Schema** field, select **YAML** and paste:

```yaml
openapi: "3.0.0"
info:
  title: Order Lookup API
  version: "1.0"
servers:
  - url: https://orders.example.com/api/v1
paths:
  /orders/{order_id}:
    get:
      operationId: lookup_order
      summary: Look up order status by order ID
      parameters:
        - name: order_id
          in: path
          required: true
          schema:
            type: string
      responses:
        "200":
          description: Order details
          content:
            application/json:
              schema:
                type: object
                properties:
                  order_id:
                    type: string
                  status:
                    type: string
                  estimated_delivery:
                    type: string
```

4. Click **Save**.

**Tool 2: RefundProcessor**

1. Go to **Manage** → **Tools** → **Create**
2. Fill in the form:

| Field | Value |
|-------|-------|
| Tool name | `RefundProcessor` |
| Type | `OpenAPI` |
| Description | `Processes refund requests for eligible orders` |
| Authentication type | `Service agent token` |

3. In the **Schema** field, select **YAML** and paste:

```yaml
openapi: "3.0.0"
info:
  title: Refund Processor API
  version: "1.0"
servers:
  - url: https://orders.example.com/api/v1
paths:
  /refunds:
    post:
      operationId: process_refund
      summary: Process a refund for an order
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                order_id:
                  type: string
                reason:
                  type: string
                amount:
                  type: number
              required:
                - order_id
                - reason
      responses:
        "200":
          description: Refund confirmation
          content:
            application/json:
              schema:
                type: object
                properties:
                  refund_id:
                    type: string
                  status:
                    type: string
```

4. Click **Save**.

#### Create Webhook

1. In the top nav click the **Manage** tab (next to Build)
2. In the left Resources panel click **Webhooks**
3. Click **+ Create**

| Field | Value |
|-------|-------|
| Display name | `PaymentGateway` |
| Webhook timeout | `5` |
| Type | `Generic web service` |
| Webhook URL | `https://payments.example.com/api/v1/webhook` |
| Subtype | `Standard` |

Under **Request Headers**:

| Key | Value |
|-----|-------|
| `Authorization` | `Bearer demo-payment-token` |

> Service Account Auth requires a `*.googleapis.com` endpoint and cannot be used with non-Google webhook URLs. Request Headers is used here instead. This produces `authType=API_KEY` in the connector's `toolAuthSummary` and `tool-credentials.json`. Leave all other auth sections empty.

#### Create Data Stores

> **Not supported in current console.** The current "Conversational Agents" console has removed agent-level data store connections. The `GET .../agents/{id}/dataStores` Dialogflow CX API endpoint returns empty for agents created in this console. The `agentKnowledgeBase` object class will produce zero records. Skip this section.

#### Grant IAM Bindings

> **Resource-level IAM not supported for this agent type.** The `getIamPolicy`/`setIamPolicy` endpoints return 404 for agents created in the current "Conversational Agents" console. IAM bindings must be set at the project level. The four bindings below are set **once** for the project and apply to all Dialogflow CX agents via the offline job's project-level fallback (`INHERITED_PROJECT_BINDING`, `confidence=MEDIUM`).
>
> Skip this section for agents 2–4 — the bindings only need to be set once.

```bash
# Run once — applies to all Dialogflow CX agents via project-level fallback
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="user:jnelson@unfinishedlife.org" \
  --role="roles/dialogflow.admin"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="group:ai_platform_users@unfinishedlife.org" \
  --role="roles/dialogflow.viewer"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:cicd-bot@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/dialogflow.reader"

# roles/dialogflow.client for cicd-bot already added in §1.4
# domain:unfinishedlife.org binding not supported at project level (domain bindings require org-level or resource-level)
```

---

### 2.2 Agent 2: HR Assistant

**Purpose**: Employee self-service for HR queries, PTO, benefits.

#### Create the Agent

| Field | Value |
|-------|-------|
| Display name | `hr-assistant` |
| Location | `us-central1` |
| Time zone | `America/New_York` |
| Default language | `en` |

#### Configure Generative Settings (for Guardrail and foundationModel)

1. In Agent Settings → **Generative AI** → **Model**
2. Set **Generative model**: `gemini-1.5-flash`

3. In Agent Settings → **Generative AI** → **Safety Settings**
- **Safety Enforcement**: `BLOCK_MOST`
- **Banned Phrases**:
  - `salary information` (en)
  - `performance reviews` (en)
  - `termination details` (en)
  - `medical records` (en)

#### Create Tools

**Tool 1: EmployeeDirectory**

1. Go to **Manage** → **Tools** → **Create**
2. Fill in the form:

| Field | Value |
|-------|-------|
| Tool name | `EmployeeDirectory` |
| Type | `OpenAPI` |
| Description | `Search employee directory by name or department` |
| Authentication type | `Service agent token` |

3. In the **Schema** field, select **YAML** and paste:

```yaml
openapi: "3.0.0"
info:
  title: Employee Directory API
  version: "1.0"
servers:
  - url: https://hr.example.com/api/v1
paths:
  /employees:
    get:
      operationId: search_employees
      summary: Search employee directory by name or department
      parameters:
        - name: query
          in: query
          required: false
          schema:
            type: string
        - name: department
          in: query
          required: false
          schema:
            type: string
      responses:
        "200":
          description: List of matching employees
          content:
            application/json:
              schema:
                type: array
                items:
                  type: object
                  properties:
                    employee_id:
                      type: string
                    name:
                      type: string
                    department:
                      type: string
```

4. Click **Save**.

**Tool 2: PTOBalance**

1. Go to **Manage** → **Tools** → **Create**
2. Fill in the form:

| Field | Value |
|-------|-------|
| Tool name | `PTOBalance` |
| Type | `OpenAPI` |
| Description | `Check PTO balance and submit time-off requests` |
| Authentication type | `Service agent token` |

3. In the **Schema** field, select **YAML** and paste:

```yaml
openapi: "3.0.0"
info:
  title: PTO Balance API
  version: "1.0"
servers:
  - url: https://hr.example.com/api/v1
paths:
  /employees/{employee_id}/pto:
    get:
      operationId: get_pto_balance
      summary: Get PTO balance for an employee
      parameters:
        - name: employee_id
          in: path
          required: true
          schema:
            type: string
      responses:
        "200":
          description: PTO balance
          content:
            application/json:
              schema:
                type: object
                properties:
                  employee_id:
                    type: string
                  available_days:
                    type: number
                  used_days:
                    type: number
```

4. Click **Save**.

#### Create Webhook

1. In the top nav click the **Manage** tab
2. In the left Resources panel click **Webhooks**
3. Click **+ Create**

First, create a Secret Manager secret for the OAuth client secret:

```bash
echo -n "workday-demo-secret" | gcloud secrets create workday-oauth-client-secret \
  --project=$PROJECT_ID \
  --data-file=- \
  --replication-policy=automatic

# Note the secret version path — you'll need it below:
# projects/gen-lang-client-0559379892/secrets/workday-oauth-client-secret/versions/1
```

Then fill in the webhook form:

| Field | Value |
|-------|-------|
| Display name | `WorkdaySync` |
| Webhook timeout | `5` |
| Type | `Generic web service` |
| Webhook URL | `https://hr-integrations.example.com/workday/webhook` |
| Subtype | `Standard` |

Under **Third-party OAuth**:

| Field | Value |
|-------|-------|
| Client ID | `workday-client-id` |
| Client secret (Secret version) | `projects/gen-lang-client-0559379892/secrets/workday-oauth-client-secret/versions/1` |
| OAuth Endpoint URL | `https://accounts.example.com/oauth/token` |
| OAuth Scopes | `workday.read` |

> Leave all other auth sections empty. This is the only webhook configured with OAuth — it produces `authType=OAUTH` in the connector's `toolAuthSummary` and `tool-credentials.json`, alongside the `authType=API_KEY` entry from `PaymentGateway` and `authType=NONE` entries from `ServiceNowAPI` and `SalesforceSync`.

#### Create Data Stores

> **Not supported in current console.** Skip this section — see customer-service-bot note above.

#### Grant IAM Bindings

> **Skip.** Resource-level IAM not supported for this agent type. Project-level bindings set in §2.1 apply to all CX agents. No action needed here.

---

### 2.3 Agent 3: IT Help Desk

**Purpose**: IT support ticket creation, knowledge base search, asset lookup.

> **Prerequisite for KBSearch tool**: Create a GCS bucket with a dummy file. This is used as the data source for the `KBSearch` Data store tool.
>
> ```bash
> gsutil mb -p $PROJECT_ID -l us-central1 gs://${PROJECT_ID}-demo-data
> echo "IT knowledge base demo content." > /tmp/demo.txt
> gsutil cp /tmp/demo.txt gs://${PROJECT_ID}-demo-data/demo.txt
> ```

#### Create the Agent

| Field | Value |
|-------|-------|
| Display name | `it-helpdesk` |
| Location | `us-central1` |
| Time zone | `America/Chicago` |
| Default language | `en` |

#### Configure Generative Settings (for Guardrail and foundationModel)

1. In Agent Settings → **Generative AI** → **Model**
2. Set **Generative model**: `gemini-1.5-flash`

3. In Agent Settings → **Generative AI** → **Safety Settings**
- **Safety Enforcement**: `BLOCK_FEW`
- **Banned Phrases**:
  - `admin passwords` (en)
  - `root access` (en)
  - `bypass security` (en)

#### Create Tools

**Tool 1: TicketCreate**

1. Go to **Manage** → **Tools** → **Create**
2. Fill in the form:

| Field | Value |
|-------|-------|
| Tool name | `TicketCreate` |
| Type | `OpenAPI` |
| Description | `Create IT support tickets` |
| Authentication type | `Service agent token` |

3. In the **Schema** field, select **YAML** and paste:

```yaml
openapi: "3.0.0"
info:
  title: IT Ticketing API
  version: "1.0"
servers:
  - url: https://itsm.example.com/api/v1
paths:
  /tickets:
    post:
      operationId: create_ticket
      summary: Create an IT support ticket
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                title:
                  type: string
                description:
                  type: string
                priority:
                  type: string
                  enum: [low, medium, high, critical]
                category:
                  type: string
              required:
                - title
                - description
      responses:
        "201":
          description: Ticket created
          content:
            application/json:
              schema:
                type: object
                properties:
                  ticket_id:
                    type: string
                  status:
                    type: string
```

4. Click **Save**.

**Tool 2: KBSearch**

1. Go to **Tools** → **Create**
2. Fill in the form:

| Field | Value |
|-------|-------|
| Tool name | `KBSearch` |
| Type | `Data store` |
| Description | `Search IT knowledge base articles` |

3. Under **Data stores**, click **Create data store**
4. Select **Cloud Storage (unstructured data)**
5. Point at `gs://${PROJECT_ID}-demo-data/demo.txt` (create this bucket and file first if not done)
6. Complete the wizard — the data store ID will be auto-generated (e.g. `KBSearch_<timestamp>`)
7. Click **Save** on the tool

> The data store tool appears in `GET .../agents/{id}/tools` with `toolType: "CUSTOMIZED_TOOL"` and a `dataStoreSpec.dataStoreConnections[0].dataStoreType: "UNSTRUCTURED"` field. The connector maps this to `toolType = "DATA_STORE_TOOL"` in the `agentTool` object. The `GET .../agents/{id}/dataStores` endpoint remains empty — `agentKnowledgeBase` returns zero records regardless.

**Tool 3: AssetLookup**

1. Go to **Manage** → **Tools** → **Create**
2. Fill in the form:

| Field | Value |
|-------|-------|
| Tool name | `AssetLookup` |
| Type | `OpenAPI` |
| Description | `Look up IT assets by serial number or employee` |
| Authentication type | `Service agent token` |

3. In the **Schema** field, select **YAML** and paste:

```yaml
openapi: "3.0.0"
info:
  title: Asset Management API
  version: "1.0"
servers:
  - url: https://assets.example.com/api/v1
paths:
  /assets:
    get:
      operationId: lookup_asset
      summary: Look up IT assets by serial number or employee
      parameters:
        - name: serial_number
          in: query
          required: false
          schema:
            type: string
        - name: employee_email
          in: query
          required: false
          schema:
            type: string
      responses:
        "200":
          description: Asset details
          content:
            application/json:
              schema:
                type: object
                properties:
                  asset_id:
                    type: string
                  type:
                    type: string
                  assigned_to:
                    type: string
```

4. Click **Save**.

#### Create Webhook

1. In the top nav click the **Manage** tab
2. In the left Resources panel click **Webhooks**
3. Click **+ Create**

| Field | Value |
|-------|-------|
| Display name | `ServiceNowAPI` |
| Webhook timeout | `5` |
| Type | `Generic web service` |
| Webhook URL | `https://example.service-now.com/api/now/table/incident` |
| Subtype | `Standard` |

> Leave all auth sections empty. This produces `authType=NONE` in the connector output.

#### Create Data Store

> **Not supported in current console.** Skip this section — see customer-service-bot note above.

#### Grant IAM Bindings

> **Skip.** Resource-level IAM not supported for this agent type. Project-level bindings set in §2.1 apply to all CX agents. No action needed here.

---

### 2.4 Agent 4: Sales Qualification Bot

**Purpose**: Lead qualification, CRM integration, competitor intelligence.

#### Create the Agent

| Field | Value |
|-------|-------|
| Display name | `sales-qualification-bot` |
| Location | `us-central1` |
| Time zone | `America/Los_Angeles` |
| Default language | `en` |

#### Configure Generative Settings (for Guardrail and foundationModel)

1. In Agent Settings → **Generative AI** → **Model**
2. Set **Generative model**: `gemini-1.5-flash`

3. In Agent Settings → **Generative AI** → **Safety Settings**
- **Safety Enforcement**: `BLOCK_SOME`
- **Banned Phrases**:
  - `guaranteed ROI` (en)
  - `promise delivery` (en)
  - `competitor weaknesses` (en)

#### Create Tools

**Tool 1: LeadScoring**

1. Go to **Manage** → **Tools** → **Create**
2. Fill in the form:

| Field | Value |
|-------|-------|
| Tool name | `LeadScoring` |
| Type | `OpenAPI` |
| Description | `Score leads based on qualification criteria` |
| Authentication type | `Service agent token` |

3. In the **Schema** field, select **YAML** and paste:

```yaml
openapi: "3.0.0"
info:
  title: Lead Scoring API
  version: "1.0"
servers:
  - url: https://sales.example.com/api/v1
paths:
  /leads/score:
    post:
      operationId: score_lead
      summary: Score a lead based on qualification criteria
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                company_name:
                  type: string
                company_size:
                  type: integer
                industry:
                  type: string
                budget:
                  type: number
                timeline:
                  type: string
              required:
                - company_name
      responses:
        "200":
          description: Lead score
          content:
            application/json:
              schema:
                type: object
                properties:
                  score:
                    type: integer
                  tier:
                    type: string
```

4. Click **Save**.

**Tool 2: CRMIntegration**

1. Go to **Manage** → **Tools** → **Create**
2. Fill in the form:

| Field | Value |
|-------|-------|
| Tool name | `CRMIntegration` |
| Type | `OpenAPI` |
| Description | `Create and update CRM records` |
| Authentication type | `Service agent token` |

3. In the **Schema** field, select **YAML** and paste:

```yaml
openapi: "3.0.0"
info:
  title: CRM Integration API
  version: "1.0"
servers:
  - url: https://crm.example.com/api/v1
paths:
  /records:
    post:
      operationId: upsert_crm_record
      summary: Create or update a CRM record
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                record_type:
                  type: string
                  enum: [lead, contact, opportunity]
                data:
                  type: object
              required:
                - record_type
                - data
      responses:
        "200":
          description: Record upserted
          content:
            application/json:
              schema:
                type: object
                properties:
                  record_id:
                    type: string
                  record_type:
                    type: string
```

4. Click **Save**.

#### Create Webhook

1. In the top nav click the **Manage** tab
2. In the left Resources panel click **Webhooks**
3. Click **+ Create**

| Field | Value |
|-------|-------|
| Display name | `SalesforceSync` |
| Webhook timeout | `5` |
| Type | `Generic web service` |
| Webhook URL | `https://salesforce.example.com/services/data/v58.0/sobjects/` |
| Subtype | `Standard` |

> Leave all auth sections empty. This produces `authType=NONE` in the connector output.

#### Create Data Store

> **Not supported in current console.** Skip this section — see customer-service-bot note above.

#### Grant IAM Bindings

> **Skip.** Resource-level IAM not supported for this agent type. Project-level bindings set in §2.1 apply to all CX agents. No action needed here.

---

## Part 3: Vertex AI Agent Engine Agents

These agents use `reasoningEngines` API and demonstrate `serviceAccount` + `agentFramework` attributes.

> **Note on `foundationModel`:** The connector's `parseReasoningEngineNode` hardcodes `foundationModel = null` for Vertex AI Agent Engine agents. The Vertex AI reasoningEngine API does not expose the model as a top-level API field — it is embedded in the agent's deployed code. The `foundationModel` attribute will always be null for Vertex AI agents regardless of what model the agent uses internally. Only Dialogflow CX agents populate `foundationModel` (via `generativeSettings.generativeModel`).

> **Note on `serviceAccount`:** `deploymentSpec.serviceAccount` is not a valid field in the v1 API and will be rejected. The API does return `spec.effectiveIdentity` — a GCP-managed service account auto-assigned at creation (e.g. `service-273041378232@gcp-sa-aiplatform-re.iam.gserviceaccount.com`). The connector currently reads `deploymentSpec.serviceAccount` which will always be null. A future enhancement could read `spec.effectiveIdentity` instead.

> **Note on resource names:** The reasoning engine resource name uses the project **number** (e.g. `projects/273041378232/...`), not the project ID. Use the project number in IAM REST calls.

### 3.1 Agent 5: Research Assistant (LangGraph)

**Purpose**: Research synthesis using LangGraph framework.

#### Create the Agent

```bash
# Create research-assistant reasoning engine
# Note: deploymentSpec.serviceAccount is not supported in v1 API — serviceAccount attribute will be null
curl -s -X POST \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "x-goog-user-project: $PROJECT_ID" \
  -H "Content-Type: application/json" \
  "https://us-central1-aiplatform.googleapis.com/v1/projects/${PROJECT_ID}/locations/us-central1/reasoningEngines" \
  -d '{
    "displayName": "research-assistant",
    "description": "Research synthesis agent using LangGraph for multi-step reasoning",
    "spec": {
      "agentFramework": "langgraph"
    }
  }' | python3 -m json.tool
```

Wait for the operation to complete (poll until `done: true`):

```bash
# Replace with the operation name from the response above
curl -s \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "x-goog-user-project: $PROJECT_ID" \
  "https://us-central1-aiplatform.googleapis.com/v1/<operation-name>" \
  | python3 -m json.tool
```

Get the reasoning engine resource name:

```bash
curl -s \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "x-goog-user-project: $PROJECT_ID" \
  "https://us-central1-aiplatform.googleapis.com/v1/projects/${PROJECT_ID}/locations/us-central1/reasoningEngines" \
  | python3 -c "import sys,json; [print(e['name'], e.get('displayName')) for e in json.load(sys.stdin).get('reasoningEngines',[])]"
```

#### Grant IAM Bindings

```bash
# Replace with full resource name: projects/.../locations/us-central1/reasoningEngines/...
RE_FULL="<research-assistant-full-resource-name>"
TOKEN=$(gcloud auth print-access-token)

curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "x-goog-user-project: $PROJECT_ID" \
  -H "Content-Type: application/json" \
  "https://us-central1-aiplatform.googleapis.com/v1/${RE_FULL}:setIamPolicy" \
  -d '{
    "policy": {
      "bindings": [
        {
          "role": "roles/aiplatform.user",
          "members": [
            "user:jnelson@unfinishedlife.org",
            "group:ai_platform_users@unfinishedlife.org",
            "domain:unfinishedlife.org"
          ]
        },
        {
          "role": "roles/aiplatform.viewer",
          "members": [
            "serviceAccount:cicd-bot@gen-lang-client-0559379892.iam.gserviceaccount.com"
          ]
        }
      ]
    }
  }' | python3 -m json.tool
```

---

### 3.2 Agent 6: Code Review Bot (Google ADK)

**Purpose**: Automated code review using Google's Agent Development Kit.

#### Create the Agent

```bash
curl -s -X POST \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "x-goog-user-project: $PROJECT_ID" \
  -H "Content-Type: application/json" \
  "https://us-central1-aiplatform.googleapis.com/v1/projects/${PROJECT_ID}/locations/us-central1/reasoningEngines" \
  -d '{
    "displayName": "code-review-bot",
    "description": "Automated code review agent using Google ADK with security and style checks",
    "spec": {
      "agentFramework": "google-adk"
    }
  }' | python3 -m json.tool
```

Wait for the operation to complete, then get the resource name (same commands as §3.1).

> Both reasoning engines will have `spec.effectiveIdentity: service-273041378232@gcp-sa-aiplatform-re.iam.gserviceaccount.com` — a single GCP-managed SA shared across all reasoning engines in the project. This is not the same as a user-configured `deploymentSpec.serviceAccount`. The connector reads `deploymentSpec.serviceAccount` (null) not `effectiveIdentity`, so `serviceAccount` remains null on both `agentTool` objects.

#### Grant IAM Bindings

```bash
# Replace with full resource name for code-review-bot (uses project number, not project ID)
RE_FULL="<code-review-bot-full-resource-name>"
TOKEN=$(gcloud auth print-access-token)

curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "x-goog-user-project: $PROJECT_ID" \
  -H "Content-Type: application/json" \
  "https://us-central1-aiplatform.googleapis.com/v1/${RE_FULL}:setIamPolicy" \
  -d '{
    "policy": {
      "bindings": [
        {
          "role": "roles/aiplatform.admin",
          "members": ["user:jnelson@unfinishedlife.org"]
        },
        {
          "role": "roles/aiplatform.user",
          "members": [
            "group:ai_platform_users@unfinishedlife.org",
            "serviceAccount:cicd-bot@gen-lang-client-0559379892.iam.gserviceaccount.com"
          ]
        },
        {
          "role": "roles/aiplatform.viewer",
          "members": ["domain:unfinishedlife.org"]
        }
      ]
    }
  }' | python3 -m json.tool
```

---

## Part 4: Expected Connector Discovery Results

After creating all resources, the connector should discover:

### 4.1 Agents (6 total)

| __UID__ | __NAME__ | agentFramework | serviceAccount | toolIds | knowledgeBaseIds |
|---------|----------|----------------|----------------|---------|------------------|
| `projects/.../agents/customer-service-bot` | customer-service-bot | null | null | [OrderLookup, RefundProcessor, code-interpreter, PaymentGateway] | [] |
| `projects/.../agents/hr-assistant` | hr-assistant | null | null | [EmployeeDirectory, PTOBalance, code-interpreter, WorkdaySync] | [] |
| `projects/.../agents/it-helpdesk` | it-helpdesk | null | null | [TicketCreate, KBSearch, AssetLookup, code-interpreter, ServiceNowAPI] | [] |
| `projects/.../agents/sales-qualification-bot` | sales-qualification-bot | null | null | [LeadScoring, CRMIntegration, code-interpreter, SalesforceSync] | [] |
| `projects/.../reasoningEngines/research-assistant` | research-assistant | langgraph | null | [] | [] |
| `projects/.../reasoningEngines/code-review-bot` | code-review-bot | google-adk | null | [] | [] |

> `knowledgeBaseIds` is empty for all agents — the `GET .../agents/{id}/dataStores` endpoint returns empty in the current console. Data store tools (like `KBSearch`) appear in `toolIds` with `toolType=DATA_STORE_TOOL` instead. `serviceAccount` is null for Vertex AI agents — `deploymentSpec` is not supported in the v1 API.

### 4.2 Tools (17 total)

> **Confirmed via REST API**: The Dialogflow CX API returns `toolType: "CUSTOMIZED_TOOL"` for OpenAPI-type tools regardless of the UI label. The `openApiSpec` field is present in the response alongside `toolType`. Dialogflow CX also auto-creates a `code-interpreter` built-in tool (`toolType: "BUILTIN_TOOL"`) on every agent — this will appear in the connector's `agentTool` output for each CX agent.

| __UID__ | __NAME__ | toolType | agentId | endpoint |
|---------|----------|----------|---------|----------|
| `.../tools/OrderLookup` | OrderLookup | CUSTOMIZED_TOOL | customer-service-bot | null |
| `.../tools/RefundProcessor` | RefundProcessor | CUSTOMIZED_TOOL | customer-service-bot | null |
| `.../tools/code-interpreter` | code-interpreter | BUILTIN_TOOL | customer-service-bot | null |
| `.../webhooks/PaymentGateway` | PaymentGateway | WEBHOOK | customer-service-bot | https://payments.example.com/... |
| `.../tools/EmployeeDirectory` | EmployeeDirectory | CUSTOMIZED_TOOL | hr-assistant | null |
| `.../tools/PTOBalance` | PTOBalance | CUSTOMIZED_TOOL | hr-assistant | null |
| `.../tools/code-interpreter` | code-interpreter | BUILTIN_TOOL | hr-assistant | null |
| `.../webhooks/WorkdaySync` | WorkdaySync | WEBHOOK | hr-assistant | https://hr-integrations.example.com/... |
| `.../tools/TicketCreate` | TicketCreate | CUSTOMIZED_TOOL | it-helpdesk | null |
| `.../tools/KBSearch` | KBSearch | DATA_STORE_TOOL | it-helpdesk | null |
| `.../tools/AssetLookup` | AssetLookup | CUSTOMIZED_TOOL | it-helpdesk | null |
| `.../tools/code-interpreter` | code-interpreter | BUILTIN_TOOL | it-helpdesk | null |
| `.../webhooks/ServiceNowAPI` | ServiceNowAPI | WEBHOOK | it-helpdesk | https://example.service-now.com/... |
| `.../tools/LeadScoring` | LeadScoring | CUSTOMIZED_TOOL | sales-qualification-bot | null |
| `.../tools/CRMIntegration` | CRMIntegration | CUSTOMIZED_TOOL | sales-qualification-bot | null |
| `.../tools/code-interpreter` | code-interpreter | BUILTIN_TOOL | sales-qualification-bot | null |
| `.../webhooks/SalesforceSync` | SalesforceSync | WEBHOOK | sales-qualification-bot | https://salesforce.example.com/... |

### 4.3 Knowledge Bases / Data Stores (0 — surfaced via agentTool instead)

> **Architecture change confirmed via REST API.** The Dialogflow CX `GET .../agents/{id}/dataStores` endpoint returns empty for all agents. Agent-level data store connections no longer exist as a separate API resource.
>
> Data store tools ARE discoverable — through `GET .../agents/{id}/tools`. A Data store tool appears with `toolType: "CUSTOMIZED_TOOL"` and a `dataStoreSpec` field containing `dataStoreConnections[].dataStoreType`. The connector's `parseToolNode` correctly handles this: it checks for `dataStoreSpec` presence and sets `toolType = "DATA_STORE_TOOL"` on the resulting `agentTool` object.
>
> **Result:** `agentKnowledgeBase` returns zero records. Data store information is captured in `agentTool` instead, with `toolType = "DATA_STORE_TOOL"`.
>
> **Additional confirmed facts from API:**
> - Data store IDs are auto-generated (e.g. `it-knowledge-base-ds_1776618617369`) — the display name is not the ID
> - Data stores created via the tool wizard land in `locations/us` (multi-region), not `us-central1`
> - `dataStoreType` value is `UNSTRUCTURED` for Cloud Storage (unstructured data) source

### 4.4 Guardrails (0 — not returned by current API)

> **Confirmed via REST API.** The Dialogflow CX agent GET response for agents created in the current "Conversational Agents" console does not include `generativeSettings`, `generativeSafetySettings`, or any safety-related fields. The connector's guardrail synthesis reads `generativeSettings.safetySettings` from the agent GET response — since that field is absent, `agentGuardrail` returns zero records regardless of what safety settings are configured in the console UI.
>
> This was confirmed by calling `GET .../agents/243eb89a-...` directly — the response contains only `name`, `displayName`, `defaultLanguageCode`, `timeZone`, `startFlow`, `advancedSettings`, `genAppBuilderSettings`, and `satisfiesPzi`. No generative AI fields are present.
>
> The safety settings configured in the console (BLOCK_SOME, banned phrases, etc.) are stored but not surfaced via the v3 agent GET endpoint in this console version.

### 4.5 Identity Bindings

**Dialogflow CX Agents — `sourceTag=INHERITED_PROJECT_BINDING`, `confidence=MEDIUM`:**

> Resource-level IAM (`getIamPolicy`) returns 404 for all CX agents in the current console. All bindings are project-level.

| __UID__ | agentId | iamRole | iamMember | scope | confidence |
|---------|---------|---------|-----------|-------|------------|
| `ib-{hash}` | customer-service-bot | roles/dialogflow.admin | user:jnelson@unfinishedlife.org | PROJECT | MEDIUM |
| `ib-{hash}` | customer-service-bot | roles/dialogflow.viewer | group:ai_platform_users@unfinishedlife.org | PROJECT | MEDIUM |
| `ib-{hash}` | customer-service-bot | roles/dialogflow.reader | serviceAccount:cicd-bot@... | PROJECT | MEDIUM |
| `ib-{hash}` | customer-service-bot | roles/dialogflow.client | serviceAccount:cicd-bot@... | PROJECT | MEDIUM |
| ... (similar for other 3 CX agents) | | | | | |

> `domain:unfinishedlife.org` not included — domain members are not supported in project-level IAM bindings.

**Vertex AI Agent Engine — `sourceTag=DIRECT_RESOURCE_BINDING`, `confidence=HIGH`:**

> Resource-level IAM IS supported for reasoning engines — `setIamPolicy` succeeds. These produce `DIRECT_RESOURCE_BINDING` records.

| __UID__ | agentId | iamRole | iamMember | scope | confidence |
|---------|---------|---------|-----------|-------|------------|
| `ib-{hash}` | research-assistant | roles/aiplatform.user | user:jnelson@unfinishedlife.org | AGENT_RESOURCE | HIGH |
| `ib-{hash}` | research-assistant | roles/aiplatform.user | group:ai_platform_users@unfinishedlife.org | AGENT_RESOURCE | HIGH |
| `ib-{hash}` | research-assistant | roles/aiplatform.user | domain:unfinishedlife.org | AGENT_RESOURCE | HIGH |
| `ib-{hash}` | research-assistant | roles/aiplatform.viewer | serviceAccount:cicd-bot@... | AGENT_RESOURCE | HIGH |
| ... (similar for code-review-bot) | | | | | |

**Service Account IAM Bindings:**

| __UID__ | scope (SA resource) | iamRole | iamMember | kind |
|---------|---------------------|---------|-----------|------|
| `...shared-agent-sa:roles/iam.serviceAccountUser:user:jnelson@...` | projects/.../serviceAccounts/shared-agent-sa@... | roles/iam.serviceAccountUser | user:jnelson@unfinishedlife.org | DIRECT |
| `...shared-agent-sa:roles/iam.serviceAccountUser:group:ai_platform_users@...` | projects/.../serviceAccounts/shared-agent-sa@... | roles/iam.serviceAccountUser | group:ai_platform_users@unfinishedlife.org | GROUP |
| `...shared-agent-sa:roles/iam.serviceAccountTokenCreator:serviceAccount:cicd-bot@...` | projects/.../serviceAccounts/shared-agent-sa@... | roles/iam.serviceAccountTokenCreator | serviceAccount:cicd-bot@... | SERVICE_ACCOUNT |
| `...cicd-bot:roles/iam.serviceAccountAdmin:group:ai_platform_users@...` | projects/.../serviceAccounts/cicd-bot@... | roles/iam.serviceAccountAdmin | group:ai_platform_users@unfinishedlife.org | GROUP |
| `...sales-agent-sa:roles/iam.serviceAccountUser:domain:unfinishedlife.org` | projects/.../serviceAccounts/sales-agent-sa@... | roles/iam.serviceAccountUser | domain:unfinishedlife.org | DOMAIN |

### 4.6 Service Accounts (3 total)

| __UID__ | __NAME__ (email) | disabled | linkedAgentIds | keyCount |
|---------|------------------|----------|----------------|----------|
| `projects/.../serviceAccounts/shared-agent-sa@...` | shared-agent-sa@...iam.gserviceaccount.com | false | [] | 0 |
| `projects/.../serviceAccounts/cicd-bot@...` | cicd-bot@...iam.gserviceaccount.com | false | [] | 1 |
| `projects/.../serviceAccounts/sales-agent-sa@...` | sales-agent-sa@...iam.gserviceaccount.com | false | [] | 0 |

### 4.7 Tool Credentials (4 total — webhooks only)

Read from `tool-credentials.json` in GCS. One record per webhook. The offline job collects auth metadata from `genericWebService` fields. Non-webhook tools (`CUSTOMIZED_TOOL`, `DATA_STORE_TOOL`, `BUILTIN_TOOL`) are not included.

| __UID__ | agentId | webhookName | endpoint | authType | credentialRef |
|---------|---------|-------------|----------|----------|---------------|
| `tc-{hash}` | customer-service-bot | PaymentGateway | https://payments.example.com/api/v1/webhook | `API_KEY` | api-key |
| `tc-{hash}` | hr-assistant | WorkdaySync | https://hr-integrations.example.com/workday/webhook | `OAUTH` | oauth |
| `tc-{hash}` | it-helpdesk | ServiceNowAPI | https://example.service-now.com/api/now/table/incident | `NONE` | null |
| `tc-{hash}` | sales-qualification-bot | SalesforceSync | https://salesforce.example.com/services/data/v58.0/sobjects/ | `NONE` | null |

**`authType` values explained:**
- `API_KEY` — webhook has static request headers configured (e.g. `Authorization: Bearer ...`); connector reads the header key as `credentialRef`. Note: Service Account Auth (`authType=SERVICE_ACCOUNT`) requires a `*.googleapis.com` endpoint and is not achievable with non-Google webhook URLs.
- `OAUTH` — webhook uses `genericWebService.oauthConfig`; connector reads `clientId` as `credentialRef`
- `NONE` — no auth fields set on the webhook

**`toolAuthSummary` on the agent object class** (live, derived from webhook responses at reconciliation time) mirrors this data as a JSON array of per-tool entries. Each entry has `toolId`, `toolKey`, `toolType`, `authType`, `credentialRef`.

---

## Part 5: Connector Configuration

To discover all the above, configure the connector with:

```json
{
  "projectId": "gen-lang-client-0559379892",
  "location": "us-central1",
  "agentApiFlavor": "dialogflowcx",
  "useWorkloadIdentity": false,
  "serviceAccountKeyJson": "<service-account-key-json>",
  "identityBindingScanEnabled": true,
  "discoverServiceAccounts": true,
  "includeServiceAccountKeys": true,
  "organizationId": "321497704104",
  "useCloudAssetApi": true
}
```

**Note**: With `useCloudAssetApi=true`, the connector will discover both Dialogflow CX and Vertex AI Agent Engine agents across the entire organization, regardless of the `agentApiFlavor` setting.

---

## Summary: Object Class Coverage

| Object Class | Count | Source | Notes |
|--------------|-------|--------|-------|
| `agent` (__ACCOUNT__) | 6 | 4 Dialogflow CX + 2 Vertex AI Agent Engine | `foundationModel` populated for CX only; null for Vertex AI by design |
| `agentTool` | 17 | Tools + webhooks from Dialogflow CX agents (includes 4 auto-created `BUILTIN_TOOL` code-interpreter instances) | |
| `agentKnowledgeBase` | 0 | Not supported in current console — agent-level data store connections removed |
| `agentGuardrail` | 0 | Not returned by current API — `generativeSettings` absent from agent GET response |
| `agentIdentityBinding` | 24+ | IAM bindings — CX agents: `INHERITED_PROJECT_BINDING`/MEDIUM (resource-level IAM returns 404); Vertex AI agents: `DIRECT_RESOURCE_BINDING`/HIGH |
| `serviceAccount` | 3 | Project service accounts | `linkedAgentIds` empty for all — `deploymentSpec` not supported in v1 API |
| `agentToolCredential` | 4 | Webhook auth metadata from offline job (GCS) | Exercises `API_KEY`, `OAUTH`, and `NONE` authTypes (`SERVICE_ACCOUNT` requires `*.googleapis.com` endpoint) |

---

## Cleanup

To remove all resources:

```bash
TOKEN=$(gcloud auth print-access-token)

# List and delete Dialogflow CX agents
AGENTS=$(curl -s \
  -H "Authorization: Bearer $TOKEN" \
  -H "x-goog-user-project: $PROJECT_ID" \
  "https://us-central1-dialogflow.googleapis.com/v3/projects/${PROJECT_ID}/locations/us-central1/agents" \
  | python3 -c "import sys,json; [print(a['name']) for a in json.load(sys.stdin).get('agents',[])]")

for AGENT in $AGENTS; do
  echo "Deleting $AGENT..."
  curl -s -X DELETE \
    -H "Authorization: Bearer $TOKEN" \
    -H "x-goog-user-project: $PROJECT_ID" \
    "https://us-central1-dialogflow.googleapis.com/v3/${AGENT}" | python3 -m json.tool
done

# Delete Vertex AI reasoning engines (actual resource IDs)
for RE_ID in 4487160279267803136 1739401556618379264; do
  echo "Deleting reasoningEngine ${RE_ID}..."
  curl -s -X DELETE \
    -H "Authorization: Bearer $TOKEN" \
    -H "x-goog-user-project: $PROJECT_ID" \
    "https://us-central1-aiplatform.googleapis.com/v1/projects/273041378232/locations/us-central1/reasoningEngines/${RE_ID}" \
    | python3 -m json.tool
done

# Delete service accounts
for SA in shared-agent-sa cicd-bot sales-agent-sa; do
  gcloud iam service-accounts delete ${SA}@${PROJECT_ID}.iam.gserviceaccount.com --quiet
done

# Remove project-level IAM bindings added in §1.4
gcloud projects remove-iam-policy-binding $PROJECT_ID \
  --member="user:jnelson@unfinishedlife.org" \
  --role="roles/dialogflow.admin"

gcloud projects remove-iam-policy-binding $PROJECT_ID \
  --member="group:ai_platform_users@unfinishedlife.org" \
  --role="roles/dialogflow.viewer"

gcloud projects remove-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:cicd-bot@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/dialogflow.reader"

gcloud projects remove-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:cicd-bot@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/dialogflow.client"

# Delete GCS demo bucket
gsutil rm -r gs://${PROJECT_ID}-demo-data

# Delete Secret Manager secret (WorkdaySync OAuth)
gcloud secrets delete workday-oauth-client-secret --project=$PROJECT_ID --quiet
```
---

## Appendix: Actual Resource IDs (gen-lang-client-0559379892)

| Resource | Display Name | Resource ID |
|----------|-------------|-------------|
| Dialogflow CX Agent | customer-service-bot | `243eb89a-abbe-4630-b68b-ee87d6a84741` |
| Dialogflow CX Agent | hr-assistant | `855ba6ff-8156-4e6d-8c53-1081ee98f827` |
| Dialogflow CX Agent | it-helpdesk | `50f00afe-1e05-4e7e-9d30-b09fd3fc3e6c` |
| Dialogflow CX Agent | sales-qualification-bot | `a7d644d9-e13f-4b71-b34a-196d261408ab` |
| Vertex AI Reasoning Engine | research-assistant | `4487160279267803136` |
| Vertex AI Reasoning Engine | code-review-bot | `1739401556618379264` |

Full resource names:
- `projects/gen-lang-client-0559379892/locations/us-central1/agents/<uuid>`
- `projects/273041378232/locations/us-central1/reasoningEngines/<id>`
