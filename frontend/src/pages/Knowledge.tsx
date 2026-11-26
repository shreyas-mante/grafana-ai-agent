import { FormEvent, useEffect, useState } from 'react';
import { api } from '../api';

export default function Knowledge() {
  const [docs, setDocs] = useState<any[]>([]);
  const [q, setQ] = useState('');
  const [results, setResults] = useState<any[] | null>(null);
  const [title, setTitle] = useState('');
  const [type, setType] = useState('runbook');
  const [content, setContent] = useState('');
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');

  const load = () => api<any[]>('/api/knowledge').then(setDocs).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  async function search(e: FormEvent) {
    e.preventDefault();
    if (!q.trim()) return;
    try {
      const r = await api('/api/knowledge/search?q=' + encodeURIComponent(q.trim()));
      setResults(r.results);
    } catch (err: any) {
      setError(err.message);
    }
  }

  async function add(e: FormEvent) {
    e.preventDefault();
    if (!title.trim() || !content.trim()) {
      setError('Add a title and some content.');
      return;
    }
    try {
      await api('/api/knowledge', 'POST', { title: title.trim(), type, content });
      setTitle('');
      setContent('');
      setSaved('Added to the knowledge base');
      setTimeout(() => setSaved(''), 3000);
      load();
    } catch (err: any) {
      setError(err.message);
    }
  }

  return (
    <div className="page">
      <h1>Knowledge base</h1>
      <p className="muted">Runbooks, past incidents and team docs. The agent searches these during every investigation, and finished investigations are saved here automatically.</p>
      {error && <div className="card error-text" onClick={() => setError('')}>{error}</div>}

      <form className="card search" onSubmit={search}>
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Connection is not available, request timed out" maxLength={1000} />
        <button className="primary">Search</button>
      </form>
      {results && (
        <div className="card">
          <h3>Results</h3>
          {results.length === 0 && <div className="muted">No matches.</div>}
          {results.map((h) => (
            <details key={h.docId} className="kb-hit" open>
              <summary><span className="tag">{h.type}</span> {h.title} <span className="muted small">{Math.round(h.score * 100)}% match</span></summary>
              <pre className="excerpt">{h.excerpt}</pre>
            </details>
          ))}
        </div>
      )}

      <div className="grid-2">
        <div className="card">
          <h3>Documents ({docs.length})</h3>
          <table className="table">
            <tbody>
              {docs.map((d) => (
                <tr key={d.id}><td><span className="tag">{d.type}</span></td><td>{d.title}</td><td className="muted small">{d.source.startsWith('auto') ? 'auto-saved' : d.source}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
        <form className="card" onSubmit={add}>
          <h3>Add a runbook or postmortem</h3>
          <label>Title <input value={title} maxLength={200} onChange={(e) => { setTitle(e.target.value); setError(''); }} placeholder="Redis connection storms" /></label>
          <label>Type
            <select value={type} onChange={(e) => setType(e.target.value)}>
              <option value="runbook">Runbook</option>
              <option value="incident">Past incident</option>
              <option value="doc">Doc</option>
            </select>
          </label>
          <label>Content (Markdown)
            <textarea rows={10} value={content} maxLength={200000} onChange={(e) => { setContent(e.target.value); setError(''); }}
                      placeholder={'## Symptoms\n…\n\n## Fix\n…'} />
          </label>
          <div className="row"><button className="primary">Add</button>{saved && <span className="ok-text">{saved}</span>}</div>
          <p className="hint">Secrets like tokens, keys and emails are redacted automatically before saving.</p>
        </form>
      </div>
    </div>
  );
}
