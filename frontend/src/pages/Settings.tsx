import { FormEvent, useEffect, useState } from 'react';
import { api, ago } from '../api';
import type { Me } from '../App';

const GROUPS: { id: string; title: string; test?: string; help: string }[] = [
  { id: 'ai', title: 'AI provider', test: 'ai', help: 'Bring your own key. Gemini and Groq have free tiers; Claude and OpenAI are paid. Leave empty for free offline mode.' },
  { id: 'prometheus', title: 'Prometheus (metrics)', test: 'prometheus', help: 'Grafana Cloud: use the query URL ending in /api/prom, the instance ID as username, and a metrics:read token.' },
  { id: 'loki', title: 'Loki (logs)', test: 'loki', help: 'Optional. Lets the agent search error logs.' },
  { id: 'tempo', title: 'Tempo (traces)', test: 'tempo', help: 'Optional. Lets the agent find slow spans.' },
  { id: 'grafana', title: 'Grafana (dashboards)', test: 'grafana', help: 'Optional. Use a Viewer service account token.' },
  { id: 'code', title: 'Source code (GitHub)', test: 'code', help: 'A fine-grained token with Contents: read and Pull requests: read. The issues write token is only used for actions you approve.' },
  { id: 'notify', title: 'Slack notifications', test: 'slack', help: 'Posts finished investigations to a channel through an incoming webhook.' },
  { id: 'actions', title: 'Remediation webhook', help: 'Approved actions (rollback, restart, scale…) are POSTed here, signed with HMAC-SHA256 in X-PulseAgent-Signature.' },
];

const CHOICES: Record<string, string[]> = {
  'llm.provider': ['none', 'gemini', 'anthropic', 'openai'],
  'notify.on': ['alerts', 'all', 'none'],
};

const MODEL_HINT: Record<string, string> = {
  gemini: 'gemini-2.5-flash', anthropic: 'claude-opus-5-5', openai: 'gpt-4o-mini (or llama-3.3-70b-versatile on Groq)', none: '',
};

function Integrations({ onChange }: { onChange: () => void }) {
  const [defs, setDefs] = useState<any[]>([]);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [msg, setMsg] = useState<Record<string, { ok: boolean; text: string }>>({});
  const [busy, setBusy] = useState('');

  const load = () => api<any[]>('/api/admin/settings').then(setDefs);
  useEffect(() => { load(); }, []);

  const value = (d: any) => (d.name in edits ? edits[d.name] : d.secret ? '' : d.value);

  async function save(group: string, clear?: string) {
    const values: Record<string, string> = {};
    defs.filter((d) => d.group === group).forEach((d) => { if (d.name in edits) values[d.name] = edits[d.name]; });
    if (clear) values[clear] = '';
    if (Object.keys(values).length === 0) return setMsg({ ...msg, [group]: { ok: true, text: 'Nothing to save' } });
    setBusy(group);
    try {
      setDefs(await api<any[]>('/api/admin/settings', 'PUT', { values }));
      const next = { ...edits };
      Object.keys(values).forEach((k) => delete next[k]);
      setEdits(next);
      setMsg({ ...msg, [group]: { ok: true, text: 'Saved' } });
      onChange();
    } catch (e: any) {
      setMsg({ ...msg, [group]: { ok: false, text: e.message } });
    } finally {
      setBusy('');
    }
  }

  async function test(group: string, integration: string) {
    setBusy(group);
    try {
      const r = await api('/api/admin/settings/test/' + integration, 'POST');
      setMsg({ ...msg, [group]: { ok: r.ok, text: r.detail } });
    } catch (e: any) {
      setMsg({ ...msg, [group]: { ok: false, text: e.message } });
    } finally {
      setBusy('');
    }
  }

  const provider = value(defs.find((d) => d.name === 'llm.provider') || {}) || 'none';

  return (
    <div className="grid-cards">
      {GROUPS.map((g) => (
        <form key={g.id} className="card" onSubmit={(e: FormEvent) => { e.preventDefault(); save(g.id); }}>
          <h3>{g.title}</h3>
          <p className="muted small">{g.help}</p>
          {defs.filter((d) => d.group === g.id).map((d) => (
            <label key={d.name}>{d.label}
              {CHOICES[d.name] ? (
                <select value={value(d) || CHOICES[d.name][0]} onChange={(e) => setEdits({ ...edits, [d.name]: e.target.value })}>
                  {CHOICES[d.name].map((c) => <option key={c} value={c}>{c}</option>)}
                </select>
              ) : (
                <div className="row">
                  <input type={d.secret ? 'password' : 'text'} autoComplete="off" spellCheck={false} value={value(d)}
                         placeholder={d.secret ? (d.set ? '•••••••• saved (type to replace)' : 'Not set') : d.name === 'llm.model' ? MODEL_HINT[provider] : ''}
                         onChange={(e) => setEdits({ ...edits, [d.name]: e.target.value })} />
                  {d.secret && d.set && <button type="button" className="small" onClick={() => save(g.id, d.name)}>Remove</button>}
                </div>
              )}
            </label>
          ))}
          <div className="row">
            <button className="primary small" disabled={busy === g.id}>Save</button>
            {g.test && <button type="button" className="small" disabled={busy === g.id} onClick={() => test(g.id, g.test!)}>Test connection</button>}
            {msg[g.id] && <span className={msg[g.id].ok ? 'ok-text small' : 'error-text small'}>{msg[g.id].text}</span>}
          </div>
        </form>
      ))}
    </div>
  );
}

