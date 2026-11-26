import { useEffect, useState } from 'react';
import { api, ago } from '../api';

export default function Actions({ isOwner }: { isOwner: boolean }) {
  const [list, setList] = useState<any[]>([]);
  const [busy, setBusy] = useState('');
  const [msg, setMsg] = useState('');
  const load = () => api<any[]>('/api/actions').then(setList);
  useEffect(() => { load(); const t = setInterval(load, 5000); return () => clearInterval(t); }, []);

  async function decide(id: string, verb: 'approve' | 'reject') {
    if (verb === 'approve' && !confirm('Approve and run this action now?')) return;
    setBusy(id);
    try {
      const r = await api(`/api/actions/${id}/${verb}`, 'POST');
      setMsg(r.result || 'Rejected');
      load();
    } catch (e: any) { setMsg(e.message); } finally { setBusy(''); }
  }

  const payload = (p: string) => { try { return JSON.parse(p); } catch { return {}; } };

  return (
    <div className="page">
      <h1>Actions</h1>
      <p className="muted">Remediations the agent proposed. Nothing runs until an owner approves it, and every decision is in the audit log.</p>
      {msg && <div className="card small" onClick={() => setMsg('')}>{msg}</div>}
      <div className="card">
        {list.length === 0 ? <div className="empty"><strong>No proposed actions</strong><p className="muted">Connect a remediation webhook or a GitHub issues token in Settings, and the agent can propose fixes for you to approve.</p></div> : (
          <table className="table">
            <thead><tr><th>Action</th><th>Why</th><th>Status</th><th /></tr></thead>
            <tbody>{list.map((a) => {
              const p = payload(a.payload);
              return (
                <tr key={a.id}>
                  <td><div className="strong">{a.title}</div><div className="small muted"><span className="tag">{p.kind || a.type}</span> {p.target}</div>
                    {a.investigation_id && <a className="small" href={'#/inv/' + a.investigation_id}>View investigation</a>}</td>
                  <td className="small">{a.reason}</td>
                  <td><span className={a.status === 'PENDING' ? 'badge info' : a.status === 'EXECUTED' ? 'badge ok' : a.status === 'FAILED' ? 'badge bad' : 'tag'}>{a.status.toLowerCase()}</span>
                    <div className="muted tiny">{a.decided_by ? `${a.decided_by} · ${ago(a.decided_at)}` : ago(a.proposed_at)}</div>
                    {a.result && <div className="tiny">{a.result}</div>}</td>
                  <td className="row">
                    {a.status === 'PENDING' && isOwner && (<>
                      <button className="primary small" disabled={busy === a.id} onClick={() => decide(a.id, 'approve')}>Approve</button>
                      <button className="small" disabled={busy === a.id} onClick={() => decide(a.id, 'reject')}>Reject</button>
                    </>)}
                    {a.status === 'PENDING' && !isOwner && <span className="muted small">Waiting for an owner</span>}
                  </td>
                </tr>
              );
            })}</tbody>
          </table>
        )}
      </div>
    </div>
  );
}
