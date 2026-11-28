import { useEffect, useState, type FormEvent } from 'react';
import { useFeedback } from '../../components/Feedback';
import { Icon, type IconName } from '../../components/Icon';
import { Alert, Badge, Button, Card, CardHeader, EmptyState, PageHeader, Select, SkeletonRows, TextInput } from '../../components/ui';
import { api } from '../../lib/api';
import { ago } from '../../lib/format';
import { useApi, useDocumentTitle } from '../../lib/hooks';
import type { Me } from '../../lib/types';

type Section = 'integrations' | 'map' | 'keys' | 'members' | 'audit';

interface SettingDef { name: string; group: string; label: string; secret: boolean; value?: string; set?: boolean }

const GROUPS: { id: string; title: string; icon: IconName; test?: string; help: string }[] = [
  { id: 'ai', title: 'AI provider', icon: 'sparkles', test: 'ai', help: 'Bring your own key. Gemini and Groq have free tiers; Claude and OpenAI are paid. Leave as “none” for free offline mode.' },
  { id: 'prometheus', title: 'Prometheus', icon: 'chart', test: 'prometheus', help: 'Grafana Cloud: the query URL ending in /api/prom, your instance ID as username, and a metrics:read token.' },
  { id: 'code', title: 'Source code (GitHub)', icon: 'gitBranch', test: 'code', help: 'A fine-grained token with Contents: read and Pull requests: read. The issues token is only used for actions you approve.' },
  { id: 'loki', title: 'Loki logs', icon: 'list', test: 'loki', help: 'Optional. Lets the agent search error logs.' },
  { id: 'tempo', title: 'Tempo traces', icon: 'activity', test: 'tempo', help: 'Optional. Lets the agent find slow spans.' },
  { id: 'grafana', title: 'Grafana dashboards', icon: 'layers', test: 'grafana', help: 'Optional. Use a Viewer service account token.' },
  { id: 'notify', title: 'Slack notifications', icon: 'bell', test: 'slack', help: 'Posts finished investigations to a channel through an incoming webhook.' },
  { id: 'actions', title: 'Remediation webhook', icon: 'webhook', help: 'Approved actions are POSTed here, signed with HMAC-SHA256 in the X-PulseAgent-Signature header.' },
];

const CHOICES: Record<string, { value: string; label: string }[]> = {
  'llm.provider': [{ value: 'none', label: 'None (offline mode)' }, { value: 'gemini', label: 'Google Gemini' }, { value: 'anthropic', label: 'Anthropic Claude' }, { value: 'openai', label: 'OpenAI-compatible (OpenAI, Groq, Ollama…)' }],
  'notify.on': [{ value: 'alerts', label: 'Automatic investigations only' }, { value: 'all', label: 'All investigations' }, { value: 'none', label: 'Nothing' }],
};
const MODEL_HINT: Record<string, string> = { gemini: 'gemini-flash-latest', anthropic: 'claude-opus-5-5', openai: 'gpt-4o-mini, or llama-3.3-70b-versatile on Groq', none: '' };

export default function Settings({ me, onChange }: { me: Me; onChange: () => void }) {
  useDocumentTitle('Settings');
  const [section, setSection] = useState<Section>('integrations');
  const nav: { id: Section; label: string; icon: IconName }[] = [
    { id: 'integrations', label: 'Integrations', icon: 'plug' },
    { id: 'map', label: 'Service map', icon: 'network' },
    { id: 'keys', label: 'API keys', icon: 'key' },
    { id: 'members', label: 'Members', icon: 'users' },
    { id: 'audit', label: 'Audit log', icon: 'list' },
  ];
  return (
    <div className="page fade-in">
      <PageHeader title="Settings" description={`${me.workspaceName}: integrations, keys and people. Secrets are encrypted and never shown again after saving.`} />
      <div className="settings-layout">
        <nav className="settings-nav" aria-label="Settings sections">
          {nav.map((n) => <button key={n.id} aria-current={section === n.id} onClick={() => setSection(n.id)}><Icon name={n.icon} />{n.label}</button>)}
        </nav>
        <div style={{ minWidth: 0 }}>
          {section === 'integrations' && <Integrations onChange={onChange} />}
          {section === 'map' && <ServiceMap onChange={onChange} />}
          {section === 'keys' && <Keys />}
          {section === 'members' && <Members me={me} />}
          {section === 'audit' && <Audit />}
        </div>
      </div>
    </div>
  );
}

