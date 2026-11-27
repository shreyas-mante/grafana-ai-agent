import { useMemo, useState } from 'react';
import { useFeedback } from '../../components/Feedback';
import { Icon } from '../../components/Icon';
import { Alert, Button, Card, EmptyState, LinkButton, PageHeader, SkeletonRows } from '../../components/ui';
import { api } from '../../lib/api';
import { ago, shortClass } from '../../lib/format';
import { useApi, useDocumentTitle } from '../../lib/hooks';
import { href, navigate, routes } from '../../lib/router';
import type { ErrorGroup } from '../../lib/types';

export default function Errors() {
  useDocumentTitle('Errors');
  const { toast } = useFeedback();
  const { data, error, loading } = useApi<ErrorGroup[]>('/api/errors', { poll: 5000 });
  const [q, setQ] = useState('');
  const [service, setService] = useState('');
  const [busy, setBusy] = useState<string | null>(null);

  const services = useMemo(() => Array.from(new Set((data || []).map((e) => e.service))).sort(), [data]);
  const rows = useMemo(() => (data || []).filter((e) =>
    (!service || e.service === service) &&
    (!q || `${e.exceptionClass} ${e.message || ''} ${e.path || ''} ${e.topFrame || ''}`.toLowerCase().includes(q.toLowerCase()))), [data, q, service]);

  async function investigate(g: ErrorGroup) {
    setBusy(g.id);
    try {
      const inv = await api('/api/investigations', 'POST', {
        question: `Why is ${g.httpMethod ? g.httpMethod + ' ' : ''}${g.path || g.service} failing with ${shortClass(g.exceptionClass)}? Find the root cause in the code and suggest a fix.`,
        service: g.service, lookbackMinutes: 15, errorGroupId: g.id,
      });
      toast('Investigation started');
      navigate(routes.investigation(inv.id));
    } catch (e: any) {
      toast(e.message, 'bad');
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="page fade-in">
      <PageHeader title="Errors" description="Exceptions your applications report through the PulseAgent SDK, grouped by type and code location." />
      {error && <Alert tone="bad">{error}</Alert>}
      <Card>
        <div className="card-header" style={{ flexWrap: 'wrap' }}>
          <div className="input-icon" style={{ width: 280 }}>
            <Icon name="search" />
            <input className="input" type="search" placeholder="Search errors" aria-label="Search errors" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          {services.length > 1 && (
            <select className="select" style={{ width: 200 }} aria-label="Filter by service" value={service} onChange={(e) => setService(e.target.value)}>
              <option value="">All services</option>
              {services.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          )}
          <span className="spacer" />
          <span className="muted small">{rows.length} group{rows.length === 1 ? '' : 's'}</span>
        </div>
        {loading ? <SkeletonRows rows={5} /> : rows.length === 0 ? (
          <EmptyState icon="checkCircle" title={data?.length ? 'No matching errors' : 'No errors reported yet'}
                      action={!data?.length && <LinkButton href={href(routes.integrate)} icon="plug">Connect an app</LinkButton>}>
            {data?.length ? 'Try a different search.' : 'Add the Spring Boot starter to your app with an ingest key. Exceptions show up here within seconds.'}
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Error</th><th>Where</th><th>Events</th><th>Last seen</th><th /></tr></thead>
              <tbody>
                {rows.map((g) => (
                  <tr key={g.id}>
                    <td style={{ maxWidth: 420 }}>
                      <div className="strong">{shortClass(g.exceptionClass)}</div>
                      <div className="muted small clamp-2">{g.message}</div>
                    </td>
                    <td style={{ maxWidth: 360 }}>
                      <div className="row-wrap"><span className="tag">{g.service}</span>{g.environment && <span className="tag">{g.environment}</span>}</div>
                      {g.path && <div className="mono tiny muted truncate" style={{ marginTop: 4 }}>{g.httpMethod} {g.path}</div>}
                      {g.topFrame && <div className="mono tiny muted truncate" title={g.topFrame}>{g.topFrame}</div>}
                    </td>
                    <td><span className="badge badge-bad">{g.count.toLocaleString()}</span></td>
                    <td className="muted nowrap small">{ago(g.lastSeen)}<div className="tiny">first {ago(g.firstSeen)}</div></td>
                    <td className="actions-cell"><Button size="sm" icon="sparkles" loading={busy === g.id} onClick={() => investigate(g)}>Investigate</Button></td>
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
