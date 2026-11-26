import { useEffect, useState } from 'react';
import { api, ago } from '../api';
import { go } from '../App';

export default function Errors() {
  const [list, setList] = useState<any[] | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    const load = () => api<any[]>('/api/errors').then(setList).catch((e) => setError(e.message));
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, []);

  async function investigate(g: any) {
    try {
      const inv = await api('/api/investigations', 'POST', {
        question: `Why is ${g.httpMethod || ''} ${g.path || g.service} failing with ${g.exceptionClass.split('.').pop()}? Find the root cause in the code and suggest a fix.`,
        service: g.service,
        lookbackMinutes: 15,
        errorGroupId: g.id,
      });
      go('/inv/' + inv.id);
    } catch (e: any) {
      setError(e.message);
    }
  }

  return (
    <div className="page">
      <h1>Errors</h1>
      <p className="muted">Exceptions reported by your applications through the PulseAgent SDK, grouped by type and code location.</p>
      {error && <div className="card error-text">{error}</div>}
      <div className="card">
        {list === null ? <div className="muted">Loading…</div> : list.length === 0 ? (
          <div className="empty">
            <strong>No errors reported yet</strong>
            <p className="muted">Add the SDK to your app (see Integrate). In the demo, run <code>scripts/chaos.sh on</code> to make the payment service fail.</p>
          </div>
        ) : (
          <table className="table">
            <thead><tr><th>Error</th><th>Where</th><th>Count</th><th>Last seen</th><th /></tr></thead>
            <tbody>
              {list.map((g) => (
                <tr key={g.id}>
                  <td>
                    <div className="strong">{g.exceptionClass.split('.').pop()}</div>
                    <div className="muted small clamp">{g.message}</div>
                  </td>
                  <td><div>{g.service}</div><div className="mono tiny muted">{g.httpMethod} {g.path}</div><div className="mono tiny muted">{g.topFrame}</div></td>
                  <td><span className="badge bad">{g.count.toLocaleString()}</span></td>
                  <td className="muted">{ago(g.lastSeen)}</td>
                  <td><button className="small" onClick={() => investigate(g)}>Investigate →</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
