import { useEffect, useState } from 'react';
import { api } from './api';
import Login from './pages/Login';
import Investigations from './pages/Investigations';
import InvestigationView from './pages/InvestigationView';
import Errors from './pages/Errors';
import Knowledge from './pages/Knowledge';
import Integrate from './pages/Integrate';

function useHashRoute() {
  const [hash, setHash] = useState(window.location.hash || '#/');
  useEffect(() => {
    const on = () => setHash(window.location.hash || '#/');
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return hash.replace(/^#/, '') || '/';
}

export function go(path: string) {
  window.location.hash = path;
}

const NAV = [
  { path: '/', label: 'Investigations', icon: '⌕' },
  { path: '/errors', label: 'Errors', icon: '⚠' },
  { path: '/knowledge', label: 'Knowledge base', icon: '❏' },
  { path: '/integrate', label: 'Integrate', icon: '⇄' },
];

export default function App() {
  const [authed, setAuthed] = useState<boolean | null>(null);
  const [meta, setMeta] = useState<any>(null);
  const route = useHashRoute();

  useEffect(() => {
    api('/api/auth/me').then(() => setAuthed(true)).catch(() => setAuthed(false));
    const onUnauth = () => setAuthed(false);
    window.addEventListener('pa-unauthorized', onUnauth);
    return () => window.removeEventListener('pa-unauthorized', onUnauth);
  }, []);

  useEffect(() => {
    if (authed) api('/api/meta').then(setMeta).catch(() => {});
  }, [authed]);

  if (authed === null) return <div className="center muted">Loading…</div>;
  if (!authed) return <Login onDone={() => setAuthed(true)} />;

  const invMatch = route.match(/^\/inv\/(inv_[0-9a-f]{16})$/);
  let page;
  if (invMatch) page = <InvestigationView id={invMatch[1]} />;
  else if (route === '/errors') page = <Errors />;
  else if (route === '/knowledge') page = <Knowledge />;
  else if (route === '/integrate') page = <Integrate />;
  else page = <Investigations />;

  const active = invMatch ? '/' : route;

  async function logout() {
    await api('/api/auth/logout', 'POST').catch(() => {});
    setAuthed(false);
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><span className="logo">◉</span> PulseAgent</div>
        <nav>
          {NAV.map((n) => (
            <a key={n.path} href={'#' + n.path} className={active === n.path ? 'nav active' : 'nav'}>
              <span className="nav-icon" aria-hidden="true">{n.icon}</span>{n.label}
            </a>
          ))}
        </nav>
        <div className="sidebar-foot">
          {meta && (
            <div className="status-list">
              <div><span className={meta.aiMode === 'ai' ? 'dot ok' : 'dot warn'} />AI: {meta.aiMode === 'ai' ? meta.model : 'offline mode'}</div>
              <div><span className="dot ok" />Search: {meta.embeddings} embeddings</div>
              <div><span className={meta.codeProvider !== 'none' ? 'dot ok' : 'dot warn'} />Code: {meta.codeProvider}</div>
              <div><span className="dot ok" />Prometheus: {meta.prometheusHost}</div>
            </div>
          )}
          <button className="ghost small" onClick={logout}>Sign out</button>
        </div>
      </aside>
      <main className="main">{page}</main>
    </div>
  );
}
