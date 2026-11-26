import { useEffect, useState } from 'react';
import { api } from './api';
import Login from './pages/Login';
import Investigations from './pages/Investigations';
import InvestigationView from './pages/InvestigationView';
import Errors from './pages/Errors';
import Knowledge from './pages/Knowledge';
import Integrate from './pages/Integrate';
import Settings from './pages/Settings';
import Schedules from './pages/Schedules';
import Actions from './pages/Actions';

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

export type Me = { name: string; role: string; workspaceId: string; workspaceName: string; workspaces: { id: string; name: string; role: string }[] };

const NAV = [
  { path: '/', label: 'Investigations', icon: '⌕' },
  { path: '/errors', label: 'Errors', icon: '⚠' },
  { path: '/knowledge', label: 'Knowledge base', icon: '❏' },
  { path: '/schedules', label: 'Scheduled checks', icon: '◷' },
  { path: '/actions', label: 'Actions', icon: '✓' },
  { path: '/integrate', label: 'Integrate', icon: '⇄' },
  { path: '/settings', label: 'Settings', icon: '⚙', owner: true },
];

export default function App() {
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const [meta, setMeta] = useState<any>(null);
  const route = useHashRoute();
  const invite = route.match(/^\/join\/([A-Za-z0-9_-]{20,100})$/)?.[1];

  const loadMe = () => api<Me>('/api/auth/me').then(setMe).catch(() => setMe(null));

  useEffect(() => {
    loadMe();
    const onUnauth = () => setMe(null);
    window.addEventListener('pa-unauthorized', onUnauth);
    return () => window.removeEventListener('pa-unauthorized', onUnauth);
  }, []);

  useEffect(() => {
    if (me) api('/api/meta').then(setMeta).catch(() => {});
  }, [me, route]);

  useEffect(() => {
    if (me && invite) {
      api('/api/auth/join', 'POST', { inviteToken: invite })
        .then(() => { loadMe(); go('/'); })
        .catch((e) => { alert(e.message); go('/'); });
    }
  }, [me, invite]);

  if (me === undefined) return <div className="center muted">Loading…</div>;
  if (me === null) return <Login onDone={loadMe} invite={invite} />;

  const isOwner = me.role === 'OWNER';
  const invMatch = route.match(/^\/inv\/(inv_[0-9a-f]{16})$/);
  let page;
  if (invMatch) page = <InvestigationView id={invMatch[1]} />;
  else if (route === '/errors') page = <Errors />;
  else if (route === '/knowledge') page = <Knowledge />;
  else if (route === '/schedules') page = <Schedules />;
  else if (route === '/actions') page = <Actions isOwner={isOwner} />;
  else if (route === '/integrate') page = <Integrate />;
  else if (route.startsWith('/settings') && isOwner) page = <Settings me={me} onChange={() => api('/api/meta').then(setMeta)} />;
  else page = <Investigations />;
  const active = invMatch ? '/' : route.startsWith('/settings') ? '/settings' : route;

  async function logout() {
    await api('/api/auth/logout', 'POST').catch(() => {});
    setMe(null);
  }

  async function switchTo(id: string) {
    await api('/api/auth/switch', 'POST', { workspaceId: id });
    go('/');
    loadMe();
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><span className="logo">◉</span> PulseAgent</div>
        {me.workspaces.length > 1 ? (
          <select className="ws-select" value={me.workspaceId} onChange={(e) => switchTo(e.target.value)} aria-label="Workspace">
            {me.workspaces.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
          </select>
        ) : <div className="ws-name">{me.workspaceName}</div>}
        <nav>
          {NAV.filter((n) => !n.owner || isOwner).map((n) => (
            <a key={n.path} href={'#' + n.path} className={active === n.path ? 'nav active' : 'nav'}>
              <span className="nav-icon" aria-hidden="true">{n.icon}</span>{n.label}
            </a>
          ))}
        </nav>
        <div className="sidebar-foot">
          {meta && (
            <div className="status-list">
              <div><span className={meta.aiMode === 'ai' ? 'dot ok' : 'dot warn'} />AI: {meta.aiMode === 'ai' ? meta.model : 'offline mode'}</div>
              <div><span className={meta.prometheusHost ? 'dot ok' : 'dot warn'} />Metrics: {meta.prometheusHost || 'not connected'}</div>
              <div><span className={meta.codeProvider !== 'none' ? 'dot ok' : 'dot warn'} />Code: {meta.codeProvider}</div>
              <div><span className={meta.loki ? 'dot ok' : 'dot'} />Logs {meta.loki ? 'on' : 'off'} · <span className={meta.tempo ? 'dot ok' : 'dot'} />Traces {meta.tempo ? 'on' : 'off'}</div>
              {meta.problems?.length > 0 && <div className="error-text small">{meta.problems[0]}</div>}
            </div>
          )}
          <div className="me small muted">{me.name} · {me.role.toLowerCase()}</div>
          <button className="ghost small" onClick={logout}>Sign out</button>
        </div>
      </aside>
      <main className="main">{page}</main>
    </div>
  );
}
