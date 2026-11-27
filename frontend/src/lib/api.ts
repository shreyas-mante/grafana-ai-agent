export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

/**
 * Same-origin JSON calls. The session lives in an HttpOnly cookie, so JavaScript never sees a
 * key or token. Every request carries the CSRF header the server requires for cookie writes.
 */
export async function api<T = any>(path: string, method = 'GET', body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'pulseagent' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError('Can’t reach the server. Check your connection and try again.', 0);
  }
  if (res.status === 401 && !path.startsWith('/api/auth/')) window.dispatchEvent(new Event('pa-unauthorized'));
  if (res.status === 204) return undefined as T;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const fallback = res.status === 429 ? 'Too many requests. Wait a moment and try again.'
      : res.status === 403 ? 'You don’t have permission to do that.'
      : res.status >= 500 ? 'The server had a problem. Try again in a moment.' : `Request failed (${res.status})`;
    throw new ApiError((data && data.error) || fallback, res.status);
  }
  return data as T;
}
