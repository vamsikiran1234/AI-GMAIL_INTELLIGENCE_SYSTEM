import { useEffect, useState } from 'react';
import { fetchThreads, type ThreadItem } from '../lib/api';

type Props = {
  userId: string;
  selectedThreadId: string | null;
  onSelect: (threadId: string) => void;
};

const CATEGORY_COLORS: Record<string, string> = {
  'NEWSLETTERS': 'border-blue-500/30 text-blue-300',
  'JOB / RECRUITMENT': 'border-green-500/30 text-green-300',
  'FINANCE': 'border-yellow-500/30 text-yellow-300',
  'NOTIFICATIONS': 'border-orange-500/30 text-orange-300',
  'WORK / PROFESSIONAL': 'border-purple-500/30 text-purple-300',
  'PERSONAL': 'border-gold-500/30 text-gold-400',
};

function categoryColor(category: string) {
  return CATEGORY_COLORS[category?.toUpperCase()] ?? 'border-white/10 text-mist-200';
}

function formatDate(iso: string) {
  if (!iso) return '';
  const date = new Date(iso);
  const now = new Date();
  const diffDays = Math.floor((now.getTime() - date.getTime()) / 86_400_000);
  if (diffDays === 0) return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  if (diffDays < 7) return date.toLocaleDateString(undefined, { weekday: 'short' });
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export default function ThreadList({ userId, selectedThreadId, onSelect }: Props) {
  const [threads, setThreads] = useState<ThreadItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pageSize = 20;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  useEffect(() => {
    if (!userId) return;
    setLoading(true);
    setError(null);
    fetchThreads(userId, page, pageSize)
      .then((res) => { setThreads(res.threads); setTotal(res.total); })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false));
  }, [userId, page]);

  if (loading) return (
    <div className="space-y-3">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-20 animate-pulse rounded-3xl border border-white/10 bg-ink-950/60" />
      ))}
    </div>
  );

  if (error) return (
    <div className="rounded-3xl border border-red-500/20 bg-red-500/5 p-5 text-sm text-red-300">
      Failed to load threads: {error}
    </div>
  );

  if (threads.length === 0) return (
    <div className="rounded-3xl border border-white/10 bg-ink-950/60 p-8 text-center">
      <p className="text-sm text-mist-200">No threads yet.</p>
      <p className="mt-1 text-xs text-mist-200/60">Connect Gmail and click <span className="text-gold-400">Run Sync</span> to load your inbox.</p>
    </div>
  );

  return (
    <div className="space-y-3">
      {threads.map((thread) => (
        <article key={thread.threadId} onClick={() => onSelect(thread.threadId)}
          className={`cursor-pointer rounded-3xl border p-5 transition ${selectedThreadId === thread.threadId ? 'border-gold-500/40 bg-gold-500/5' : 'border-white/10 bg-ink-950/60 hover:border-white/20 hover:bg-ink-950/80'}`}>
          <div className="flex items-start justify-between gap-3">
            <h3 className="line-clamp-1 text-sm font-semibold text-white">{thread.subject || '(no subject)'}</h3>
            <span className="shrink-0 text-xs text-mist-200">{formatDate(thread.lastMessageAt)}</span>
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            {thread.category && (
              <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-semibold uppercase tracking-wide ${categoryColor(thread.category)}`}>
                {thread.category}
              </span>
            )}
            <span className="text-xs text-mist-200/60">{thread.messageCount} message{thread.messageCount !== 1 ? 's' : ''}</span>
          </div>
          {thread.summary && <p className="mt-2 line-clamp-2 text-xs leading-5 text-mist-200">{thread.summary}</p>}
        </article>
      ))}
      {totalPages > 1 && (
        <div className="flex items-center justify-between pt-2">
          <button onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}
            className="rounded-2xl border border-white/10 bg-white/5 px-4 py-2 text-xs font-semibold text-white disabled:opacity-40">← Prev</button>
          <span className="text-xs text-mist-200">Page {page + 1} of {totalPages} · {total} threads</span>
          <button onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}
            className="rounded-2xl border border-white/10 bg-white/5 px-4 py-2 text-xs font-semibold text-white disabled:opacity-40">Next →</button>
        </div>
      )}
    </div>
  );
}