function Integrations({ onChange }: { onChange: () => void }) {
  const { toast, confirm } = useFeedback();
  const { data: defs, setData, error, loading } = useApi<SettingDef[]>('/api/admin/settings');
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<Record<string, { ok: boolean; text: string } | undefined>>({});
  const [busy, setBusy] = useState('');

  if (loading) return <Card><SkeletonRows rows={6} /></Card>;
  if (error || !defs) return <Alert tone="bad">{error}</Alert>;

  const value = (d: SettingDef) => (d.name in edits ? edits[d.name] : d.secret ? '' : d.value || '');
  const provider = value(defs.find((d) => d.name === 'llm.provider')!) || 'none';
  const dirty = (group: string) => defs.some((d) => d.group === group && d.name in edits);

  async function save(group: string, clear?: string) {
    const values: Record<string, string> = {};
    defs!.filter((d) => d.group === group).forEach((d) => { if (d.name in edits) values[d.name] = edits[d.name]; });
    if (clear) values[clear] = '';
    if (!Object.keys(values).length) return;
    setBusy(group);
    try {
      setData(await api<SettingDef[]>('/api/admin/settings', 'PUT', { values }));
      setEdits((e) => { const n = { ...e }; Object.keys(values).forEach((k) => delete n[k]); return n; });
      setStatus((s) => ({ ...s, [group]: undefined }));
      toast(clear ? 'Secret removed' : 'Saved');
      onChange();
    } catch (e: any) {
      setStatus((s) => ({ ...s, [group]: { ok: false, text: e.message } }));
    } finally { setBusy(''); }
  }

  async function test(group: string, integration: string) {
    setBusy('test:' + group);
    try {
      const r = await api(`/api/admin/settings/test/${integration}`, 'POST');
      setStatus((s) => ({ ...s, [group]: { ok: r.ok, text: r.detail } }));
    } catch (e: any) {
      setStatus((s) => ({ ...s, [group]: { ok: false, text: e.message } }));
    } finally { setBusy(''); }
  }

  return (
    <div className="stack">
      {GROUPS.map((g) => {
        const fields = defs.filter((d) => d.group === g.id);
        const configured = fields.some((d) => (d.secret ? d.set : d.value && d.value !== 'none'));
        const st = status[g.id];
        return (
          <Card key={g.id} as="form" onSubmit={(e: FormEvent) => { e.preventDefault(); save(g.id); }} noValidate>
            <div className="card-header">
              <span className="integration-icon"><Icon name={g.icon} /></span>
              <div style={{ flex: 1, minWidth: 0 }}><h3>{g.title}</h3><div className="muted small">{g.help}</div></div>
              {configured ? <Badge tone="ok" icon="check">Connected</Badge> : <Badge tone="neutral">Not set</Badge>}
            </div>
            <div className="card-body form-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(min(240px, 100%), 1fr))' }}>
              {fields.filter((d) => !(d.name === 'llm.baseUrl' && provider !== 'openai')).map((d) => CHOICES[d.name] ? (
                <Select key={d.name} label={d.label} value={value(d) || CHOICES[d.name][0].value} options={CHOICES[d.name]}
                        onChange={(e) => setEdits({ ...edits, [d.name]: e.target.value })} />
              ) : (
                <div key={d.name} className="field">
                  <TextInput label={d.label} type={d.secret ? 'password' : 'text'} autoComplete="off" spellCheck={false} value={value(d)}
                             placeholder={d.secret ? (d.set ? '•••••••• saved. Type to replace' : 'Not set') : d.name === 'llm.model' ? MODEL_HINT[provider] : ''}
                             onChange={(e) => setEdits({ ...edits, [d.name]: e.target.value })} />
                  {d.secret && d.set && (
                    <div className="row" style={{ justifyContent: 'space-between' }}>
                      <span className="secret-set"><Icon name="lock" size={13} />Saved and encrypted</span>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={async () => {
                        if (await confirm({ title: `Remove the ${d.label.toLowerCase()}?`, body: 'Features that use it stop working until you add a new one.', confirmLabel: 'Remove', danger: true })) save(g.id, d.name);
                      }}>Remove</button>
                    </div>
                  )}
                </div>
              ))}
            </div>
            <div className="card-footer row-wrap">
              {st && <span className="row small" style={{ color: st.ok ? 'var(--ok)' : 'var(--bad)', flex: 1, minWidth: 200 }}><Icon name={st.ok ? 'checkCircle' : 'xCircle'} size={15} />{st.text}</span>}
              <span className="spacer" />
              {g.test && <Button size="sm" icon="zap" loading={busy === 'test:' + g.id} disabled={busy !== '' || dirty(g.id)} title={dirty(g.id) ? 'Save first' : undefined} onClick={() => test(g.id, g.test!)}>Test connection</Button>}
              <Button size="sm" type="submit" variant="primary" loading={busy === g.id} disabled={!dirty(g.id) || busy !== ''}>Save</Button>
            </div>
          </Card>
        );
      })}
    </div>
  );
}