const TOPOLOGY_EXAMPLE = JSON.stringify({
  services: [
    { name: 'api-gateway', owner: 'team-platform', dependsOn: ['payment-service', 'order-service'] },
    { name: 'payment-service', owner: 'team-payments', dependsOn: ['payment-db', 'payment-gateway'], runbook: 'DB connection pool saturation' },
    { name: 'order-service', owner: 'team-orders', dependsOn: ['orders-db', 'kafka'] },
  ],
}, null, 2);

function ServiceMap({ onChange }: { onChange: () => void }) {
  const [text, setText] = useState('');
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  useEffect(() => {
    api('/api/admin/topology').then((t) => setText(t && t.services ? JSON.stringify(t, null, 2) : TOPOLOGY_EXAMPLE));
  }, []);
  async function save() {
    let parsed;
    try { parsed = JSON.parse(text); } catch { return setMsg({ ok: false, text: 'That isn’t valid JSON.' }); }
    try {
      await api('/api/admin/topology', 'PUT', { topology: parsed });
      setMsg({ ok: true, text: 'Saved' });
      onChange();
    } catch (e: any) { setMsg({ ok: false, text: e.message }); }
  }
  return (
    <div className="card">
      <h3>Service map</h3>
      <p className="muted small">Which services depend on which. The agent uses it to check upstream and downstream suspects and blast radius.</p>
      <textarea className="mono" rows={18} value={text} onChange={(e) => { setText(e.target.value); setMsg(null); }} spellCheck={false} />
      <div className="row"><button className="primary small" onClick={save}>Save</button>{msg && <span className={msg.ok ? 'ok-text small' : 'error-text small'}>{msg.text}</span>}</div>
    </div>
  );
}

