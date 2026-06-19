import { useEffect, useState } from 'react';
import { fetchThreadMessages, type MessageItem } from '../lib/api';

type Props = {
  userId: string;
  threadId: string;
  onReply: (threadId: string) => void;
  onClose: () => void;
};

function formatFull(iso: string) {
  if (!iso) return '';
  return new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export default function ThreadDetail({ userId, threadId, onReply, onClose }: Props) {
  const [messages, setMessages] = useState<MessageItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    if (!threadId) return;
    setLoading(true);
    setError(null);
    fetchThreadMessages(userId, threadId)
      .then((res) => {
        setMessages(res.messages);
        if (res.messages.length > 0) setExpanded(res.messages[res.messages.length - 1].messageId);
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false));
  }, [userId, threadId]);

  return (
    <div className="glass-card rounded-[28px] p-6">
      <div className="mb-5 flex items-center justify-between gap-3">
        <div>
          <p className="label-chip">Thread detail</p>
          <h2 className="section-title mt-2">{messages[0]?.subject || 'Loading…'}</h2>
        </div>
        <div className="flex gap-2">
          <button onClick={() => onReply(threadId)} className="rounded-2xl bg-gold-500 px-4 py-2 text-sm font-semibold text-ink-950 transition hover:bg-gold-400">Reply with AI</button>
          <button onClick={onClose} className="rounded-2xl border border-white/10 bg-white/5 px-4 py-2 text-sm font-semibold text-white transition hover:bg-white/10">✕ Close</button>
        </div>
      </div>

      {loading && <div className="space-y-3">{Array.from({ length: 3 }).map((_, i) => <div key={i} className="h-16 animate-pulse rounded-2xl border border-white/10 bg-ink-950/60" />)}</div>}
      {error && <div className="rounded-2xl border border-red-500/20 bg-red-500/5 p-4 text-sm text-red-300">Failed to load messages: {error}</div>}
      {!loading && !error && messages.length === 0 && <p className="text-sm text-mist-200">No messages found in this thread.</p>}

      <div className="space-y-3">
        {messages.map((msg) => {
          const isExpanded = expanded === msg.messageId;
          return (
            <div key={msg.messageId} className="rounded-2xl border border-white/10 bg-ink-950/60">
              <button onClick={() => setExpanded(isExpanded ? null : msg.messageId)}
                className="flex w-full items-start justify-between gap-3 p-4 text-left">
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-white">{msg.fromAddress || 'Unknown sender'}</p>
                  {msg.snippet && !isExpanded && <p className="mt-0.5 truncate text-xs text-mist-200">{msg.snippet}</p>}
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <span className="text-xs text-mist-200">{formatFull(msg.sentAt)}</span>
                  <span className="text-xs text-mist-200">{isExpanded ? '▲' : '▼'}</span>
                </div>
              </button>
              {isExpanded && (
                <div className="border-t border-white/10 px-4 pb-4 pt-3">
                  {msg.summary && (
                    <div className="mb-3 rounded-xl border border-gold-500/20 bg-gold-500/5 px-3 py-2">
                      <p className="text-xs font-semibold uppercase tracking-wide text-gold-400">AI Summary</p>
                      <p className="mt-1 text-xs leading-5 text-mist-200">{msg.summary}</p>
                    </div>
                  )}
                  <p className="whitespace-pre-wrap text-sm leading-6 text-mist-200">{msg.snippet || '(no content)'}</p>
                  {msg.category && <p className="mt-3 text-xs text-mist-200/60">Category: <span className="text-mist-200">{msg.category}</span></p>}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
