import { useEffect, useRef, useState, type FormEvent } from 'react';
import { StatusBadge, TriggerTag } from '../../components/Domain';
import { Icon } from '../../components/Icon';
import { Alert, Badge, Button, Card, CardHeader, CopyButton, EmptyState, PageHeader, Skeleton } from '../../components/ui';
import { api } from '../../lib/api';
import { ago, dateTime, duration, num } from '../../lib/format';
import { useApi, useDocumentTitle } from '../../lib/hooks';
import { href, routes } from '../../lib/router';
import type { Chart, CodeContext, Investigation } from '../../lib/types';

export default function InvestigationView({ id }: { id: string }) {
  const { data: inv, error, setData } = useApi<Investigation>(`/api/investigations/${id}`, {
    poll: (d) => (!d || d.status === 'QUEUED' || d.status === 'RUNNING' ? 1500 : false),
  });
  useDocumentTitle(inv?.report?.title || inv?.question || 'Investigation');

  if (error) return <div className="page"><PageHeader title="Investigation" back={{ href: href(routes.investigations), label: 'Investigations' }} /><Alert tone="bad">{error}</Alert></div>;
  if (!inv) return <LoadingView />;

  const r = inv.report || {};
  const running = inv.status === 'QUEUED' || inv.status === 'RUNNING';
  const evidence = Array.isArray(r.evidence) ? r.evidence : [];

  return (
    <div className="page fade-in">
      <PageHeader
        back={{ href: href(routes.investigations), label: 'Investigations' }}
        title={r.title || inv.question}
        actions={<><StatusBadge status={inv.status} confidence={r.confidence} /><CopyButton text={window.location.href} label="Copy link" /></>}
      />
      <div className="inv-meta">
        {inv.service && <span><Icon name="server" />{inv.service}</span>}
        <span><Icon name="clock" />Last {inv.lookbackMinutes < 60 ? `${inv.lookbackMinutes} min` : `${inv.lookbackMinutes / 60} h`}</span>
        <span><TriggerTag trigger={inv.trigger} /></span>
        <span><Icon name={inv.mode === 'offline' ? 'bot' : 'sparkles'} />{inv.mode === 'offline' ? 'Offline mode' : inv.model || 'AI'}</span>
        <span><Icon name="calendar" />{dateTime(inv.createdAt)}</span>
        {inv.durationSeconds != null && <span><Icon name="zap" />{duration(inv.durationSeconds)}</span>}
      </div>
      {r.title && <p className="text-2" style={{ fontStyle: 'italic' }}>“{inv.question}”</p>}
      {inv.status === 'FAILED' && <Alert tone="bad"><b>The investigation couldn’t finish.</b> {inv.failure}</Alert>}
      {running && <Alert tone="info">Investigating… {inv.stepCount} checks so far. This usually takes 20 to 60 seconds.</Alert>}

      {evidence.length > 0 && (
        <div className="grid-4">
          {evidence.slice(0, 4).map((e, i) => (
            <div key={i} className="card stat">
              <div className="stat-label truncate" title={e.signal}>{e.signal}</div>
              <div className="stat-value">{num(e.after)}</div>
              <div className="stat-sub">was {num(e.before)}</div>
            </div>
          ))}
        </div>
      )}

      <div className="inv-grid">
        <div className="stack">
          <Card>
            <CardHeader title="Agent steps" icon="list" subtitle={`${inv.steps.length} tool calls`} />
            {inv.steps.length === 0 ? <div className="card-body"><Skeleton width="60%" /></div> : (
              <ol className="timeline">
                {inv.steps.map((s) => (
                  <li key={s.index} className="tl-item">
                    <span className={`tl-dot ${s.status}`}>{s.status === 'running' ? <span className="spinner" style={{ width: 10, height: 10, borderWidth: 1.5 }} /> : <Icon name={s.status === 'failed' ? 'x' : 'check'} />}</span>
                    <div style={{ minWidth: 0 }}>
                      <div className="tl-tool">{s.tool}{typeof s.args?.title === 'string' ? <span className="muted"> · {s.args.title as string}</span> : null}</div>
                      {s.summary && <div className={`small ${s.status === 'failed' ? '' : 'muted'}`} style={s.status === 'failed' ? { color: 'var(--bad)' } : undefined}>{s.summary}</div>}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </Card>

          {inv.charts.length > 0 && (
            <Card>
              <CardHeader title="Metrics" icon="chart" subtitle="Baseline → recent" />
              {inv.charts.map((c, i) => <ChartRow key={i} chart={c} />)}
            </Card>
          )}

          {inv.knowledge.length > 0 && (
            <Card>
              <CardHeader title="From your knowledge base" icon="book" />
              <div className="card-body stack">
                {inv.knowledge.map((k) => (
                  <details key={k.docId} className="disclosure">
                    <summary><Icon name="chevronRight" /><span className="tag">{k.type}</span><span className="truncate">{k.title}</span><span className="muted small nowrap" style={{ marginLeft: 'auto' }}>{Math.round(k.score * 100)}%</span></summary>
                    <pre className="small text-2" style={{ whiteSpace: 'pre-wrap', fontFamily: 'var(--font)', margin: '8px 0 0 22px' }}>{k.excerpt}</pre>
                  </details>
                ))}
              </div>
            </Card>
          )}
        </div>

        <div className="stack">
          {inv.report && (
            <Card className="cause-card">
              <CardHeader title="Likely cause" icon="target" />
              <div className="card-body stack">
                <p className="cause-text">{r.likelyCause || 'No clear cause found.'}</p>
                {r.summary && <p className="text-2">{r.summary}</p>}
                {r.impact && <p><span className="strong">Impact: </span><span className="text-2">{r.impact}</span></p>}
              </div>
            </Card>
          )}

          {inv.code.length > 0 && (
            <Card>
              <CardHeader title="Code" icon="fileCode" subtitle="At the deployed commit, with git blame" />
              <div className="card-body">
                {r.codeFinding?.explanation && <p className="text-2" style={{ marginBottom: 14 }}>{r.codeFinding.explanation}</p>}
                {inv.code.map((c, i) => <CodeCard key={i} c={c} />)}
              </div>
            </Card>
          )}

          {r.suggestedFix && (r.suggestedFix.explanation || r.suggestedFix.diff) && (
            <Card>
              <CardHeader title="Suggested fix" icon="sparkles"><Badge tone="neutral">{inv.mode === 'offline' ? 'From your knowledge base' : 'AI suggestion'}</Badge></CardHeader>
              <div className="card-body stack">
                <p>{r.suggestedFix.explanation}</p>
                {r.suggestedFix.diff && (
                  <div className="code-block">
                    <pre style={{ padding: '10px 0' }}>{r.suggestedFix.diff.split('\n').map((l, i) => (
                      <div key={i} className={`diff-line ${l.startsWith('+') && !l.startsWith('+++') ? 'add' : l.startsWith('-') && !l.startsWith('---') ? 'del' : ''}`}>{l || ' '}</div>
                    ))}</pre>
                    <CopyButton text={r.suggestedFix.diff} />
                  </div>
                )}
                <p className="muted small">Review before applying. The agent never changes your code.</p>
              </div>
            </Card>
          )}

          {inv.actions?.length > 0 && (
            <Alert tone="info">
              The agent proposed {inv.actions.length === 1 ? 'an action' : `${inv.actions.length} actions`}. Nothing runs until an owner approves it.{' '}
              <a href={href(routes.actions)}>Review in Actions</a>
            </Alert>
          )}

          {inv.report && ((r.nextSteps?.length || 0) > 0 || (r.unknowns?.length || 0) > 0) && (
            <div className={(r.nextSteps?.length || 0) > 0 && (r.unknowns?.length || 0) > 0 ? 'grid-2' : 'stack'}>
              {(r.nextSteps?.length || 0) > 0 && (
                <Card><CardHeader title="Next steps" icon="arrowRight" /><div className="card-body"><ul className="stack-sm">{r.nextSteps!.map((s, i) => <li key={i}>{s}</li>)}</ul></div></Card>
              )}
              {(r.unknowns?.length || 0) > 0 && (
                <Card><CardHeader title="Not verified" icon="info" /><div className="card-body"><ul className="stack-sm text-2">{r.unknowns!.map((s, i) => <li key={i}>{s}</li>)}</ul></div></Card>
              )}
            </div>
          )}

          {inv.status === 'COMPLETED' && <Chat inv={inv} onUpdate={setData} />}
        </div>
      </div>
    </div>
  );
}

function LoadingView() {
  return (
    <div className="page" aria-busy="true">
      <Skeleton width={140} height={12} />
      <Skeleton width="55%" height={28} />
      <div className="grid-4">{[0, 1, 2, 3].map((i) => <div key={i} className="card stat"><Skeleton width="50%" height={11} /><Skeleton width="40%" height={24} style={{ marginTop: 10 }} /></div>)}</div>
      <div className="inv-grid"><div className="card card-pad"><Skeleton height={200} /></div><div className="card card-pad"><Skeleton height={300} /></div></div>
    </div>
  );
}

function ChartRow({ chart }: { chart: Chart }) {
  const pts = chart.points || [];
  const s = chart.summary || {};
  const change = s.changePercent;
  let line = '', area = '';
  if (pts.length > 1) {
    const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
    const W = 300, H = 54;
    const xy = pts.map((p) => [maxX === minX ? 0 : ((p[0] - minX) / (maxX - minX)) * W, maxY === minY ? H / 2 : H - 4 - ((p[1] - minY) / (maxY - minY)) * (H - 10)]);
    line = 'M' + xy.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' L');
    area = `${line} L${W},${H} L0,${H} Z`;
  }
  return (
    <div className="chart-row">
      <div className="row">
        <span className="strong small truncate" style={{ flex: 1 }}>{chart.title}</span>
        <span className="small nowrap">{num(s.baselineMean)} → <b>{num(s.recentMean)}</b></span>
        {change != null && Math.abs(change) >= 1 && <span className={`small nowrap ${change > 0 ? 'delta-up' : 'delta-down'}`}>{change > 0 ? '+' : ''}{Math.round(change)}%</span>}
      </div>
      {line && <svg className="spark" viewBox="0 0 300 54" preserveAspectRatio="none" role="img" aria-label={`${chart.title} trend`}><path className="area" d={area} /><path className="line" d={line} /></svg>}
      <div className="mono tiny muted truncate" title={chart.query}>{chart.query}</div>
    </div>
  );
}

function CodeCard({ c }: { c: CodeContext }) {
  const b = c.blame;
  const changes = c.recentChanges || [];
  const newest = changes[0];
  const showNewest = newest && changes.length > 1 && newest.commit !== b?.commit;
  const commits = c.lineCommits || [];
  return (
    <div className="code-card">
      <div className="code-head">
        <Icon name="fileCode" size={14} />
        <span className="mono strong truncate" style={{ maxWidth: '60%' }} title={c.file}>{c.file}:{c.line}</span>
        <span className="spacer" />
        <span className="muted tiny">@ {String(c.ref).slice(0, 7)} · {c.provider}</span>
      </div>
      {c.note && <div className="alert alert-warn" style={{ borderRadius: 0, border: 'none' }}><Icon name="info" />{c.note}</div>}
      <div className="code-body">
        <div className="code-lines" role="region" aria-label={`Source of ${c.file}`}>
          {c.lines.map((l, i) => {
            const n = c.startLine + i;
            const changed = showNewest && commits[i] === newest.shortCommit;
            return (
              <div key={n} className={`code-line ${n === c.line ? 'hot' : ''} ${changed ? 'changed' : ''}`}>
                <span className="ln">{n}</span><span className="sha">{commits[i] || ''}</span><span>{l || ' '}</span>
              </div>
            );
          })}
        </div>
        <div className="blame">
          <div className="strong small">Last change to line {c.line}</div>
          {b ? (
            <dl>
              <dt>Author</dt><dd>{b.authorLogin ? '@' + b.authorLogin : b.author || '—'}</dd>
              <dt>Commit</dt><dd className="mono">{b.shortCommit}</dd>
              <dt>Message</dt><dd>{b.summary || '—'}</dd>
              <dt>Date</dt><dd>{b.date ? ago(b.date) : '—'}</dd>
              {b.prNumber && <><dt>PR</dt><dd>{/^https:\/\/github\.com\//.test(b.prUrl || '')
                ? <a href={b.prUrl!} target="_blank" rel="noopener noreferrer">#{b.prNumber} {b.prTitle}</a> : `#${b.prNumber} ${b.prTitle || ''}`}</dd></>}
            </dl>
          ) : <span className="muted">Blame not available</span>}
          {showNewest && (
            <div className="blame-newest">
              <div className="row small strong" style={{ color: 'var(--warn)' }}><Icon name="commit" size={14} />Newest change nearby</div>
              <div className="small" style={{ marginTop: 4 }}><span className="mono">{newest.shortCommit}</span> · {newest.authorLogin ? '@' + newest.authorLogin : newest.author}</div>
              <div className="small text-2">{newest.summary}</div>
              <div className="tiny muted" style={{ marginTop: 4 }}>Highlighted lines changed in this commit</div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function Chat({ inv, onUpdate }: { inv: Investigation; onUpdate: (i: Investigation) => void }) {
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const box = useRef<HTMLDivElement>(null);
  // Scroll the chat panel only (never the page) to the newest message.
  useEffect(() => { if (box.current) box.current.scrollTop = box.current.scrollHeight; }, [inv.chat.length, busy]);

  async function send(e: FormEvent) {
    e.preventDefault();
    const text = msg.trim();
    if (!text) return setError('Type a question first.');
    setBusy(true);
    setError('');
    setMsg('');
    onUpdate({ ...inv, chat: [...inv.chat, { role: 'user', text, at: new Date().toISOString() }] });
    try {
      await api(`/api/investigations/${inv.id}/chat`, 'POST', { message: text });
      onUpdate(await api(`/api/investigations/${inv.id}`));
    } catch (err: any) {
      setError(err.message);
      setMsg(text);
      onUpdate(await api(`/api/investigations/${inv.id}`).catch(() => inv));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader title="Ask a follow-up" icon="message" subtitle="The agent can look up fresh data to answer" />
      {inv.chat.length === 0 && !busy ? (
        <EmptyState icon="message" title="Questions about this incident?">Try “Should we roll back the last commit?” or “Is checkout affected too?”</EmptyState>
      ) : (
        <div className="chat" aria-live="polite" ref={box}>
          {inv.chat.map((m, i) => <div key={i} className={`bubble ${m.role}`}>{m.text}</div>)}
          {busy && <div className="bubble assistant muted row"><span className="spinner" />Thinking…</div>}
        </div>
      )}
      <form className="chat-form" onSubmit={send}>
        <label htmlFor="chat-input" className="sr-only">Follow-up question</label>
        <input id="chat-input" className="input" value={msg} maxLength={2000} disabled={busy} placeholder="Ask about this investigation"
               onChange={(e) => { setMsg(e.target.value); setError(''); }} aria-invalid={!!error || undefined} />
        <Button type="submit" variant="primary" icon="send" loading={busy}>Send</Button>
      </form>
      {error && <div className="field-error" style={{ padding: '0 20px 14px' }} role="alert"><Icon name="alert" size={14} />{error}</div>}
    </Card>
  );
}

