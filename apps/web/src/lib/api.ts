export type HealthResponse = {
  status: string;
  timestamp: string;
};

export type OAuthStartResponse = {
  authorizationUrl: string;
};

export type SyncStatusResponse = {
  status: string;
  lastSyncedAt: string;
  syncedThreads: number;
  syncedMessages: number;
};

export type ChatResponse = {
  conversationId: string;
  answer: string;
  citations: Array<{
    sourceType: string;
    sourceId: string;
    sender: string;
    sentAt: string;
    snippet: string;
  }>;
};

export type DraftResponse = {
  draftId: string;
  subject: string;
  body: string;
  citations: Array<{
    sourceType: string;
    sourceId: string;
    sender: string;
    sentAt: string;
    snippet: string;
  }>;
};

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Request failed with status ${response.status}`);
  }

  return response.json() as Promise<T>;
}

export async function fetchHealth(): Promise<HealthResponse> {
  return request('/health');
}

export async function startOauth(userId: string): Promise<OAuthStartResponse> {
  return request(`/oauth/google/start?userId=${encodeURIComponent(userId)}`);
}

export async function runSync(userId: string): Promise<SyncStatusResponse> {
  return request('/sync', {
    method: 'POST',
    body: JSON.stringify({ userId }),
  });
}

export async function askAssistant(userId: string, message: string, conversationId?: string): Promise<ChatResponse> {
  return request('/assistant/chat', {
    method: 'POST',
    body: JSON.stringify({ userId, message, conversationId: conversationId ?? '' }),
  });
}

export async function createDraft(userId: string, prompt: string, mode: 'compose' | 'reply', threadId = ''): Promise<DraftResponse> {
  return request('/assistant/draft', {
    method: 'POST',
    body: JSON.stringify({ userId, prompt, mode, threadId }),
  });
}
