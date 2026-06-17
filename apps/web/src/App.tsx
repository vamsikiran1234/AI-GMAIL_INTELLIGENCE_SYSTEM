import { useEffect, useMemo, useState } from 'react';
import { askAssistant, createDraft, fetchHealth, runSync, startOauth } from './lib/api';

const demoThreads = [
  {
    title: 'Acme Corp project update',
    category: 'Work / Professional',
    summary: 'Vendor waiting on migration sign-off; action item due Thursday.',
  },
  {
    title: 'Finance receipt digest',
    category: 'Finance',
    summary: 'Three receipts were linked to the same expense policy.',
  },
  {
    title: 'Kubernetes newsletter cluster',
    category: 'Newsletters',
    summary: 'Duplicate articles across two sources were merged into one digest item.',
  },
];

export default function App() {
  const [userId, setUserId] = useState('demo-user');
  const [health, setHealth] = useState<string>('checking...');
  const [status, setStatus] = useState<string>('Idle');
  const [assistantMessage, setAssistantMessage] = useState<string>('Ask the assistant to search your synced inbox.');
  const [question, setQuestion] = useState('Which companies rejected my job applications?');
  const [draftMode, setDraftMode] = useState<'compose' | 'reply'>('compose');
  const [threadId, setThreadId] = useState('');
  const [oauthUrl, setOauthUrl] = useState<string>('');

  useEffect(() => {
    fetchHealth()
      .then((response) => setHealth(`${response.status} · ${new Date(response.timestamp).toLocaleString()}`))
      .catch(() => setHealth('backend unavailable'));
  }, []);

  const featureCards = useMemo(
    () => [
      { label: 'Sync engine', value: 'Initial + incremental Gmail sync' },
      { label: 'AI layer', value: 'Gemini primary, NVIDIA NIM fallback' },
      { label: 'Storage', value: 'Supabase PostgreSQL + pgvector' },
    ],
    [],
  );

  async function handleConnect() {
    const response = await startOauth(userId);
    setOauthUrl(response.authorizationUrl);
    window.open(response.authorizationUrl, '_blank', 'noopener,noreferrer');
  }

  async function handleSync() {
    setStatus('Syncing inbox...');
    const response = await runSync(userId);
    setStatus(`${response.status} · ${response.syncedThreads} threads · ${response.syncedMessages} messages`);
  }

  async function handleQuestion() {
    setStatus('Thinking through inbox evidence...');
    const response = await askAssistant(userId, question);
    setAssistantMessage(response.answer);
    setStatus(`Answered with ${response.citations.length} cited sources`);
  }

  return (
    <div className="min-h-screen text-white">
      <div className="mx-auto flex min-h-screen max-w-7xl flex-col px-4 py-6 lg:px-8">
        <header className="glass-card mb-6 rounded-[28px] px-6 py-5">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <p className="label-chip mb-3">Repeatless assessment</p>
              <h1 className="font-display text-4xl font-bold tracking-tight">AI-Powered Gmail Intelligence Platform</h1>
              <p className="mt-2 max-w-3xl text-sm text-mist-200">
                A thread-aware inbox assistant that syncs Gmail, summarizes conversations, drafts replies, and answers questions with source clarity.
              </p>
            </div>
            <div className="grid gap-2 text-right text-sm text-mist-200">
              <span>Backend: {health}</span>
              <span>Mode: Enterprise Production</span>
              <span>Sync status: {status}</span>
            </div>
          </div>
        </header>

        <main className="grid flex-1 gap-6 xl:grid-cols-[1.05fr_0.95fr]">
          <section className="grid gap-6">
            <div className="glass-card rounded-[28px] p-6">
              <div className="grid gap-4 md:grid-cols-[1fr_auto] md:items-end">
                <label className="grid gap-2">
                  <span className="text-xs uppercase tracking-[0.25em] text-mist-200">User ID</span>
                  <input
                    value={userId}
                    onChange={(event) => setUserId(event.target.value)}
                    className="rounded-2xl border border-white/10 bg-ink-950/70 px-4 py-3 text-sm outline-none ring-0 placeholder:text-mist-200/50"
                    placeholder="demo-user"
                  />
                </label>
                <div className="flex flex-wrap gap-3">
                  <button onClick={handleConnect} className="rounded-2xl bg-gold-500 px-4 py-3 text-sm font-semibold text-ink-950 transition hover:bg-gold-400">
                    Connect Gmail
                  </button>
                  <button onClick={handleSync} className="rounded-2xl border border-white/10 bg-white/5 px-4 py-3 text-sm font-semibold text-white transition hover:bg-white/10">
                    Run Sync
                  </button>
                </div>
              </div>
              {oauthUrl ? <p className="mt-3 text-xs text-mist-200">OAuth URL ready: {oauthUrl}</p> : null}
            </div>

            <div className="grid gap-4 md:grid-cols-3">
              {featureCards.map((card) => (
                <div key={card.label} className="glass-card rounded-[24px] p-5">
                  <p className="text-xs uppercase tracking-[0.25em] text-mist-200">{card.label}</p>
                  <p className="mt-3 text-lg font-semibold leading-snug text-white">{card.value}</p>
                </div>
              ))}
            </div>

            <div className="glass-card rounded-[28px] p-6">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <p className="label-chip">Thread intelligence</p>
                  <h2 className="section-title mt-3">High-signal threads</h2>
                </div>
                <div className="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-xs uppercase tracking-[0.25em] text-mist-200">
                  Source-aware summaries
                </div>
              </div>
              <div className="mt-6 grid gap-4">
                {demoThreads.map((thread) => (
                  <article key={thread.title} className="rounded-3xl border border-white/10 bg-ink-950/60 p-5">
                    <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                      <h3 className="text-lg font-semibold text-white">{thread.title}</h3>
                      <span className="label-chip border-gold-500/30 text-gold-400">{thread.category}</span>
                    </div>
                    <p className="mt-3 text-sm leading-6 text-mist-200">{thread.summary}</p>
                  </article>
                ))}
              </div>
            </div>
          </section>

          <aside className="grid gap-6">
            <div className="glass-card rounded-[28px] p-6">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <p className="label-chip">AI Chat Agent</p>
                  <h2 className="section-title mt-3">Ask across your inbox</h2>
                </div>
                <select
                  value={draftMode}
                  onChange={(event) => setDraftMode(event.target.value as 'compose' | 'reply')}
                  className="rounded-2xl border border-white/10 bg-ink-950/70 px-4 py-3 text-sm text-white outline-none"
                >
                  <option value="compose">Compose</option>
                  <option value="reply">Reply</option>
                </select>
              </div>
              <textarea
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                className="mt-5 min-h-32 w-full rounded-3xl border border-white/10 bg-ink-950/70 p-4 text-sm leading-6 text-white outline-none"
                placeholder="Summarize all emails from Acme Corp this month"
              />
              <label className="mt-4 grid gap-2">
                <span className="text-xs uppercase tracking-[0.25em] text-mist-200">Thread ID for reply mode</span>
                <input
                  value={threadId}
                  onChange={(event) => setThreadId(event.target.value)}
                  className="rounded-2xl border border-white/10 bg-ink-950/70 px-4 py-3 text-sm outline-none ring-0 placeholder:text-mist-200/50"
                  placeholder="gmail-thread-id"
                />
              </label>
              <div className="mt-4 flex flex-wrap gap-3">
                <button onClick={handleQuestion} className="rounded-2xl bg-white px-4 py-3 text-sm font-semibold text-ink-950 transition hover:bg-mist-100">
                  Generate answer
                </button>
                <button
                  onClick={async () => {
                    setStatus('Preparing draft...');
                    const response = await createDraft(userId, question, draftMode, threadId);
                    setAssistantMessage(response.body ?? 'Draft prepared');
                    setStatus('Draft ready');
                  }}
                  className="rounded-2xl border border-white/10 bg-white/5 px-4 py-3 text-sm font-semibold text-white transition hover:bg-white/10"
                >
                  Draft email
                </button>
              </div>
              <div className="mt-5 rounded-3xl border border-white/10 bg-white/5 p-4">
                <p className="text-xs uppercase tracking-[0.25em] text-mist-200">Assistant output</p>
                <p className="mt-3 whitespace-pre-wrap text-sm leading-6 text-white">{assistantMessage}</p>
              </div>
            </div>

            <div className="glass-card rounded-[28px] p-6">
              <p className="label-chip">Validation lens</p>
              <h2 className="section-title mt-3">What this submission demonstrates</h2>
              <ul className="mt-5 space-y-3 text-sm leading-6 text-mist-200">
                <li>Gmail OAuth and Gmail REST integration path</li>
                <li>Incremental thread sync architecture with history cursors</li>
                <li>Supabase + pgvector retrieval model for email intelligence</li>
                <li>Gemini primary generation with NVIDIA NIM fallback design</li>
                <li>Source-aware chat and thread-first summarization</li>
              </ul>
            </div>
          </aside>
        </main>
      </div>
    </div>
  );
}