const MAP_EXAMPLE = JSON.stringify({
  services: [
    { name: 'api-gateway', owner: 'team-platform', dependsOn: ['payment-service', 'order-service'] },
    { name: 'payment-service', owner: 'team-payments', dependsOn: ['payment-db', 'payment-gateway'], runbook: 'DB connection pool saturation' },
    { name: 'order-service', owner: 'team-orders', dependsOn: ['orders-db', 'kafka'] },
  ],
}, null, 2);

function ServiceMap({ onChange }: { onChange: () => void }) {
  const { toast } = useFeedback();
  const [text, setText] = useState('');
  const [err, setErr] = useState('');
  const [saving, setSaving] = useState(false);
  const [count, setCount] = useState(0);
  useEffect(() => {
    api('/api/admin/topology').then((t) => {
      const has = t && Array.isArray(t.services) && t.services.length;
      setText(has ? JSON.stringify(t, null, 2) : MAP_EXAMPLE);
      setCount(has ? t.services.length : 0);
    }).catch((e) => setErr(e.message));
  }, []);
  async function save() {
    let parsed: any;
    try { parsed = JSON.parse(text); } catch { return setErr('That isn’t valid JSON. Check for missing commas or quotes.'); }
    setSaving(true);
    try {
      await api('/api/admin/topology', 'PUT', { topology: parsed });
      setCount(parsed.services?.length || 0);
      toast('Service map saved');
      onChange();
    } catch (e: any) { setErr(e.message); } finally { setSaving(false); }
  }
  return (
    <Card>
      <CardHeader title="Service map" icon="network" subtitle={count ? `${count} services` : 'Not saved yet. The example below shows the format.'} />
      <div className="card-body stack">
        <p className="text-2">Tell the agent which services depend on which, so it can check upstream and downstream suspects and estimate blast radius.</p>
        {err && <Alert tone="bad" onClose={() => setErr('')}>{err}</Alert>}
        <label htmlFor="map" className="sr-only">Service map JSON</label>
        <textarea id="map" className="textarea json-editor" value={text} spellCheck={false} onChange={(e) => { setText(e.target.value); setErr(''); }} />
      </div>
      <div className="card-footer row"><span className="muted small">Fields: name, dependsOn, owner, runbook, tier</span><span className="spacer" /><Button variant="primary" loading={saving} onClick={save}>Save service map</Button></div>
    </Card>
  );
}

