import { FormEvent, useEffect, useRef, useState } from 'react';
import { api, ago } from '../api';
import { StatusBadge } from './Investigations';

function Sparkline({ points }: { points: [number, number][] }) {
  if (!points || points.length < 2) return null;
  const xs = points.map((p) => p[0]);
  const ys = points.map((p) => p[1]);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minY = Math.min(...ys), maxY = Math.max(...ys);
  const w = 240, h = 48;
  const path = points.map((p) => {
    const x = maxX === minX ? 0 : ((p[0] - minX) / (maxX - minX)) * w;
    const y = maxY === minY ? h / 2 : h - 4 - ((p[1] - minY) / (maxY - minY)) * (h - 8);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(' ');
  return (
    <svg className="spark" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" role="img" aria-label="Trend">
      <polyline points={path} fill="none" strokeWidth="1.6" />
    </svg>
  );
}

function fmt(v: any) {
  if (v === undefined || v === null || v === '') return '—';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  if (Math.abs(n) >= 100) return n.toFixed(0);
  if (Math.abs(n) >= 1) return n.toFixed(2);
  return n.toPrecision(2);
}

function CodeCard({ c }: { c: any }) {
  const b = c.blame || {};
  const newest = (c.recentChanges || [])[0];
  const commits: (string | null)[] = c.lineCommits || [];
  return (
    <div className="code-card">
      <div className="code-head">
        <span className="mono">{c.file}:{c.line}</span>
        <span className="muted small">at {String(c.ref).slice(0, 7)} · via {c.provider}</span>
      </div>
      {c.note && <div className="hint">{c.note}</div>}
      <div className="code-body">
        <pre className="code">
          {c.lines.map((l: string, i: number) => {
            const n = c.startLine + i;
            const isNew = newest && commits[i] === newest.shortCommit && (c.recentChanges || []).length > 1;
            const cls = 'code-line' + (n === c.line ? ' hot' : '') + (isNew ? ' changed' : '');
            return (
              <div key={n} className={cls} title={commits[i] ? 'Last changed in ' + commits[i] : undefined}>
                <span className="ln">{n}</span><span className="sha">{commits[i] || ''}</span>{l || ' '}
              </div>
            );
          })}
        </pre>
        <div className="blame">
          <div className="blame-title">Last change to this line</div>
          <dl>
            <dt>Author</dt><dd>{b.authorLogin ? '@' + b.authorLogin : b.author || '—'}</dd>
            <dt>Commit</dt><dd className="mono">{b.shortCommit || '—'}</dd>
            <dt>Message</dt><dd>{b.summary || '—'}</dd>
            <dt>Date</dt><dd>{b.date ? new Date(b.date).toLocaleString() : '—'}</dd>
            {b.prNumber && (<><dt>PR</dt><dd>{/^https:\/\/github\.com\//.test(b.prUrl || '')
              ? <a href={b.prUrl} target="_blank" rel="noopener noreferrer">#{b.prNumber} {b.prTitle}</a>
              : <>#{b.prNumber} {b.prTitle}</>}</dd></>)}
          </dl>
          {newest && (c.recentChanges || []).length > 1 && newest.commit !== b.commit && (
            <div className="newest">
              <div className="blame-title">Newest change nearby</div>
              <div><span className="mono">{newest.shortCommit}</span> {newest.authorLogin ? '@' + newest.authorLogin : newest.author}</div>
              <div className="muted">{newest.summary}</div>
              <div className="muted tiny">Highlighted in yellow</div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function Diff({ text }: { text: string }) {
  return (
    <pre className="code diff">
      {text.split('\n').map((l, i) => (
        <div key={i} className={l.startsWith('+') && !l.startsWith('+++') ? 'add' : l.startsWith('-') && !l.startsWith('---') ? 'del' : ''}>{l || ' '}</div>
      ))}
    </pre>
  );
}

export default function InvestigationView({ id }: { id: string }) {
  const [inv, setInv] = useState<any>(null);
  const [error, setError] = useState('');
  const [msg, setMsg] = useState('');
  const [chatBusy, setChatBusy] = useState(false);
  const [chatError, setChatError] = useState('');
  const chatEnd = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let stop = false;
    let timer: any;
    const load = async () => {
      try {
        const d = await api('/api/investigations/' + id);
        if (stop) return;
        setInv(d);
        if (d.status === 'QUEUED' || d.status === 'RUNNING') timer = setTimeout(load, 1500);
      } catch (e: any) {
        setError(e.message);
      }
    };
    load();
    return () => { stop = true; clearTimeout(timer); };
  }, [id]);

  useEffect(() => { chatEnd.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); }, [inv?.chat?.length]);

  async function send(e: FormEvent) {
    e.preventDefault();
    if (!msg.trim()) { setChatError('Type a question first.'); return; }
    setChatBusy(true);
    setChatError('');
    const text = msg.trim();
    setMsg('');
    try {
      await api(`/api/investigations/${id}/chat`, 'POST', { message: text });
      setInv(await api('/api/investigations/' + id));
    } catch (err: any) {
      setChatError(err.message);
      setMsg(text);
    } finally {
      setChatBusy(false);
    }
  }

  if (error) return <div className="page"><div className="card error-text">{error}</div></div>;
  if (!inv) return <div className="page muted">Loading…</div>;

  const r = inv.report || {};
  const running = inv.status === 'QUEUED' || inv.status === 'RUNNING';
  const evidence: any[] = Array.isArray(r.evidence) ? r.evidence : [];

  return (
    <div className="page">
      <a href="#/" className="back">← All investigations</a>
      <div className="title-row">
        <h1>{r.title || inv.question}</h1>
        <StatusBadge status={inv.status} confidence={r.confidence} />
      </div>
      <div className="muted small meta-line">
        {inv.service && <>{inv.service} · </>}last {inv.lookbackMinutes} min · {inv.stepCount} tool calls · {inv.mode === 'offline' ? 'offline mode' : inv.model || 'AI mode'} · started {ago(inv.createdAt)}
        {inv.durationSeconds != null && <> · took {Math.round(inv.durationSeconds)}s</>}
      </div>
      {r.title && <div className="question">“{inv.question}”</div>}
      {inv.status === 'FAILED' && <div className="card error-text">{inv.failure}</div>}

      {evidence.length > 0 && (
        <div className="metrics">
          {evidence.slice(0, 4).map((e, i) => (
            <div className="metric" key={i}>
              <div className="metric-label">{e.signal}</div>
              <div className="metric-value">{fmt(e.after)}</div>
              <div className="metric-sub">was {fmt(e.before)}</div>
            </div>
          ))}
        </div>
      )}

      <div className="grid-2">
        <div className="col">
          <div className="card">
            <h3>Agent steps</h3>
            {inv.steps.length === 0 && <div className="muted small">Planning…</div>}
            <ol className="steps">
              {inv.steps.map((s: any) => (
                <li key={s.index} className={'step ' + s.status}>
                  <span className="step-icon">{s.status === 'running' ? <span className="spinner" /> : s.status === 'failed' ? '✕' : '✓'}</span>
                  <div>
                    <div className="mono small">{s.tool}{s.args?.title ? ` · ${s.args.title}` : ''}</div>
                    {s.summary && <div className="muted small">{s.summary}</div>}
                  </div>
                </li>
              ))}
            </ol>
            {running && <div className="muted small">Working… this usually takes 20 to 60 seconds.</div>}
          </div>

          {inv.charts.length > 0 && (
            <div className="card">
              <h3>Metrics</h3>
              {inv.charts.map((c: any, i: number) => (
                <div key={i} className="chart">
                  <div className="chart-head"><span>{c.title}</span><span className="muted small">{fmt(c.summary?.baselineMean)} → {fmt(c.summary?.recentMean)}</span></div>
                  <Sparkline points={c.points} />
                  <div className="mono tiny muted" title={c.query}>{c.query}</div>
                </div>
              ))}
            </div>
          )}

          {inv.knowledge.length > 0 && (
            <div className="card">
              <h3>From your knowledge base</h3>
              {inv.knowledge.map((k: any) => (
                <details key={k.docId} className="kb-hit">
                  <summary><span className="tag">{k.type}</span> {k.title} <span className="muted small">{Math.round(k.score * 100)}% match</span></summary>
                  <pre className="excerpt">{k.excerpt}</pre>
                </details>
              ))}
            </div>
          )}
        </div>

        <div className="col">
          {inv.report && (
            <div className="card">
              <h3>Likely cause</h3>
              <p className="lead">{r.likelyCause}</p>
              {r.summary && <p>{r.summary}</p>}
              {r.impact && <p><span className="strong">Impact: </span>{r.impact}</p>}
            </div>
          )}

          {inv.code.length > 0 && (
            <div className="card">
              <h3>Code</h3>
              {r.codeFinding?.explanation && <p className="muted">{r.codeFinding.explanation}</p>}
              {inv.code.map((c: any, i: number) => <CodeCard key={i} c={c} />)}
            </div>
          )}

          {r.suggestedFix && (r.suggestedFix.explanation || r.suggestedFix.diff) && (
            <div className="card fix">
              <h3>Suggested fix</h3>
              <p>{r.suggestedFix.explanation}</p>
              {r.suggestedFix.diff && <Diff text={r.suggestedFix.diff} />}
              <p className="hint">{inv.mode === 'offline' ? 'From your knowledge base.' : 'Suggested by AI from the evidence above.'} Review before applying.</p>
            </div>
          )}

          {inv.actions?.length > 0 && (
            <div className="card">
              <h3>Proposed actions</h3>
              <p className="muted small">The agent proposed {inv.actions.length === 1 ? 'an action' : inv.actions.length + ' actions'}. Nothing runs until an owner approves it.</p>
              <a href="#/actions">Review in Actions →</a>
            </div>
          )}

          {inv.report && (Array.isArray(r.nextSteps) || Array.isArray(r.unknowns)) && (
            <div className="card">
              {Array.isArray(r.nextSteps) && r.nextSteps.length > 0 && (<><h3>Next steps</h3><ul>{r.nextSteps.map((s: string, i: number) => <li key={i}>{s}</li>)}</ul></>)}
              {Array.isArray(r.unknowns) && r.unknowns.length > 0 && (<><h3>Not verified</h3><ul className="muted">{r.unknowns.map((s: string, i: number) => <li key={i}>{s}</li>)}</ul></>)}
            </div>
          )}

          {inv.status === 'COMPLETED' && (
            <div className="card">
              <h3>Ask a follow-up</h3>
              <div className="chat">
                {inv.chat.map((m: any, i: number) => (
                  <div key={i} className={'bubble ' + m.role}>{m.text}</div>
                ))}
                {chatBusy && <div className="bubble assistant muted"><span className="spinner" /> Thinking…</div>}
                <div ref={chatEnd} />
              </div>
              <form className="chat-form" onSubmit={send}>
                <input value={msg} maxLength={2000} onChange={(e) => { setMsg(e.target.value); setChatError(''); }}
                       placeholder="Should we roll back the last commit?" disabled={chatBusy} />
                <button className="primary" disabled={chatBusy}>Send</button>
              </form>
              {chatError && <div className="error-text">{chatError}</div>}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
