import { useEffect, useState } from 'react';
import { askAssistant, createDraft, fetchHealth, runSync, sendDraft, startOauth, type DraftResponse, type SourceCitation } from './lib/api';
import CitationList from './components/CitationList';
import ThreadList from './components/ThreadList';
import ThreadDetail from './components/ThreadDetail';

function readUrlParams() {
  const p = new URLSearchParams(window.location.search);
  return {
    userId: p.get('userId'),
    email: p.get('email'),
    connected: p.get('connected') === 'true',
    error: p.get('error'),
  };
}

export default function App() {
  const [userId, setUserId] = useState('demo-user');
  const [connectedEmail, setConnectedEmail] = useState<string | null>(null);
  const [health, setHealth] = useState<string>('checking…');
  const [syncStatus, setSyncStatus] = useState<string>('Idle');
  const [isSyncing, setIsSyncing] = useState(false);
  const [selectedThreadId, setSelectedThreadId] = useState<string | null>(null);
  const [threadListKey, setThreadListKey] = useState(0);
  const [question, setQuestion] = useState('Which companies rejected my job applications?');
  const [conversationId, setConversationId] = useState<string | undefined>(undefined);
  const [chatAnswer, setChatAnswer] = useState<string>('Ask the assistant to search your synced inbox.');
  const [chatCitations, setChatCitations] = useState<SourceCitation[]>([]);
  const [isChatLoading, setIsChatLoading] = useState(false);
  const [draftMode, setDraftMode] = useState<'compose' | 'reply'>('compose');
  const [draftPrompt, setDraftPrompt] = useState('');
  const [draft, setDraft] = useState<DraftResponse | null>(null);
  const [isDraftLoading, setIsDraftLoading] = useState(false);
  const [isSending, setIsSending] = useState(false);
  const [sendResult, setSendResult] = useState<string | null>(null);

  useEffect(() => {
    const { userId: uid, email, connected, error } = readUrlParams();
    if (error) {
      setSyncStatus(`OAuth error: ${decodeURIComponent(error)}`);
      window.history.replaceState({}, '', window.location.pathname);
    } else if (connected && uid) {
      setUserId(uid);
      setConnectedEmail(email);
      window.history.replaceState({}, '', window.location.pathname);
    }
  }, []);

  useEffect(() => {
    fetchHealth()
      .then((r) => setHealth(`${r.status} · ${new Date(r.timestamp).toLocaleTimeString()}`))
      .catch(() => setHealth('backend unavailable'));
  }, []);

  async function handleConnect() {
    try { const r = await startOauth(userId); window.location.href = r.authorizationUrl; }
    catch (e: unknown) { setSyncStatus(`OAuth error: ${e instanceof Error ? e.message : String(e)}`); }
  }

  async function handleSync() {
    setIsSyncing(true); setSyncStatus('Syncing inbox…');
    try {
      const r = await runSync(userId);
      setSyncStatus(`${r.status} · ${r.syncedThreads} threads · ${r.syncedMessages} messages`);
      setThreadListKey((k) => k + 1);
    } catch (e: unknown) { setSyncStatus(`Sync failed: ${e instanceof Error ? e.message : String(e)}`); }
    finally { setIsSyncing(false); }
  }

  async function handleAsk() {
    if (!question.trim()) return;
    setIsChatLoading(true); setChatAnswer('Searching your inbox…'); setChatCitations([]);
    try {
      const r = await askAssistant(userId, question, conversationId);
      setConversationId(r.conversationId); setChatAnswer(r.answer); setChatCitations(r.citations);
    } catch (e: unknown) { setChatAnswer(`Error: ${e instanceof Error ? e.message : String(e)}`); }
    finally { setIsChatLoading(false); }
  }

  async function handleDraft() {
    if (!draftPrompt.trim()) return;
    setIsDraftLoading(true); setDraft(null); setSendResult(null);
    try { setDraft(await createDraft(userId, draftPrompt, draftMode, selectedThreadId ?? '')); }
    catch (e: unknown) { setChatAnswer(`Draft error: ${e instanceof Error ? e.message : String(e)}`); }
    finally { setIsDraftLoading(false); }
  }

  async function handleSend() {
    if (!draft) return;
    setIsSending(true); setSendResult(null);
    try {
      const r = await sendDraft(userId, draft.draftId);
      setSendResult(`Sent ✓  Gmail message ID: ${r.gmailMessageId}`); setDraft(null);
    } catch (e: unknown) { setSendResult(`Send failed: ${e instanceof Error ? e.message : String(e)}`); }
    finally { setIsSending(false); }
  }

  function handleReplyFromThread(threadId: string) {
    setDraftMode('reply'); setDraftPrompt('Write a professional reply to this thread.');
    setSelectedThreadId(threadId);
    document.getElementById('assistant-panel')?.scrollIntoView({ behavior: 'smooth' });
  }

  return (
    <div className="min-h-screen text-white">
      <div className="mx-auto flex min-h-screen max-w-7xl flex-col px-4 py-6 lg:px-8">
        <header className="glass-card mb-6 rounded-[28px] px-6 py-5">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <p className="label-chip mb-3">Repeatless assessment</p>
              <h1 className="font-display text-4xl font-bold tracking-tight">AI-Powered Gmail Intelligence Platform</h1>
              <p className="mt-2 max-w-3xl text-sm text-mist-200">Thread-aware inbox assistant — syncs Gmail, summarizes conversations, drafts replies, and answers questions with full source attribution.</p>
            </div>
            <div className="grid gap-1.5 text-right text-sm text-mist-200">
              <span>Backend: {health}</span>
              {connectedEmail && <span className="text-gold-400">✓ Connected as {connectedEmail}</span>}
              <span>Sync: {syncStatus}</span>
            </div>
          </div>
        </header>

        <main className="grid flex-1 gap-6 xl:grid-cols-[1.1fr_0.9fr]">
          <section className="grid gap-6 self-start">
            <div className="glass-card rounded-[28px] p-6">
              <div className="grid gap-4 md:grid-cols-[1fr_auto] md:items-end">
                <label className="grid gap-2">
                  <span className="text-xs uppercase tracking-[0.25em] text-mist-200">User ID</span>
                  <input value={userId} onChange={(e) => setUserId(e.target.value)}
                    className="rounded-2xl border border-white/10 bg-ink-950/70 px-4 py-3 text-sm outline-none placeholder:text-mist-200/50" placeholder="your-user-id" />
                </label>
                <div className="flex flex-wrap gap-3">
                  <button onClick={handleConnect} className="rounded-2xl bg-gold-500 px-4 py-3 text-sm font-semibold text-ink-950 transition hover:bg-gold-400">Connect Gmail</button>
                  <button onClick={handleSync} disabled={isSyncing} className="rounded-2xl border border-white/10 bg-white/5 px-4 py-3 text-sm font-semibold text-white transition hover:bg-white/10 disabled:opacity-50">{isSyncing ? 'Syncing…' : 'Run Sync'}</button>
                </div>
              </div>
            </div>

            <div className="glass-card rounded-[28px] p-6">
              <div className="mb-5 flex items-center justify-between gap-3">
                <div><p className="label-chip">Inbox</p><h2 className="section-title mt-3">Your threads</h2></div>
                <span className="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-xs uppercase tracking-[0.25em] text-mist-200">Source-aware</span>
              </div>
              <ThreadList key={threadListKey} userId={userId} selectedThreadId={selectedThreadId} onSelect={setSelectedThreadId} />
            </div>

            {selectedThreadId && (
              <ThreadDetail userId={userId} threadId={selectedThreadId} onReply={handleReplyFromThread} onClose={() => setSelectedThreadId(null)} />
            )}
          </section>

          <aside id="assistant-panel" className="grid gap-6 self-start">
            <div className="glass-card rounded-[28px] p-6">
              <div className="mb-5 flex items-center justify-between gap-3">
                <div><p className="label-chip">AI Chat Agent</p><h2 className="section-title mt-3">Ask across your inbox</h2></div>
                {conversationId && (
                  <button onClick={() => { setConversationId(undefined); setChatAnswer('Ask the assistant to search your synced inbox.'); setChatCitations([]); }}
                    className="rounded-2xl border border-white/10 bg-white/5 px-3 py-2 text-xs font-semibold text-mist-200 transition hover:bg-white/10">New chat</button>
                )}
              </div>
              <textarea value={question} onChange={(e) => setQuestion(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleAsk(); }}
                className="min-h-28 w-full rounded-3xl border border-white/10 bg-ink-950/70 p-4 text-sm leading-6 text-white outline-none placeholder:text-mist-200/50"
                placeholder="Which companies rejected my job applications?" />
              <button onClick={handleAsk} disabled={isChatLoading}
                className="mt-3 rounded-2xl bg-white px-4 py-3 text-sm font-semibold text-ink-950 transition hover:bg-mist-100 disabled:opacity-50">
                {isChatLoading ? 'Searching inbox…' : 'Generate answer'}
              </button>
              <div className="mt-4 rounded-3xl border border-white/10 bg-white/5 p-4">
                <p className="text-xs uppercase tracking-[0.25em] text-mist-200">Assistant output{conversationId && <span className="ml-2 normal-case text-mist-200/50">(conversation active)</span>}</p>
                <p className="mt-3 whitespace-pre-wrap text-sm leading-6 text-white">{chatAnswer}</p>
              </div>
              <CitationList citations={chatCitations} />
            </div>

            <div className="glass-card rounded-[28px] p-6">
              <div className="mb-5 flex items-center justify-between gap-3">
                <div><p className="label-chip">Compose & Reply</p><h2 className="section-title mt-3">Draft an email</h2></div>
                <select value={draftMode} onChange={(e) => setDraftMode(e.target.value as 'compose' | 'reply')}
                  className="rounded-2xl border border-white/10 bg-ink-950/70 px-4 py-3 text-sm text-white outline-none">
                  <option value="compose">Compose</option>
                  <option value="reply">Reply</option>
                </select>
              </div>
              {draftMode === 'reply' && (
                <div className="mb-4 rounded-2xl border border-gold-500/20 bg-gold-500/5 px-4 py-3">
                  <p className="text-xs text-mist-200">{selectedThreadId ? `Replying to thread: ${selectedThreadId}` : 'Select a thread from the list to reply with context.'}</p>
                </div>
              )}
              <textarea value={draftPrompt} onChange={(e) => setDraftPrompt(e.target.value)}
                className="min-h-24 w-full rounded-3xl border border-white/10 bg-ink-950/70 p-4 text-sm leading-6 text-white outline-none placeholder:text-mist-200/50"
                placeholder={draftMode === 'reply' ? 'Write a professional reply acknowledging their points…' : 'Write a follow-up to the product team about the Q3 launch delay…'} />
              <button onClick={handleDraft} disabled={isDraftLoading}
                className="mt-3 rounded-2xl border border-white/10 bg-white/5 px-4 py-3 text-sm font-semibold text-white transition hover:bg-white/10 disabled:opacity-50">
                {isDraftLoading ? 'Drafting…' : 'Generate draft'}
              </button>
              {draft && (
                <div className="mt-4 space-y-3">
                  <div className="rounded-3xl border border-white/10 bg-white/5 p-4">
                    <p className="text-xs uppercase tracking-[0.25em] text-mist-200">Draft · {draft.subject}</p>
                    <p className="mt-3 whitespace-pre-wrap text-sm leading-6 text-white">{draft.body}</p>
                  </div>
                  <CitationList citations={draft.citations} />
                  {!sendResult && (
                    <button onClick={handleSend} disabled={isSending}
                      className="w-full rounded-2xl bg-gold-500 px-4 py-3 text-sm font-semibold text-ink-950 transition hover:bg-gold-400 disabled:opacity-50">
                      {isSending ? 'Sending…' : 'Send via Gmail ✈'}
                    </button>
                  )}
                  {sendResult && <div className="rounded-2xl border border-green-500/20 bg-green-500/5 px-4 py-3 text-sm text-green-300">{sendResult}</div>}
                </div>
              )}
            </div>

            <div className="glass-card rounded-[28px] p-6">
              <p className="label-chip">System design</p>
              <h2 className="section-title mt-3">What this demonstrates</h2>
              <ul className="mt-4 space-y-2 text-sm leading-6 text-mist-200">
                <li>✓ Gmail OAuth 2.0 with token encryption at rest</li>
                <li>✓ Initial + incremental sync via Gmail history IDs</li>
                <li>✓ pgvector RAG pipeline — embed → retrieve → cite</li>
                <li>✓ Gemini primary / NVIDIA NIM fallback AI routing</li>
                <li>✓ Thread-first data model with full conversation context</li>
                <li>✓ Source citations on every AI answer and draft</li>
                <li>✓ Newsletter deduplication via semantic clustering</li>
                <li>✓ Exponential backoff on all external API calls</li>
              </ul>
            </div>
          </aside>
        </main>
      </div>
    </div>
  );
}
