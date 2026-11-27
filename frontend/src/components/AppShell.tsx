import { useEffect, useState, type ReactNode } from 'react';
import { api } from '../lib/api';
import { initials } from '../lib/format';
import { href, navigate, routes } from '../lib/router';
import type { Me, Meta } from '../lib/types';
import { Icon, type IconName } from './Icon';

interface NavItem { path: string; label: string; icon: IconName; owner?: boolean; badge?: number }

export default function AppShell({ me, meta, active, pendingActions, children, onSignOut, onSwitch }: {
  me: Me; meta: Meta | null; active: string; pendingActions: number; children: ReactNode; onSignOut: () => void; onSwitch: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [active]);

  const groups: { title: string; items: NavItem[] }[] = [
    { title: 'Investigate', items: [
      { path: routes.app, label: 'Overview', icon: 'home' },
      { path: routes.investigations, label: 'Investigations', icon: 'search' },
      { path: routes.errors, label: 'Errors', icon: 'alert' },
      { path: routes.actions, label: 'Actions', icon: 'shield', badge: pendingActions },
    ] },
    { title: 'Configure', items: [
      { path: routes.knowledge, label: 'Knowledge base', icon: 'book' },
      { path: routes.schedules, label: 'Scheduled checks', icon: 'clock' },
      { path: routes.integrate, label: 'Integrate', icon: 'plug' },
      { path: routes.settings, label: 'Settings', icon: 'settings', owner: true },
    ] },
  ];

  const health: { label: string; on: boolean; detail: string }[] = meta ? [
    { label: 'AI', on: meta.aiMode === 'ai', detail: meta.aiMode === 'ai' ? (meta.model || 'connected') : 'Offline mode' },
    { label: 'Metrics', on: !!meta.prometheusHost, detail: meta.prometheusHost || 'Not connected' },
    { label: 'Code', on: meta.codeProvider !== 'none', detail: meta.codeProvider === 'none' ? 'Not connected' : meta.codeProvider },
    { label: 'Logs', on: meta.loki, detail: meta.loki ? 'Loki' : 'Off' },
    { label: 'Traces', on: meta.tempo, detail: meta.tempo ? 'Tempo' : 'Off' },
  ] : [];

  return (
    <div className="shell">
      <a className="skip-link" href="#app-main" onClick={(e) => { e.preventDefault(); document.getElementById('app-main')?.focus(); }}>Skip to content</a>
      <header className="topbar">
        <button className="btn btn-ghost btn-icon" aria-label="Open navigation" aria-expanded={open} onClick={() => setOpen(true)}><Icon name="menu" /></button>
        <a className="brand" href={href(routes.app)}><span className="brand-mark"><Icon name="logo" size={16} /></span>PulseAgent</a>
      </header>
      {open && <div className="scrim" onClick={() => setOpen(false)} aria-hidden="true" />}
      <aside className={`sidebar ${open ? 'open' : ''}`} aria-label="Navigation">
        <div className="sidebar-head">
          <a className="brand" href={href(routes.app)}><span className="brand-mark"><Icon name="logo" size={16} /></span>PulseAgent</a>
          <button className="btn btn-ghost btn-icon btn-sm sidebar-close" aria-label="Close navigation" onClick={() => setOpen(false)}><Icon name="x" /></button>
        </div>

        <div className="ws">
          <label className="sr-only" htmlFor="ws-select">Workspace</label>
          {me.workspaces.length > 1 ? (
            <select id="ws-select" className="select ws-select" value={me.workspaceId} onChange={(e) => onSwitch(e.target.value)}>
              {me.workspaces.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          ) : (
            <div className="ws-single"><span className="ws-icon">{initials(me.workspaceName)}</span><span className="truncate">{me.workspaceName}</span></div>
          )}
        </div>

        <nav className="nav">
          {groups.map((g) => (
            <div key={g.title} className="nav-group">
              <div className="nav-title">{g.title}</div>
              {g.items.filter((i) => !i.owner || me.role === 'OWNER').map((i) => (
                <a key={i.path} href={href(i.path)} className={`nav-item ${active === i.path ? 'active' : ''}`} aria-current={active === i.path ? 'page' : undefined}>
                  <Icon name={i.icon} />{i.label}
                  {!!i.badge && <span className="nav-badge" aria-label={`${i.badge} pending`}>{i.badge}</span>}
                </a>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar-foot">
          {meta && (
            <a className="health" href={href(me.role === 'OWNER' ? routes.settings : routes.integrate)} title="Integration status">
              <div className="health-title">Connections</div>
              {health.map((h) => (
                <div key={h.label} className="health-row">
                  <span className={`dot ${h.on ? 'dot-ok' : ''}`} />
                  <span className="health-label">{h.label}</span>
                  <span className="health-detail truncate">{h.detail}</span>
                </div>
              ))}
              {meta.problems.length > 0 && <div className="health-problem"><Icon name="alert" size={13} />{meta.problems[0]}</div>}
            </a>
          )}
          <div className="me">
            <span className="avatar" aria-hidden="true">{initials(me.name)}</span>
            <div className="me-text">
              <div className="truncate strong small">{me.name}</div>
              <div className="tiny muted">{me.role === 'OWNER' ? 'Owner' : 'Member'}</div>
            </div>
            <button className="btn btn-ghost btn-icon btn-sm" aria-label="Sign out" title="Sign out" onClick={onSignOut}><Icon name="logout" /></button>
          </div>
        </div>
      </aside>
      <main id="app-main" className="main" tabIndex={-1}>{children}</main>
    </div>
  );
}

export async function signOut() {
  await api('/api/auth/logout', 'POST').catch(() => {});
  navigate(routes.home, true);
}
