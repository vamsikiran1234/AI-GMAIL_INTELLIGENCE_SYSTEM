# Step 5 — .env.example and README

## Status: COMPLETE ✅

## What Was Done

### `.env.example` — Created at project root
- Every variable documented with description and source URL
- No real secrets — all placeholder values
- Covers backend and frontend variables
- Local vs production URL guidance included

### `README.md` — Fully rewritten
- Correct port: 8081
- Correct run command: `java -jar target/gmail-intelligence-api-0.0.1-SNAPSHOT.jar`
- Correct build: `../../mvnw clean package -DskipTests`
- Google Cloud Console + Supabase setup steps
- Full API endpoint reference table
- Environment variable reference table
- Usage flow: connect → sync → browse → chat → draft → send

## Next Step: Deploy

**Frontend → Vercel**
1. Import repo in Vercel, set root directory to `apps/web`
2. Add env var: `VITE_API_BASE_URL=https://your-backend.onrender.com/api`

**Backend → Render**
1. New Web Service, connect repo, root `apps/api`
2. Build: `../../mvnw clean package -DskipTests`
3. Start: `java -jar target/gmail-intelligence-api-0.0.1-SNAPSHOT.jar`
4. Add all env vars with production values
5. Update `GMAIL_OAUTH_REDIRECT_URI` and `FRONTEND_ORIGIN` to production URLs
6. Add production redirect URI to Google Cloud Console
