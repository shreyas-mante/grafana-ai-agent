import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';

/**
 * Loads JSON from the API, optionally polling while the tab is visible.
 * Stale responses from earlier requests are ignored.
 */
export function useApi<T>(path: string | null, opts: { poll?: number | ((data: T | undefined) => number | false) } = {}) {
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(!!path);
  const seq = useRef(0);
  const pollRef = useRef(opts.poll);
  pollRef.current = opts.poll;
  const dataRef = useRef<T | undefined>(undefined);

  const load = useCallback(async () => {
    if (!path) return;
    const my = ++seq.current;
    try {
      const d = await api<T>(path);
      if (my !== seq.current) return;
      dataRef.current = d;
      setData(d);
      setError(null);
    } catch (e: any) {
      if (my !== seq.current) return;
      setError(e.message);
    } finally {
      if (my === seq.current) setLoading(false);
    }
  }, [path]);

  useEffect(() => {
    setLoading(!!path);
    setData(undefined);
    dataRef.current = undefined;
    load();
    let timer: number | undefined;
    const schedule = () => {
      const p = pollRef.current;
      const ms = typeof p === 'function' ? p(dataRef.current) : p;
      if (!ms) return;
      timer = window.setTimeout(async () => {
        if (document.visibilityState === 'visible') await load();
        schedule();
      }, ms);
    };
    schedule();
    return () => {
      seq.current++;
      if (timer) window.clearTimeout(timer);
    };
  }, [path, load]);

  return { data, error, loading, reload: load, setData };
}

/** Wraps an async action with busy state and error capture. */
export function useAction<A extends unknown[], R>(fn: (...args: A) => Promise<R>) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = useCallback(async (...args: A): Promise<R | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await fn(...args);
    } catch (e: any) {
      setError(e.message);
      return undefined;
    } finally {
      setBusy(false);
    }
  }, [fn]);
  return { run, busy, error, setError };
}

export function useDocumentTitle(title: string) {
  useEffect(() => {
    document.title = title ? `${title} · PulseAgent` : 'PulseAgent: AI incident investigator';
  }, [title]);
}
