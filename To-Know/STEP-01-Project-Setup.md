# Step Objective

Establish the production-ready foundation for the AI-Powered Gmail Intelligence Platform, including the repo structure, runtime contracts, environment variables, architecture document, and backend/frontend scaffolds.

# Business Understanding

This step creates the base of the product. Before the app can read Gmail or use AI, it needs a reliable structure that developers and reviewers can understand quickly. A strong foundation reduces future mistakes, makes deployment simpler, and shows that the system was designed like a real product instead of a demo.

# Technical Understanding

The repository is organized into a Spring Boot backend, a React frontend, and documentation. The backend owns Gmail OAuth, sync, AI orchestration, and persistence. The frontend owns the user experience for inbox review, thread insight, chat, and draft review. The architecture document describes how Gmail API, Gemini, NVIDIA NIM, Supabase, and N8N interact.

# Theory

This step uses the idea of separation of concerns. Infrastructure, business logic, presentation, and documentation are all separated so the system remains maintainable. It also introduces thread-first email intelligence, where the conversation is treated as the core unit rather than isolated messages.

# Practical Implementation

- Added repository-level documentation for setup and architecture.
- Defined environment variables for Gmail OAuth, Gemini, NVIDIA NIM, Supabase, and frontend API access.
- Created the initial project layout for `apps/api`, `apps/web`, and `To-Know` documentation.
- Added the first architecture explanation with database, AI, and Gmail design decisions.

# Alternative Approach

## Method 1
Build a single monolithic application with mixed UI, business logic, and integration code.

## Method 2
Use a modular monorepo with a dedicated backend, frontend, and documentation layer.

Method 2 was chosen because it is easier to maintain, deploy, and explain in a technical assessment.

# Advantages

- Clear separation between frontend and backend responsibilities
- Easier documentation and review
- Better long-term maintainability
- Easier future deployment to Vercel, Render, and Supabase

# Disadvantages

- More setup files are required
- Initial bootstrapping takes longer than a demo-style prototype

# Future Improvements

- Add automated CI and formatting checks
- Add deployment manifests
- Add more granular operational runbooks
- Expand step-by-step docs for Gmail sync, AI summarization, chat, and deduplication

# Interview Questions

1. Why did you separate the frontend and backend?
   - Because the product has distinct presentation and integration concerns, and the deployment targets are different.

2. Why is thread-first modeling important here?
   - Because summaries, replies, and chat answers need the full conversation context, not isolated message fragments.

3. Why use pgvector instead of a separate vector database?
   - Because Supabase PostgreSQL can store relational data and embeddings together, which simplifies operations.

4. Why reserve N8N for only certain workflows?
   - Because the core business logic should remain in Spring Boot while N8N handles scheduled orchestration.
