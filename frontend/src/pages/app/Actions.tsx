import { useState } from 'react';
import { useFeedback } from '../../components/Feedback';
import { Icon } from '../../components/Icon';
import { Alert, Badge, Button, Card, EmptyState, LinkButton, PageHeader, SkeletonRows, type Tone } from '../../components/ui';
import { api } from '../../lib/api';
import { ago } from '../../lib/format';
import { useApi, useDocumentTitle } from '../../lib/hooks';
import { href, routes } from '../../lib/router';
import type { ActionItem } from '../../lib/types';

const STATUS: Record<ActionItem['status'], { tone: Tone; label: string }> = {
  PENDING: { tone: 'info', label: 'Awaiting approval' },
  APPROVED: { tone: 'info', label: 'Running' },
  EXECUTED: { tone: 'ok', label: 'Done' },
  FAILED: { tone: 'bad', label: 'Failed' },
  REJECTED: { tone: 'neutral', label: 'Rejected' },
};

function parse(p: string): { kind?: string; target?: string; details?: string } {
  try { return JSON.parse(p); } catch { return {}; }
}

export default function Actions({ isOwner, onChange }: { isOwner: boolean; onChange: () => void }) {
  useDocumentTitle('Actions');
  const { toast, confirm } = useFeedback();
  const { data, error, loading, reload } = useApi<ActionItem[]>('/api/actions', { poll: 5000 });
  const [busy, setBusy] = useState<string | null>(null);
  const [view, setView] = useState<'pending' | 'history'>('pending');

  async function decide(a: ActionItem, verb: 'approve' | 'reject') {
    const p = parse(a.payload);
    if (verb === 'approve' && !(await confirm({
      title: 'Approve and run this action?',
      body: <><b>{a.title}</b><br />{a.type === 'webhook' ? `It will be sent to your remediation webhook as “${p.kind}” for ${p.target}.` : 'A GitHub issue will be opened with the findings.'}</>,
      confirmLabel: 'Approve and run', danger: a.type === 'webhook',
    }))) return;
    setBusy(a.id);
    try {
      const r = await api(`/api/actions/${a.id}/${verb}`, 'POST');
      toast(verb === 'approve' ? (r.result || 'Approved') : 'Rejected', verb === 'approve' && String(r.result).startsWith('Failed') ? 'bad' : 'ok');
      reload();
      onChange();
    } catch (e: any) { toast(e.message, 'bad'); } finally { setBusy(null); }
  }

  const pending = (data || []).filter((a) => a.status === 'PENDING');
  const history = (data || []).filter((a) => a.status !== 'PENDING');
  const rows = view === 'pending' ? pending : history;

  return (
    <div className="page fade-in">
      <PageHeader title="Actions" description="Remediations the agent proposed. Nothing runs until an owner approves it, and every decision is recorded in the audit log." />
      {error && <Alert tone="bad">{error}</Alert>}
      {!isOwner && pending.length > 0 && <Alert tone="info">Only workspace owners can approve actions.</Alert>}
      <Card>
        <div className="card-header">
          <div className="segmented" role="group" aria-label="Show">
            <button aria-pressed={view === 'pending'} onClick={() => setView('pending')}>Pending <span className="muted">{pending.length}</span></button>
            <button aria-pressed={view === 'history'} onClick={() => setView('history')}>History <span className="muted">{history.length}</span></button>
          </div>
        </div>
        {loading ? <SkeletonRows /> : rows.length === 0 ? (
          view === 'pending'
            ? <EmptyState icon="shield" title="Nothing waiting for approval" action={isOwner && <LinkButton href={href(routes.settings)} icon="settings">Set up actions</LinkButton>}>
                Connect a remediation webhook or a GitHub issues token in Settings, and the agent can propose fixes for you to approve.
              </EmptyState>
            : <EmptyState icon="list" title="No decisions yet">Approved and rejected actions show up here.</EmptyState>
        ) : (
          <div>
            {rows.map((a) => {
              const p = parse(a.payload);
              const s = STATUS[a.status];
              return (
                <div key={a.id} className="list-row" style={{ alignItems: 'flex-start' }}>
                  <span className={`list-icon ${a.status === 'FAILED' ? 'bad' : a.status === 'EXECUTED' ? 'ok' : 'info'}`}><Icon name={a.type === 'github_issue' ? 'pr' : 'webhook'} /></span>
                  <div className="list-main stack-sm">
                    <div className="row-wrap"><span className="strong">{a.title}</span><Badge tone={s.tone}>{s.label}</Badge></div>
                    <div className="row-wrap small"><span className="tag">{p.kind || a.type}</span>{p.target && <span className="mono text-2">{p.target}</span>}</div>
                    {a.reason && <p className="text-2 small">{a.reason}</p>}
                    {p.details && <pre className="code-block small" style={{ padding: '8px 10px', margin: 0, whiteSpace: 'pre-wrap' }}>{p.details}</pre>}
                    <div className="tiny muted">
                      Proposed {ago(a.proposed_at)}{a.decided_by && ` · ${a.status === 'REJECTED' ? 'rejected' : 'approved'} by ${a.decided_by} ${ago(a.decided_at)}`}
                      {a.investigation_id && <> · <a href={href(routes.investigation(a.investigation_id))}>View investigation</a></>}
                    </div>
                    {a.result && <div className={`small ${a.status === 'FAILED' ? '' : 'text-2'}`} style={a.status === 'FAILED' ? { color: 'var(--bad)' } : undefined}>{a.result}</div>}
                  </div>
                  {a.status === 'PENDING' && isOwner && (
                    <div className="row">
                      <Button size="sm" onClick={() => decide(a, 'reject')} disabled={busy === a.id}>Reject</Button>
                      <Button size="sm" variant="primary" icon="check" loading={busy === a.id} onClick={() => decide(a, 'approve')}>Approve</Button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </Card>
    </div>
  );
}