function Keys() {
  const [list, setList] = useState<any[]>([]);
  const [name, setName] = useState('');
  const [scope, setScope] = useState('ingest');
  const [origins, setOrigins] = useState('');
  const [created, setCreated] = useState<string | null>(null);
  const [error, setError] = useState('');
  const load = () => api<any[]>('/api/admin/keys').then(setList);
  useEffect(() => { load(); }, []);
  async function create(e: FormEvent) {
    e.preventDefault();
    if (!name.trim()) return setError('Give the key a name.');
    const allowedOrigins = origins.split(/[\s,]+/).map((s) => s.trim().replace(/\/+$/, '')).filter(Boolean);
    try {
      const r = await api('/api/admin/keys', 'POST', { name: name.trim(), scope, allowedOrigins });
      setCreated(r.key);
      setName('');
      setOrigins('');
      load();
    } catch (err: any) { setError(err.message); }
  }
  async function revoke(id: string) {
    if (!confirm('Revoke this key? Apps using it will stop working immediately.')) return;
    await api('/api/admin/keys/' + id, 'DELETE');
    load();
  }
  return (
    <div className="grid-2">
      <div className="card">
        <h3>API keys</h3>
        <table className="table">
          <thead><tr><th>Name</th><th>Scope</th><th>Last used</th><th /></tr></thead>
          <tbody>
            {list.map((k) => (
              <tr key={k.id} className={k.revoked ? 'muted' : ''}>
                <td><div>{k.name}</div><div className="mono tiny muted">{k.prefix}…{k.allowed_origins ? ' · ' + k.allowed_origins : ''}</div></td>
                <td><span className="tag">{k.scope}</span></td>
                <td className="muted small">{k.revoked ? 'revoked' : k.last_used_at ? ago(k.last_used_at) : 'never'}</td>
                <td>{!k.revoked && <button className="small" onClick={() => revoke(k.id)}>Revoke</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <form className="card" onSubmit={create}>
        <h3>Create a key</h3>
        <label>Name<input value={name} maxLength={100} onChange={(e) => { setName(e.target.value); setError(''); }} placeholder="payment-service production" /></label>
        <label>Scope
          <select value={scope} onChange={(e) => setScope(e.target.value)}>
            <option value="ingest">ingest: send errors and alerts only (for apps and Grafana)</option>
            <option value="admin">admin: full API access (for your backend scripts)</option>
            <option value="widget">widget: embedded chat on allowed sites only</option>
          </select>
        </label>
        {scope === 'widget' && (
          <label>Allowed origins (space-separated)<input value={origins} onChange={(e) => setOrigins(e.target.value)} placeholder="https://admin.example.com" /></label>
        )}
        {error && <div className="error-text">{error}</div>}
        <button className="primary small">Create key</button>
        {created && (
          <div className="created">
            <div className="strong small">Copy this key now. It won't be shown again.</div>
            <div className="row"><code className="mono small break">{created}</code>
              <button type="button" className="small" onClick={() => navigator.clipboard?.writeText(created)}>Copy</button></div>
            <button type="button" className="ghost small" onClick={() => setCreated(null)}>I've saved it</button>
          </div>
        )}
      </form>
    </div>
  );
}

function Members({ me }: { me: Me }) {
  const [list, setList] = useState<any[]>([]);
  const [role, setRole] = useState('MEMBER');
  const [link, setLink] = useState('');
  const [error, setError] = useState('');
  const load = () => api<any[]>('/api/admin/members').then(setList);
  useEffect(() => { load(); }, []);
  async function invite() {
    const r = await api('/api/admin/invites', 'POST', { role });
    setLink(r.link);
  }
  async function remove(id: string) {
    if (!confirm('Remove this person from the workspace?')) return;
    try { await api('/api/admin/members/' + id, 'DELETE'); load(); } catch (e: any) { setError(e.message); }
  }
  return (
    <div className="grid-2">
      <div className="card">
        <h3>Members</h3>
        {error && <div className="error-text">{error}</div>}
        <table className="table"><tbody>
          {list.map((m) => (
            <tr key={m.id}><td>{m.email}</td><td><span className="tag">{String(m.role).toLowerCase()}</span></td>
              <td>{m.email !== me.name && <button className="small" onClick={() => remove(m.id)}>Remove</button>}</td></tr>
          ))}
        </tbody></table>
      </div>
      <div className="card">
        <h3>Invite someone</h3>
        <label>Role<select value={role} onChange={(e) => setRole(e.target.value)}><option value="MEMBER">Member</option><option value="OWNER">Owner</option></select></label>
        <button className="primary small" onClick={invite}>Create invite link</button>
        {link && (
          <div className="created">
            <div className="small">Send this link privately. It works once and expires in 7 days.</div>
            <div className="row"><code className="mono small break">{link}</code><button className="small" onClick={() => navigator.clipboard?.writeText(link)}>Copy</button></div>
          </div>
        )}
      </div>
    </div>
  );
}

function Audit() {
  const [list, setList] = useState<any[]>([]);
  useEffect(() => { api<any[]>('/api/admin/audit').then(setList); }, []);
  return (
    <div className="card">
      <h3>Audit log</h3>
      <table className="table">
        <thead><tr><th>When</th><th>Who</th><th>What</th><th>Detail</th></tr></thead>
        <tbody>{list.map((a, i) => <tr key={i}><td className="muted small">{ago(a.at)}</td><td className="small">{a.actor}</td><td><span className="tag">{a.action}</span></td><td className="small">{a.detail}</td></tr>)}</tbody>
      </table>
    </div>
  );
}

const TABS = [['integrations', 'Integrations'], ['map', 'Service map'], ['keys', 'API keys'], ['members', 'Members'], ['audit', 'Audit log']];

export default function Settings({ me, onChange }: { me: Me; onChange: () => void }) {
  const [tab, setTab] = useState('integrations');
  return (
    <div className="page">
      <h1>Settings</h1>
      <p className="muted">{me.workspaceName}: integrations, keys and people. Secrets are encrypted and never shown again after saving.</p>
      <div className="tabs page-tabs">{TABS.map(([id, label]) => <button key={id} className={tab === id ? 'tab active' : 'tab'} onClick={() => setTab(id)}>{label}</button>)}</div>
      {tab === 'integrations' && <Integrations onChange={onChange} />}
      {tab === 'map' && <ServiceMap onChange={onChange} />}
      {tab === 'keys' && <Keys />}
      {tab === 'members' && <Members me={me} />}
      {tab === 'audit' && <Audit />}
    </div>
  );
}
