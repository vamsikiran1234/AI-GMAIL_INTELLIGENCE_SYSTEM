// ─── Response types ───────────────────────────────────────────────────────────

export type HealthResponse = { status: string; timestamp: string };
export type OAuthStartResponse = { authorizationUrl: string };
export type SyncStatusResponse = { status: string; lastSyncedAt: string; syncedThreads: number; syncedMessages: number };

export type SourceCitation = {
  sourceType: string; sourceId: string; sender: string; sentAt: string; snippet: string;
};

export type ChatResponse = { conversationId: string; answer: string; citations: SourceCitation[] };
export type DraftResponse = { draftId: string; subject: string; body: string; citations: SourceCitation[] };
export type SendResponse = { gmailMessageId: string; status: string };

export type ThreadItem = {
  threadId: string; subject: string; category: string;
  summary: string; lastMessageAt: string; messageCount: number;
};
export type ThreadListResponse = { threads: ThreadItem[]; total: number; page: number; pageSize: number };

export type MessageItem = {
  messageId: string; threadId: string; fromAddress: string; subject: string;
  sentAt: string; snippet: string; summary: string; category: string;
};
export type ThreadMessagesResponse = { threadId: string; messages: MessageItem[] };

// ─── Base request ─────────────────────────────────────────────────────────────

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081/api';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Request failed with status ${response.status}`);
  }
  return response.json() as Promise<T>;
}

// ─── API functions ────────────────────────────────────────────────────────────

export const fetchHealth = (): Promise<HealthResponse> => request('/health');
export const startOauth = (userId: string): Promise<OAuthStartResponse> =>
  request(`/oauth/google/start?userId=${encodeURIComponent(userId)}`);
export const runSync = (userId: string): Promise<SyncStatusResponse> =>
  request('/sync', { method: 'POST', body: JSON.stringify({ userId }) });
export const fetchThreads = (userId: string, page = 0, pageSize = 20): Promise<ThreadListResponse> =>
  request(`/threads?userId=${encodeURIComponent(userId)}&page=${page}&pageSize=${pageSize}`);
export const fetchThreadMessages = (userId: string, threadId: string): Promise<ThreadMessagesResponse> =>
  request(`/threads/${encodeURIComponent(threadId)}/messages?userId=${encodeURIComponent(userId)}`);
export const askAssistant = (userId: string, message: string, conversationId?: string): Promise<ChatResponse> =>
  request('/assistant/chat', { method: 'POST', body: JSON.stringify({ userId, message, conversationId: conversationId ?? '' }) });
export const createDraft = (userId: string, prompt: string, mode: 'compose' | 'reply', threadId = ''): Promise<DraftResponse> =>
  request('/assistant/draft', { method: 'POST', body: JSON.stringify({ userId, prompt, mode, threadId }) });
export const sendDraft = (userId: string, draftId: string): Promise<SendResponse> =>
  request('/threads/send', { method: 'POST', body: JSON.stringify({ userId, draftId }) });
