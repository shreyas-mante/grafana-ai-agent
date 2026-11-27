import { AskBox, StatusBadge } from '../../components/Domain';
import { Icon, type IconName } from '../../components/Icon';
import { Card, CardHeader, EmptyState, LinkButton, PageHeader, SkeletonRows, Stat } from '../../components/ui';
import { ago, shortClass } from '../../lib/format';
import { useApi, useDocumentTitle } from '../../lib/hooks';
import { href, routes } from '../../lib/router';
import type { ActionItem, ErrorGroup, InvestigationSummary, Me, Meta } from '../../lib/types';

export default function Overview({ me, meta }: { me: Me; meta: Meta | null }) {
  useDocumentTitle('Overview');
  const invs = useApi<InvestigationSummary[]>('/api/investigations', { poll: 8000 });
  const errors = useApi<ErrorGroup[]>('/api/errors', { poll: 15000 });
  const actions = useApi<ActionItem[]>('/api/actions', { poll: 15000 });

  const week = Date.now() - 7 * 86400_000;
  const recent = (invs.data || []).filter((i) => new Date(i.created_at).getTime() > week);
  const running = (invs.data || []).filter((i) => i.status === 'QUEUED' || i.status === 'RUNNING').length;
  const activeErrors = (errors.data || []).filter((e) => Date.now() - new Date(e.lastSeen).getTime() < 86400_000);
  const errorEvents = activeErrors.reduce((s, e) => s + e.count, 0);
  const pending = (actions.data || []).filter((a) => a.status === 'PENDING').length;

  const setup: { done: boolean; label: string; hint: string; href: string; icon: IconName }[] = meta ? [
    { done: meta.aiMode === 'ai', label: 'Connect an AI provider', hint: 'Gemini and Groq have free tiers. Without one, investigations run offline.', href: routes.settings, icon: 'sparkles' },
    { done: !!meta.prometheusHost, label: 'Connect Prometheus', hint: 'The agent compares metrics against the baseline.', href: routes.settings, icon: 'chart' },
    { done: meta.codeProvider !== 'none', label: 'Connect your source code', hint: 'For exact file, line, author and commit.', href: routes.settings, icon: 'fileCode' },
    { done: (errors.data || []).length > 0, label: 'Send errors from your app', hint: 'Add the Spring Boot starter with an ingest key.', href: routes.integrate, icon: 'code' },
    { done: meta.serviceMap, label: 'Add your service map', hint: 'Lets the agent check dependencies and blast radius.', href: routes.settings, icon: 'network' },
  ] : [];
  const setupDone = setup.filter((s) => s.done).length;

  return (
    <div className="page fade-in">
      <PageHeader title={`Good ${greeting()}, ${firstName(me.name)}`} description={`Here’s what’s happening in ${me.workspaceName}.`} />

      <section className="hero-card" aria-labelledby="ask-title">
        <h2 id="ask-title" className="row" style={{ marginBottom: 12 }}><Icon name="sparkles" size={18} />Ask PulseAgent</h2>
        <AskBox />
      </section>

      <div className="grid-4">
        <Stat label="Investigations (7 days)" icon="search" value={invs.data ? recent.length : '…'} sub={running ? `${running} running now` : 'None running'} />
        <Stat label="Active error groups" icon="alert" value={errors.data ? activeErrors.length : '…'} tone={activeErrors.length ? 'bad' : undefined} sub={`${errorEvents.toLocaleString()} events in 24 h`} />
        <Stat label="Pending approvals" icon="shield" value={actions.data ? pending : '…'} sub={pending ? 'Waiting for an owner' : 'Nothing to approve'} />
        <Stat label="Knowledge base" icon="book" value={meta ? meta.knowledgeDocs : '…'} sub="Runbooks and incidents" />
      </div>

      <div className="grid-2">
        <Card>
          <CardHeader title="Recent investigations" icon="search"><LinkButton href={href(routes.investigations)} size="sm" variant="ghost" iconRight="arrowRight">View all</LinkButton></CardHeader>
          {invs.loading ? <SkeletonRows rows={4} /> : (invs.data || []).length === 0 ? (
            <EmptyState icon="search" title="No investigations yet">Ask a question above, or open an error to investigate it.</EmptyState>
          ) : (
            <div>
              {(invs.data || []).slice(0, 6).map((i) => (
                <a key={i.id} className="list-row" href={href(routes.investigation(i.id))}>
                  <span className={`list-icon ${i.status === 'FAILED' ? 'bad' : i.status === 'COMPLETED' ? 'ok' : 'info'}`}><Icon name={i.status === 'COMPLETED' ? 'checkCircle' : i.status === 'FAILED' ? 'xCircle' : 'refresh'} /></span>
                  <div className="list-main">
                    <div className="truncate strong">{i.title || i.question}</div>
                    <div className="muted small truncate">{i.service ? `${i.service} · ` : ''}{ago(i.created_at)}</div>
                  </div>
                  <StatusBadge status={i.status} confidence={i.confidence} />
                </a>
              ))}
            </div>
          )}
        </Card>

        <Card>
          <CardHeader title="Top errors (24 h)" icon="alert"><LinkButton href={href(routes.errors)} size="sm" variant="ghost" iconRight="arrowRight">View all</LinkButton></CardHeader>
          {errors.loading ? <SkeletonRows rows={4} /> : activeErrors.length === 0 ? (
            <EmptyState icon="checkCircle" title="No errors in the last 24 hours">Errors appear here when your apps report them through the SDK.</EmptyState>
          ) : (
            <div>
              {[...activeErrors].sort((a, b) => b.count - a.count).slice(0, 6).map((e) => (
                <a key={e.id} className="list-row" href={href(routes.errors)}>
                  <span className="list-icon bad"><Icon name="alert" /></span>
                  <div className="list-main">
                    <div className="truncate strong">{shortClass(e.exceptionClass)}</div>
                    <div className="muted small truncate">{e.service}{e.path ? ` · ${e.httpMethod || ''} ${e.path}` : ''} · {ago(e.lastSeen)}</div>
                  </div>
                  <span className="badge badge-bad">{e.count.toLocaleString()}</span>
                </a>
              ))}
            </div>
          )}
        </Card>
      </div>

      {setup.length > 0 && setupDone < setup.length && (
        <Card>
          <CardHeader title="Finish setting up" icon="checkCircle" subtitle={`${setupDone} of ${setup.length} done`}>
            <div className="progress" style={{ width: 140 }}><span style={{ width: `${(setupDone / setup.length) * 100}%` }} /></div>
          </CardHeader>
          <div className="setup-list">
            {setup.map((s) => (
              <div key={s.label} className="setup-item">
                <span className={`setup-check ${s.done ? 'done' : ''}`}><Icon name="check" /></span>
                <div className="list-main">
                  <div className={s.done ? 'muted' : 'strong'} style={s.done ? { textDecoration: 'line-through' } : undefined}>{s.label}</div>
                  {!s.done && <div className="muted small">{s.hint}</div>}
                </div>
                {!s.done && (me.role === 'OWNER' || s.href === routes.integrate) && <LinkButton href={href(s.href)} size="sm" iconRight="arrowRight">Set up</LinkButton>}
              </div>
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}

function greeting() {
  const h = new Date().getHours();
  return h < 12 ? 'morning' : h < 18 ? 'afternoon' : 'evening';
}

function firstName(name: string) {
  const n = name.replace(/^API key:\s*/, '').split('@')[0].split(/[._\s-]/)[0];
  return n ? n[0].toUpperCase() + n.slice(1) : 'there';
}
