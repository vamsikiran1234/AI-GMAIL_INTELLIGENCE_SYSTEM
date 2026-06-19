# Step 6 — N8N Workflows

## Status: COMPLETE ✅

---

## What Was Built

Four N8N workflow JSON files in `/n8n-workflows/`:

| File | Workflow | Schedule | Purpose |
|---|---|---|---|
| `workflow-1-scheduled-sync.json` | Scheduled Gmail Sync | Every 30 min | Auto-syncs all active users |
| `workflow-2-keep-alive.json` | Keep Backend Alive | Every 10 min | Prevents Render sleep |
| `workflow-3-recategorize.json` | Re-categorization | Daily 2 AM | Fix NULL/missing categories |
| `workflow-4-newsletter-pipeline.json` | Newsletter Pipeline | Daily 6 AM | Populate newsletter_item table |

## Spring Boot Changes

- Added `POST /api/internal/sync-user` endpoint (InternalSyncController.java)
- Added `N8N_INTERNAL_SECRET` to AppProperties, application.yml, .env, .env.example
- Protected by shared secret — N8N sends the secret, Spring Boot validates it

---

## How To Import Workflows Into N8N Cloud

### For each workflow file:

1. Open N8N cloud
2. Click **+ New workflow** (or **Workflows** in sidebar)
3. Click the **⋮ menu** (top right) → **Import from file**
4. Select the JSON file from `d:\AI_GMAIL_INTELL_SYS\n8n-workflows\`
5. After import, check each Postgres node has **Supabase Postgres** selected as credential
6. Click **Activate** toggle (top right) to enable the workflow

### Import order:
1. workflow-2-keep-alive.json first (keeps backend alive during testing)
2. workflow-1-scheduled-sync.json
3. workflow-4-newsletter-pipeline.json
4. workflow-3-recategorize.json

---

## N8N Variables Required (Settings → Variables)

| Name | Value |
|---|---|
| `API_BASE_URL` | `https://YOUR-NGROK-URL.ngrok-free.app/api` (local) or `https://your-backend.onrender.com/api` (production) |
| `NGROK_SKIP_WARNING` | `true` |
| `N8N_INTERNAL_SECRET` | Must match `N8N_INTERNAL_SECRET` in `apps/api/.env` |

---

## Workflow Details

### Workflow 1 — Scheduled Gmail Sync
- Runs every 30 minutes
- Queries Supabase for users whose `last_synced_at` is older than 25 minutes
- Calls `POST /api/internal/sync-user` for each user
- Checks response status = "completed"
- Logs errors if sync fails

### Workflow 2 — Keep Backend Alive
- Pings `GET /api/health` every 10 minutes
- Prevents Render free tier from sleeping
- Logs healthy/down status
- **Also works as a monitoring alert**

### Workflow 3 — Daily Re-categorization
- Runs at 2 AM daily
- Finds threads with NULL category or summary (last 7 days)
- Calls `GET /api/threads/{threadId}/summary` per thread
- Waits 2 seconds between calls (rate limit protection)

### Workflow 4 — Newsletter Pipeline
- Runs at 6 AM daily
- Finds users who have newsletter threads in the last 2 days
- Calls `POST /api/assistant/newsletter-digest` per user
- Extracts deduplicated items from response
- Inserts into `newsletter_item` table with `ON CONFLICT DO NOTHING`

---

## After Deployment (Update API_BASE_URL)

When you deploy backend to Render:
1. Go to N8N cloud → Settings → Variables
2. Update `API_BASE_URL` to: `https://your-backend.onrender.com/api`
3. Remove the `NGROK_SKIP_WARNING` variable (not needed for Render)
4. All 4 workflows automatically use the new URL

---

## Actions Required From You

### ⚠️ Import the 4 workflow JSON files into N8N cloud
Files are in: `d:\AI_GMAIL_INTELL_SYS\n8n-workflows\`

### ⚠️ Set credentials in each Postgres node
After import, open each workflow and verify the Postgres nodes have
**Supabase Postgres** selected (the credential you already created).

### ⚠️ Activate workflows in this order:
1. Workflow 2 (keep-alive) — activate first
2. Workflow 1 (scheduled sync) — activate second
3. Workflow 4 (newsletter) — activate third
4. Workflow 3 (recategorize) — activate last

---

## Remaining Steps

### Step 7 — Deploy Frontend to Vercel
### Step 8 — Deploy Backend to Render
### Step 9 — Update N8N API_BASE_URL to production Render URL
### Step 10 — Update Google OAuth redirect URI to production URL
### Step 11 — Final end-to-end test on production
### Step 12 — Final git commit and submission
