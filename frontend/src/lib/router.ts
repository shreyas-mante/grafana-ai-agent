import { useEffect, useState } from 'react';

/** Minimal hash router: the server serves one page, so deep links never hit the backend. */
export function currentPath(): string {
  const h = window.location.hash.replace(/^#/, '');
  return h === '' ? '/' : h.startsWith('/') ? h : '/' + h;
}

export function useRoute(): string {
  const [path, setPath] = useState(currentPath());
  useEffect(() => {
    const on = () => {
      setPath(currentPath());
      window.scrollTo({ top: 0 });
    };
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return path;
}

export function navigate(path: string, replace = false) {
  const url = '#' + path;
  if (replace) window.location.replace(url);
  else window.location.hash = path;
}

export function match(pattern: RegExp, path: string): string[] | null {
  const m = path.match(pattern);
  return m ? m.slice(1) : null;
}

export const routes = {
  home: '/',
  login: '/login',
  signup: '/signup',
  app: '/app',
  investigations: '/app/investigations',
  investigation: (id: string) => `/app/inv/${id}`,
  errors: '/app/errors',
  knowledge: '/app/knowledge',
  schedules: '/app/schedules',
  actions: '/app/actions',
  integrate: '/app/integrate',
  settings: '/app/settings',
};

export const href = (path: string) => '#' + path;
