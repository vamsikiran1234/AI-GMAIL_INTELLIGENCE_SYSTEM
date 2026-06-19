# Step 6 — N8N Integration Plan

## Status: PLANNED

---

## Why N8N Here

The current system has **zero automation**. Every action is manual:
- Sync only runs when the user clicks "Run Sync"
- newsletter_item table is never written to
- No scheduled re-sync happens
- AI processing blocks the HTTP request thread during sync

N8N fills exactly this gap — it handles scheduled, event-driven, and pipeline work
so Spring Boot stays focused on real-time API logic.

---

## Boundary Decision — What Goes Where

| Responsibility | Owner | Reason |
|---|---|---|
| Gmail OAuth 2.0 | Spring Boot | Requires secure token encryption at rest |
| Real-time chat / RAG | Spring Boot | Needs low latency, direct DB access |
| Compose & Reply | Spring Boot | User-facing, synchronous |
| Thread detail / message fetch | Spring Boot | Real-time UI interaction |
| **Scheduled Gmail Sync** | **N8N** | Cron job, runs every 30 min per user |
| **Email Processing Pipeline** | **N8N** | Calls Spring Boot webhook per new thread |
| **Categorization Trigger** | **N8N** | Re-categorizes stale/uncategorized threads |
| **Newsletter Processing Pipeline** | **N8N** | Extracts, deduplicates, writes newsletter_item |

---

## The 4 N8N Workflows

---

### Workflow 1 — Scheduled Gmail Sync

**Purpose:** Auto-sync every active Gmail connection every 30 minutes without the user pressing a button.

**Trigger:** Cron — every 30 minutes

**Nodes:**

```
[Cron: every 30 min]
    ↓
[Postgres: SELECT user_id FROM gmail_connection
           WHERE last_synced_at < now() - interval '25 minutes'
              OR last_synced_at IS NULL]
    ↓
[Split In Batches: process one user at a time]
    ↓
[HTTP Request: POST http://api:8081/api/sync
               body: { "userId": "{{$json.user_id}}" }]
    ↓
[IF: response.status != "completed"]
    ↓ (error path)
[Postgres: UPDATE gmail_connection SET sync_error = {{error}} WHERE user_id = ...]
```

**What Spring Boot does:** The existing `POST /api/sync` endpoint handles all the actual Gmail API calls and DB writes. N8N just schedules which users to sync.

**Environment variables N8N needs:**
```
API_BASE_URL=http://localhost:8081/api
```

---

### Workflow 2 — Email Processing Pipeline (Post-Sync Hook)

**Purpose:** After sync completes, find threads that don't have an AI summary or category yet and process them through the pipeline.

**Why needed:** Current sync does AI processing inline (blocks HTTP thread for minutes). This workflow decouples it — sync fast, process async.

**Trigger:** Webhook — Spring Boot calls this after `syncMailbox()` completes

**OR** Cron — every 15 minutes, look for unprocessed threads

**Nodes:**

```
[Cron: every 15 min]
    ↓
[Postgres: SELECT DISTINCT user_id, thread_id
           FROM email_thread
           WHERE summary IS NULL OR category IS NULL
           LIMIT 50]
    ↓
[Split In Batches: 5 threads at a time]
    ↓
[HTTP Request: GET http://api:8081/api/threads/{threadId}/summary?userId=...]
    ↓
[Wait: 2 seconds]  ← rate limit protection
    ↓
[Next batch]
```

**Note:** For MVP the current inline processing works. This workflow is an optimization for large inboxes.

---

### Workflow 3 — Categorization Trigger (Re-categorize Stale Emails)

**Purpose:** Periodically find emails where category is NULL or 'PERSONAL' (the fallback) and re-run categorization with fresh AI.

**Trigger:** Cron — once daily at 2 AM

**Nodes:**

```
[Cron: daily at 02:00]
    ↓
[Postgres: SELECT user_id, thread_id FROM email_thread
           WHERE category IS NULL
              OR category = 'PERSONAL'
           AND created_at > now() - interval '7 days'
           LIMIT 100]
    ↓
[Split In Batches]
    ↓
[HTTP Request: GET /api/threads/{threadId}/summary?userId=...]
    ↓
[Postgres: UPDATE email_thread SET category=..., summary=... WHERE ...]
```

---

### Workflow 4 — Newsletter Processing Pipeline

**Purpose:** Identify newsletter threads, extract news items, deduplicate across sources, and populate the `newsletter_item` table which is currently never written to.

**Trigger:** Cron — once daily at 6 AM

**Nodes:**

