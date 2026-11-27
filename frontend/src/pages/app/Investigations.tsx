import { useMemo, useState } from 'react';
import { AskBox, StatusBadge, TriggerTag } from '../../components/Domain';
import { Icon } from '../../components/Icon';
import { Alert, Card, EmptyState, PageHeader, SkeletonRows } from '../../components/ui';
import { ago } from '../../lib/format';
import { useApi, useDocumentTitle } from '../../lib/hooks';
import { navigate, routes } from '../../lib/router';
import type { InvestigationSummary } from '../../lib/types';

type Filter = 'all' | 'running' | 'COMPLETED' | 'FAILED';

export default function Investigations() {
  useDocumentTitle('Investigations');
  const { data, error, loading } = useApi<InvestigationSummary[]>('/api/investigations', {
    poll: (d) => (d?.some((i) => i.status === 'QUEUED' || i.status === 'RUNNING') ? 3000 : 10000),
  });
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState<Filter>('all');

  const rows = useMemo(() => (data || []).filter((i) => {
    if (filter === 'running' && !(i.status === 'QUEUED' || i.status === 'RUNNING')) return false;
    if ((filter === 'COMPLETED' || filter === 'FAILED') && i.status !== filter) return false;
    const text = `${i.title || ''} ${i.question} ${i.service || ''}`.toLowerCase();
    return !q || text.includes(q.toLowerCase());
  }), [data, q, filter]);

  const counts = {
    all: data?.length || 0,
    running: data?.filter((i) => i.status === 'QUEUED' || i.status === 'RUNNING').length || 0,
    COMPLETED: data?.filter((i) => i.status === 'COMPLETED').length || 0,
    FAILED: data?.filter((i) => i.status === 'FAILED').length || 0,
  };

  return (
    <div className="page fade-in">
      <PageHeader title="Investigations" description="Every question, alert, error and scheduled check the agent has looked into." />
      <Card pad><AskBox compact /></Card>
      {error && <Alert tone="bad">{error}</Alert>}
      <Card>
        <div className="card-header" style={{ flexWrap: 'wrap' }}>
          <div className="segmented" role="group" aria-label="Filter by status">
            {([['all', 'All'], ['running', 'Running'], ['COMPLETED', 'Completed'], ['FAILED', 'Failed']] as [Filter, string][]).map(([k, label]) => (
              <button key={k} aria-pressed={filter === k} onClick={() => setFilter(k)}>{label} <span className="muted">{counts[k]}</span></button>
            ))}
          </div>
          <span className="spacer" />
          <div className="input-icon" style={{ width: 260 }}>
            <Icon name="search" />
            <input className="input" type="search" placeholder="Search investigations" aria-label="Search investigations" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
        </div>
        {loading ? <SkeletonRows rows={6} /> : rows.length === 0 ? (
          <EmptyState icon="search" title={data?.length ? 'No matching investigations' : 'Start your first investigation'}>
            {data?.length ? 'Try a different search or filter.' : 'Ask a question above. Alerts sent to the webhook and scheduled checks also show up here.'}
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Investigation</th><th>Service</th><th>Trigger</th><th>Status</th><th>Started</th></tr></thead>
              <tbody>
                {rows.map((i) => (
                  <tr key={i.id} className="clickable" onClick={() => navigate(routes.investigation(i.id))}
                      tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') navigate(routes.investigation(i.id)); }}>
                    <td style={{ maxWidth: 520 }}>
                      <div className="strong truncate">{i.title || i.question}</div>
                      {i.title && <div className="muted small truncate">{i.question}</div>}
                    </td>
                    <td>{i.service ? <span className="tag">{i.service}</span> : <span className="muted">—</span>}</td>
                    <td><TriggerTag trigger={i.trigger_type} /></td>
                    <td><StatusBadge status={i.status} confidence={i.confidence} /></td>
                    <td className="muted nowrap">{ago(i.created_at)}</td>
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