function Keys() {
  const { toast, confirm } = useFeedback();
  const { data, loading, reload } = useApi<any[]>('/api/admin/keys');
  const [name, setName] = useState('');
  const [scope, setScope] = useState('ingest');
  const [origins, setOrigins] = useState('');
  const [created, setCreated] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);

  async function create(e: FormEvent) {
    e.preventDefault();
    const list = origins.split(/[\s,]+/).map((s) => s.trim().replace(/\/+$/, '')).filter(Boolean);
    const errs: Record<string, string> = {};
    if (!name.trim()) errs.name = 'Give the key a name, like the app that will use it.';
    if (scope === 'widget' && !list.length) errs.origins = 'Add at least one site, like https://admin.example.com.';
    if (list.some((o) => !/^https?:\/\/[A-Za-z0-9.-]+(:\d+)?$/.test(o))) errs.origins = 'Use origins only, like https://admin.example.com (no paths).';
    setErrors(errs);
    if (Object.keys(errs).length) return;
    setSaving(true);
    try {
      const r = await api('/api/admin/keys', 'POST', { name: name.trim(), scope, allowedOrigins: list });
      setCreated(r.key);
      setName(''); setOrigins('');
      reload();
    } catch (err: any) { toast(err.message, 'bad'); } finally { setSaving(false); }
  }

  async function revoke(k: any) {
    if (!(await confirm({ title: `Revoke “${k.name}”?`, body: 'Apps using this key stop working immediately. This can’t be undone.', confirmLabel: 'Revoke key', danger: true }))) return;
    try { await api(`/api/admin/keys/${k.id}`, 'DELETE'); toast('Key revoked'); reload(); } catch (e: any) { toast(e.message, 'bad'); }
  }

  const SCOPES: Record<string, string> = { ingest: 'Send errors and alerts only', admin: 'Full API access', widget: 'Embedded chat on allowed sites' };

  return (
    <div className="stack">
      {created && (
        <div className="key-reveal fade-in" role="status">
          <div className="row strong" style={{ color: 'var(--ok)' }}><Icon name="checkCircle" size={17} />Your new key. Copy it now; it won’t be shown again.</div>
          <code>{created}</code>
          <div className="row">
            <Button size="sm" icon="copy" onClick={() => { navigator.clipboard?.writeText(created); toast('Copied'); }}>Copy key</Button>
            <Button size="sm" variant="ghost" onClick={() => setCreated(null)}>I’ve saved it</Button>
          </div>
        </div>
      )}
      <Card as="form" onSubmit={create} noValidate>
        <CardHeader title="Create a key" icon="plus" />
        <div className="card-body form-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(min(220px, 100%), 1fr))' }}>
          <TextInput label="Name" value={name} maxLength={100} placeholder="payment-service production" error={errors.name} onChange={(e) => { setName(e.target.value); setErrors({}); }} />
          <Select label="Scope" value={scope} onChange={(e) => { setScope(e.target.value); setErrors({}); }}
                  options={Object.entries(SCOPES).map(([v, l]) => ({ value: v, label: `${v}: ${l}` }))} />
          {scope === 'widget' && <TextInput label="Allowed sites" value={origins} placeholder="https://admin.example.com" error={errors.origins}
                                            hint="Separate several with spaces." onChange={(e) => { setOrigins(e.target.value); setErrors({}); }} />}
        </div>
        <div className="card-footer row"><span className="muted small">Keys are stored only as SHA-256 hashes.</span><span className="spacer" /><Button type="submit" variant="primary" icon="key" loading={saving}>Create key</Button></div>
      </Card>
      <Card>
        <CardHeader title="Keys" icon="key" />
        {loading ? <SkeletonRows /> : !data?.length ? <EmptyState icon="key" title="No keys yet">Create an ingest key for each app that reports errors.</EmptyState> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Name</th><th>Scope</th><th>Created</th><th>Last used</th><th /></tr></thead>
              <tbody>
                {data.map((k) => (
                  <tr key={k.id} style={k.revoked ? { opacity: 0.55 } : undefined}>
                    <td><div className="strong">{k.name}</div><div className="mono tiny muted">{k.prefix}…{k.allowed_origins ? ` · ${k.allowed_origins}` : ''}</div></td>
                    <td><Badge tone={k.scope === 'admin' ? 'warn' : 'neutral'}>{k.scope}</Badge></td>
                    <td className="small muted nowrap">{ago(k.created_at)}<div className="tiny">by {k.created_by}</div></td>
                    <td className="small muted nowrap">{k.revoked ? <Badge tone="bad">Revoked</Badge> : k.last_used_at ? ago(k.last_used_at) : 'Never'}</td>
                    <td className="actions-cell">{!k.revoked && <Button size="sm" variant="danger" onClick={() => revoke(k)}>Revoke</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

function Members({ me }: { me: Me }) {
  const { toast, confirm } = useFeedback();
  const { data, loading, reload } = useApi<any[]>('/api/admin/members');
  const [role, setRole] = useState('MEMBER');
  const [link, setLink] = useState('');
  const [busy, setBusy] = useState(false);

  async function invite() {
    setBusy(true);
    try { setLink((await api('/api/admin/invites', 'POST', { role })).link); } catch (e: any) { toast(e.message, 'bad'); } finally { setBusy(false); }
  }
  async function remove(m: any) {
    if (!(await confirm({ title: `Remove ${m.email}?`, body: 'They lose access to this workspace immediately.', confirmLabel: 'Remove', danger: true }))) return;
    try { await api(`/api/admin/members/${m.id}`, 'DELETE'); toast('Member removed'); reload(); } catch (e: any) { toast(e.message, 'bad'); }
  }

  return (
    <div className="stack">
      <Card>
        <CardHeader title="Invite someone" icon="users" subtitle="Invite links work once and expire after 7 days." />
        <div className="card-body row-wrap" style={{ alignItems: 'flex-end' }}>
          <div style={{ width: 220 }}><Select label="Role" value={role} onChange={(e) => setRole(e.target.value)} options={[{ value: 'MEMBER', label: 'Member: investigate and view' }, { value: 'OWNER', label: 'Owner: full control' }]} /></div>
          <Button variant="primary" icon="plus" loading={busy} onClick={invite}>Create invite link</Button>
        </div>
        {link && (
          <div className="card-body" style={{ paddingTop: 0 }}>
            <div className="key-reveal">
              <div className="small strong">Send this link privately, for example in a direct message.</div>
              <code>{link}</code>
              <div className="row"><Button size="sm" icon="copy" onClick={() => { navigator.clipboard?.writeText(link); toast('Invite link copied'); }}>Copy link</Button></div>
            </div>
          </div>
        )}
      </Card>
      <Card>
        <CardHeader title="Members" icon="users" subtitle={data ? `${data.length} people` : undefined} />
        {loading ? <SkeletonRows /> : !data?.length ? (
          <EmptyState icon="users" title="Only API keys so far">Invite teammates so they can sign in with their own accounts.</EmptyState>
        ) : (
          <div>
            {data.map((m) => (
              <div key={m.id} className="list-row">
                <span className="avatar">{m.email.slice(0, 2).toUpperCase()}</span>
                <div className="list-main"><div className="strong truncate">{m.email}{m.email === me.name && <span className="muted"> (you)</span>}</div><div className="muted small">Joined {ago(m.created_at)}</div></div>
                <Badge tone={m.role === 'OWNER' ? 'info' : 'neutral'}>{m.role === 'OWNER' ? 'Owner' : 'Member'}</Badge>
                {m.email !== me.name && <Button size="sm" variant="ghost" icon="trash" aria-label={`Remove ${m.email}`} onClick={() => remove(m)} />}
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}

function Audit() {
  const { data, loading, error } = useApi<any[]>('/api/admin/audit');
  return (
    <Card>
      <CardHeader title="Audit log" icon="list" subtitle="The last 200 security-relevant events in this workspace" />
      {loading ? <SkeletonRows rows={6} /> : error ? <div className="card-body"><Alert tone="bad">{error}</Alert></div> : !data?.length ? <EmptyState icon="list" title="No events yet" /> : (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>When</th><th>Who</th><th>Event</th><th>Detail</th></tr></thead>
            <tbody>
              {data.map((a, i) => (
                <tr key={i}>
                  <td className="small muted nowrap" title={a.at}>{ago(a.at)}</td>
                  <td className="small">{a.actor}</td>
                  <td><span className="tag mono">{a.action}</span></td>
                  <td className="small text-2" style={{ maxWidth: 420 }}>{a.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
