# Step Objective

Build the first production-shaped slice of the Gmail intelligence platform: Gmail OAuth, Gmail sync, thread storage, AI summarization, chat retrieval, and the React dashboard shell.

# Business Understanding

This step turns the project from a setup exercise into a usable product direction. A reviewer can now see how a user connects Gmail, how email data is stored, how AI uses that data, and how the frontend presents the workflow. This is the part that proves the system is more than a prototype.

# Technical Understanding

The backend is a Spring Boot 3 application using Java 21, JDBC, Flyway migrations, WebClient, and PostgreSQL. Gmail data is synchronized through the Gmail REST API, then stored in Supabase tables designed around threads and messages. AI services are abstracted so Gemini is primary and NVIDIA NIM is a fallback. The frontend is a Vite + React + TypeScript app with Tailwind styling and a product-style dashboard.

# Theory

This step applies thread-first information modeling, retrieval-augmented generation, and incremental synchronization. Gmail history IDs are used to avoid reprocessing the entire mailbox. Embeddings are stored in pgvector so semantic search can be done inside PostgreSQL. The assistant is designed to answer only from retrieved email evidence.

# Practical Implementation

- Created Gmail OAuth endpoints for authorization URL generation and callback exchange.
- Added an API client for Gmail REST requests with pagination and retry handling.
- Built a sync service that supports initial and incremental mailbox sync.
- Stored threads, messages, embeddings, sync state, and chat history in PostgreSQL schema migrations.
- Added AI orchestration for summarization, drafting, classification, chat answers, and fallback model routing.
- Created a polished React dashboard that can connect Gmail, run sync, and ask the assistant questions.

# Alternative Approach

## Method 1
Use a single monolithic service with Gmail, AI, and UI logic mixed together.

## Method 2
Split responsibilities into controllers, services, repositories, API clients, and a separate frontend app.

Method 2 was chosen because it better supports maintainability, reviewability, and future deployment scaling.

# Advantages

- Clear separation between integration, business logic, and presentation
- Easier to explain in an interview or technical review
- Thread-aware design is explicit in both the schema and services
- The app already demonstrates sync, retrieval, summarization, and drafting flows

# Disadvantages

- More code is required upfront
- Some production integrations still require real credentials and cloud setup

# Future Improvements

- Add full backend build automation and CI
- Add send-email and reply-send flows with Gmail thread headers preserved end-to-end
- Add Redis or queue-backed background jobs for heavy sync workloads
- Add more explicit source citation persistence for assistant responses
- Expand newsletter deduplication into a dedicated digest workflow

# Interview Questions

1. Why did you use Gmail history IDs for sync?
   - They let the application process only changes after the initial sync instead of reloading everything.

2. Why are threads first-class in the data model?
   - Because summaries, replies, and chat answers need conversation context rather than isolated message fragments.

3. Why store embeddings in PostgreSQL instead of a separate vector store?
   - It keeps relational data and semantic retrieval in one system, which is simpler for a submission and still production-capable.

4. How do you prevent hallucinations?
   - The assistant is prompted and structured to answer only from retrieved email evidence and to explicitly say when evidence is missing.

5. Why did you choose Spring Boot and Vite?
   - Spring Boot is strong for enterprise API and integration work, while Vite gives a fast, modern frontend workflow.