```
[Cron: daily at 06:00]
    ↓
[Postgres: SELECT user_id, thread_id, subject, summary
           FROM email_thread
           WHERE category = 'NEWSLETTERS'
           AND last_message_at > now() - interval '2 days'
           AND thread_id NOT IN (
               SELECT source_id FROM newsletter_item WHERE digest_date = current_date
           )]
    ↓
[Split In Batches: by user_id]
    ↓
[HTTP Request: POST /api/assistant/newsletter-digest
               body: { "userId": "...", "days": 2 }]
    ↓
[Postgres: INSERT INTO newsletter_item
           (id, user_id, canonical_title, summary, source_names, digest_date)
           VALUES (...) ON CONFLICT DO NOTHING]
```

---

## Implementation Order

| # | Workflow | Complexity | Value | Do First? |
|---|---|---|---|---|
| 1 | Scheduled Gmail Sync | Low | High | ✅ Yes |
| 4 | Newsletter Pipeline | Medium | High | ✅ Yes |
| 3 | Re-categorization | Low | Medium | After 1 |
| 2 | Processing Pipeline | High | Medium | After deploy |

Start with Workflow 1 and Workflow 4 — these directly fill gaps in the submission requirements.

---

## Spring Boot Changes Required

Only **one small change** is needed in Spring Boot to support N8N:

Add an **internal sync webhook endpoint** that N8N can call per-user without auth:

```
POST /api/internal/sync-user
body: { "userId": "...", "secret": "N8N_INTERNAL_SECRET" }
```

This is safer than exposing the existing `/api/sync` endpoint — the internal endpoint checks a shared secret instead of requiring OAuth.

**File to modify:** Add `InternalSyncController.java`

---

## What You Need To Install N8N

### Option A — Run locally (for development)

```bash
# Using Docker
docker run -it --rm \
  --name n8n \
  -p 5678:5678 \
  -v ~/.n8n:/home/node/.n8n \
  docker.n8nio/n8n
```

Then open: http://localhost:5678

### Option B — Cloud (for production)

Use **n8n.cloud** free tier: https://app.n8n.cloud/register

---

## N8N Environment Variables

```
API_BASE_URL=http://localhost:8081/api        # local
# or
API_BASE_URL=https://your-backend.onrender.com/api   # production

N8N_INTERNAL_SECRET=your-random-secret-here

# Supabase direct DB (for Postgres nodes)
DB_HOST=aws-1-ap-southeast-2.pooler.supabase.com
DB_PORT=5432
DB_NAME=postgres
DB_USER=postgres.your-project-ref
DB_PASSWORD=your-db-password
```

---

## Workflow 1 Step-by-Step (Concrete Instructions for You)

This is the highest-value workflow and the easiest to set up.

### Steps you must do manually:

1. **Install N8N** (local Docker or cloud)

2. **Create new workflow** in N8N called "Scheduled Gmail Sync"

3. **Add Cron node**
   - Set to: every 30 minutes
   - Expression: `*/30 * * * *`

4. **Add Postgres node** (Query)
   - Credential: your Supabase connection
   - Query:
     ```sql
     SELECT user_id FROM gmail_connection
     WHERE last_synced_at < now() - interval '25 minutes'
        OR last_synced_at IS NULL
     ```

5. **Add SplitInBatches node**
   - Batch size: 1

6. **Add HTTP Request node**
   - Method: POST
   - URL: `{{ $env.API_BASE_URL }}/sync`
   - Body: `{ "userId": "{{ $json.user_id }}" }`
   - Headers: `Content-Type: application/json`

7. **Add IF node**
   - Condition: `{{ $json.status }}` equals `completed`

8. **Activate the workflow**

---

## After All 4 Workflows Are Set Up

Update `Architecture.md` to show:
- N8N scheduler calling Spring Boot sync endpoint
- N8N newsletter pipeline writing to `newsletter_item` table
- N8N re-categorization loop

---

## Actions Required From You

### ⚠️ 1. Install N8N
Choose local Docker or cloud (n8n.cloud free tier).

### ⚠️ 2. Add Supabase Postgres credential in N8N
- Host: `aws-1-ap-southeast-2.pooler.supabase.com`
- Port: 5432
- Database: postgres
- User: `postgres.ssepodfezrysgesebzlc`
- Password: your password
- SSL: Required

### ⚠️ 3. Set N8N environment variables
See list above.

### ✅ Spring Boot backend change (I will implement)
Add `InternalSyncController` with shared-secret protected webhook for N8N to call.

---

## Recommended Commit Message (after implementation)

```
feat(n8n): add scheduled sync, newsletter pipeline, and re-categorization workflows

- POST /api/internal/sync-user endpoint for N8N webhook calls
- N8N Workflow 1: scheduled Gmail sync every 30 minutes
- N8N Workflow 4: daily newsletter deduplication pipeline
- N8N Workflow 3: daily re-categorization of uncategorized threads
- Architecture.md updated with N8N workflow diagram
```
