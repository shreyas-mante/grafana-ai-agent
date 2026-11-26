import { FormEvent, useEffect, useState } from 'react';
import { api, ago } from '../api';
import { go } from '../App';

export default function Schedules() {
  const [list, setList] = useState<any[]>([]);
  const [name, setName] = useState('');
  const [question, setQuestion] = useState('Is anything unusual with errors, latency or saturation?');
  const [service, setService] = useState('');
  const [interval, setIntervalMin] = useState(60);
  const [error, setError] = useState('');
  const load = () => api<any[]>('/api/schedules').then(setList).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!name.trim() || !question.trim()) return setError('Add a name and a question.');
    try {
      await api('/api/schedules', 'POST', { name: name.trim(), question: question.trim(), service: service.trim() || null, intervalMinutes: interval });
      setName('');
      load();
    } catch (err: any) { setError(err.message); }
  }

  return (
    <div className="page">
      <h1>Scheduled checks</h1>
      <p className="muted">Recurring investigations, like a health check every hour. Results go to Slack if it's connected.</p>
      {error && <div className="card error-text" onClick={() => setError('')}>{error}</div>}
      <div className="grid-2">
        <div className="card">
          {list.length === 0 ? <div className="empty"><strong>No scheduled checks yet</strong><p className="muted">Add one to catch problems before users notice.</p></div> : (
            <table className="table">
              <thead><tr><th>Check</th><th>Every</th><th>Last run</th><th /></tr></thead>
              <tbody>{list.map((s) => (
                <tr key={s.id}>
                  <td><div className="strong">{s.name}</div><div className="muted small">{s.question}</div></td>
                  <td>{s.interval_minutes < 60 ? `${s.interval_minutes} min` : `${s.interval_minutes / 60} h`}</td>
                  <td className="small">{s.last_run_at ? <a href={'#/inv/' + s.last_investigation_id} onClick={(e) => { if (!s.last_investigation_id) e.preventDefault(); }}>{ago(s.last_run_at)}</a> : <span className="muted">not yet</span>}</td>
                  <td className="row">
                    <button className="small" onClick={() => api(`/api/schedules/${s.id}/toggle`, 'POST', { enabled: !s.enabled }).then(load)}>{s.enabled ? 'Pause' : 'Resume'}</button>
                    <button className="small" onClick={() => confirm('Delete this check?') && api(`/api/schedules/${s.id}`, 'DELETE').then(load)}>Delete</button>
                  </td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </div>
        <form className="card" onSubmit={create}>
          <h3>Add a check</h3>
          <label>Name<input value={name} maxLength={100} onChange={(e) => { setName(e.target.value); setError(''); }} placeholder="Payments hourly health" /></label>
          <label>Question<textarea rows={3} value={question} maxLength={1000} onChange={(e) => setQuestion(e.target.value)} /></label>
          <label>Service (optional)<input value={service} maxLength={100} onChange={(e) => setService(e.target.value)} placeholder="payment-service" /></label>
          <label>Every
            <select value={interval} onChange={(e) => setIntervalMin(Number(e.target.value))}>
              {[15, 30, 60, 180, 360, 720, 1440].map((m) => <option key={m} value={m}>{m < 60 ? `${m} minutes` : `${m / 60} hours`}</option>)}
            </select>
          </label>
          <button className="primary small">Add check</button>
          <p className="hint">Each run uses your AI provider's quota. On free tiers, hourly or slower is safest.</p>
          <button type="button" className="ghost small" onClick={() => go('/')}>← Back to investigations</button>
        </form>
      </div>
    </div>
  );
}
