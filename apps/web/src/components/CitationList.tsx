import type { SourceCitation } from '../lib/api';

type Props = {
  citations: SourceCitation[];
};

export default function CitationList({ citations }: Props) {
  if (citations.length === 0) return null;

  return (
    <div className="mt-4 space-y-2">
      <p className="text-xs uppercase tracking-[0.25em] text-mist-200">
        Sources ({citations.length})
      </p>
      {citations.map((c, index) => (
        <div
          key={`${c.sourceId}-${index}`}
          className="rounded-2xl border border-white/10 bg-ink-950/60 px-4 py-3"
        >
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-semibold text-gold-400">{c.sender || 'Unknown sender'}</span>
            {c.sentAt && (
              <span className="text-xs text-mist-200">
                {new Date(c.sentAt).toLocaleDateString(undefined, {
                  month: 'short', day: 'numeric', year: 'numeric',
                })}
              </span>
            )}
            <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-xs text-mist-200">
              {c.sourceType}
            </span>
          </div>
          {c.snippet && (
            <p className="mt-1.5 line-clamp-2 text-xs leading-5 text-mist-200">{c.snippet}</p>
          )}
        </div>
      ))}
    </div>
  );
}
