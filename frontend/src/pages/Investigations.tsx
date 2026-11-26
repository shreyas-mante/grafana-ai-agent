import { FormEvent, useEffect, useState } from 'react';
import { api, ago } from '../api';
import { go } from '../App';

const EXAMPLES = [
  'Why is payment-service returning 5xx errors?',
  'Why did p95 latency increase in the last 15 minutes?',
  'Is anything unusual happening right now?',
];

export function StatusBadge({ status, confidence }: { status: string; confidence?: string }) {
  if (status === 'COMPLETED') {
    return <span className="badge ok">{confidence ? `Done · ${confidence} confidence` : 'Done'}</span>;
  }
  if (status === 'FAILED') return <span className="badge bad">Failed</span>;
  return <span className="badge info"><span className="spinner" /> {status === 'QUEUED' ? 'Queued' : 'Investigating'}</span>;
}

export default function Investigations() {
  const [question, setQuestion] = useState('');
  const [service, setService] = useState('payment-service');
  const [lookback, setLookback] = useState(15);
  const [list, setList] = useState<any[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const load = () => api<any[]>('/api/investigations').then(setList).catch(() => {});
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!question.trim()) {
      setError('Describe what you want investigated.');
      return;
    }
    if (service && !/^[A-Za-z0-9._-]+$/.test(service)) {
      setError('Service names can only use letters, numbers, dots, dashes and underscores.');
      return;
    }
    setBusy(true);
    try {
      const inv = await api('/api/investigations', 'POST', { question: question.trim(), service: service.trim() || null, lookbackMinutes: lookback });
      go('/inv/' + inv.id);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <h1>Investigations</h1>
      <form className="card ask" onSubmit={submit}>
        <textarea rows={2} value={question} maxLength={1000}
                  onChange={(e) => { setQuestion(e.target.value); setError(''); }}
                  placeholder="Why is payment-service returning 5xx errors?" />
        <div className="ask-row">
          <label>Service <input value={service} maxLength={100} onChange={(e) => { setService(e.target.value); setError(''); }} placeholder="payment-service" /></label>
          <label>Window
            <select value={lookback} onChange={(e) => setLookback(Number(e.target.value))}>
              {[5, 15, 30, 60, 180, 720, 1440].map((m) => <option key={m} value={m}>{m < 60 ? `${m} min` : `${m / 60} h`}</option>)}
            </select>
          </label>
          <span className="spacer" />
          <button className="primary" disabled={busy}>{busy ? 'Starting…' : 'Investigate →'}</button>
        </div>
        {error && <div className="error-text">{error}</div>}
        <div className="chips">
          {EXAMPLES.map((x) => <button type="button" key={x} className="chip" onClick={() => setQuestion(x)}>{x}</button>)}
        </div>
      </form>

      <div className="card">
        {list.length === 0 ? (
          <div className="empty">
            <strong>Start your first investigation</strong>
            <p className="muted">Ask a question above, or open an error from the Errors page. Alerts sent to the webhook start investigations automatically.</p>
          </div>
        ) : (
          <table className="table">
            <thead><tr><th>Investigation</th><th>Service</th><th>Trigger</th><th>Status</th><th>Started</th></tr></thead>
            <tbody>
              {list.map((i) => (
                <tr key={i.id} className="clickable" onClick={() => go('/inv/' + i.id)}>
                  <td><div className="strong">{i.title || i.question}</div>{i.title && <div className="muted small">{i.question}</div>}</td>
                  <td>{i.service || '—'}</td>
                  <td><span className="tag">{i.trigger_type}</span></td>
                  <td><StatusBadge status={i.status} confidence={i.confidence} /></td>
                  <td className="muted">{ago(i.created_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
