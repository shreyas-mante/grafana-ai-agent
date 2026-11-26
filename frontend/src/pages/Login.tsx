import { FormEvent, useState } from 'react';
import { api } from '../api';

export default function Login({ onDone }: { onDone: () => void }) {
  const [key, setKey] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!key.trim()) {
      setError('Enter your admin key.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await api('/api/auth/login', 'POST', { apiKey: key.trim() });
      setKey('');
      onDone();
    } catch (err: any) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="center">
      <form className="card login" onSubmit={submit}>
        <div className="brand big"><span className="logo">◉</span> PulseAgent</div>
        <p className="muted">AI incident investigator for Prometheus, your code and your runbooks.</p>
        <label htmlFor="key">Admin key</label>
        <input id="key" type="password" autoComplete="off" spellCheck={false} value={key}
               onChange={(e) => { setKey(e.target.value); setError(''); }} placeholder="pa_admin_…" />
        {error && <div className="error-text">{error}</div>}
        <button className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        <p className="hint">Your key is exchanged for a secure session cookie and isn't stored in the browser.</p>
      </form>
    </div>
  );
}
