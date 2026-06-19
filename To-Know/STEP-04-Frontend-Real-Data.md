# Step 4 — Wire Real Data to Frontend

## Status: COMPLETE ✅

## Files Changed

| File | Change |
|---|---|
| `src/lib/api.ts` | Added `fetchThreads`, `fetchThreadMessages`, `sendDraft` + 5 new types |
| `src/components/CitationList.tsx` | New — renders source citations |
| `src/components/ThreadList.tsx` | New — real paginated thread list from API |
| `src/components/ThreadDetail.tsx` | New — messages in a thread + reply button |
| `src/App.tsx` | Rewritten — real data, OAuth redirect detection, send flow |

## Key Features
- Thread list shows real synced emails with category badges, summaries, message count
- Clicking a thread expands messages with AI summary highlighted
- "Reply with AI" pre-fills draft panel with thread context
- AI chat answer shows source citations (sender, date, snippet)
- Draft shows citations + Send button → calls Gmail API
- OAuth callback auto-populates userId and shows connected email
- Conversation ID persists for follow-up questions
- `threadListKey` bumped after sync to force refresh

## Verified
```
npx tsc --noEmit  →  0 errors
```
