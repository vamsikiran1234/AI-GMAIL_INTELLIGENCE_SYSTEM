# AI-Powered Gmail Intelligence Platform

Production-oriented starter for an AI assistant that syncs Gmail, summarizes threads, drafts replies, categorizes email, and answers questions from the user's inbox with source attribution.

## What is included

- Gmail OAuth 2.0 flow and Gmail REST API integration surface
- Incremental Gmail sync design with quota/backoff handling
- Supabase PostgreSQL schema with pgvector support
- Gemini-first AI services with an NVIDIA NIM fallback path
- React + Vite + TypeScript frontend shell
- Architecture and design documentation in `Architecture.md`

## Repository layout

- `apps/api` - Spring Boot 3 / Java 21 backend
- `apps/web` - React / Vite / TypeScript frontend
- `To-Know` - step-by-step explanation files for assessment submission
- `Architecture.md` - system and design document

## Setup

1. Create a Supabase PostgreSQL database and run the migration in `apps/api/src/main/resources/db/migration/V1__init.sql`.
2. Copy `.env.example` to `.env` and fill the values.
3. Start the backend:

```bash
cd apps/api
mvn spring-boot:run
```

4. Start the frontend:

```bash
cd apps/web
npm install
npm run dev
```

## Required environment variables

All required variables are documented in `.env.example`.

## Notes

- Secrets are never committed.
- The backend is designed around Gmail threads as the primary unit of intelligence.
- AI responses include source metadata so answers can be traced back to the email thread(s) that produced them.
