import { FormEvent, useEffect, useState } from 'react';
import { api } from '../api';

export default function Login({ onDone, invite }: { onDone: () => void; invite?: string }) {
  const [mode, setMode] = useState<'signin' | 'signup' | 'key'>(invite ? 'signup' : 'signin');
  const [signupMode, setSignupMode] = useState('invite');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [workspace, setWorkspace] = useState('');
  const [key, setKey] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api('/api/auth/config').then((c) => setSignupMode(c.signup)).catch(() => {});
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    if (mode === 'key' && !key.trim()) return setError('Enter your admin key.');
    if (mode !== 'key' && (!email.trim() || !password)) return setError('Enter your email and password.');
    if (mode === 'signup' && password.length < 12) return setError('Use at least 12 characters for your password.');
    setBusy(true);
    try {
      if (mode === 'key') await api('/api/auth/login', 'POST', { apiKey: key.trim() });
      else if (mode === 'signin') await api('/api/auth/login', 'POST', { email: email.trim(), password });
      else await api('/api/auth/signup', 'POST', { email: email.trim(), password, workspaceName: workspace.trim() || null, inviteToken: invite || null });
      setKey('');
      setPassword('');
      if (invite) window.location.hash = '#/';
      onDone();
    } catch (err: any) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  const canSignUp = signupMode === 'open' || !!invite;

  return (
    <div className="center">
      <form className="card login" onSubmit={submit}>
        <div className="brand big"><span className="logo">◉</span> PulseAgent</div>
        <p className="muted">AI incident investigator for your metrics, logs, traces, code and runbooks.</p>
        {invite && <div className="badge info" style={{ marginBottom: 10 }}>You've been invited to a workspace</div>}
        <div className="tabs">
          <button type="button" className={mode === 'signin' ? 'tab active' : 'tab'} onClick={() => setMode('signin')}>Sign in</button>
          {canSignUp && <button type="button" className={mode === 'signup' ? 'tab active' : 'tab'} onClick={() => setMode('signup')}>Create account</button>}
          <button type="button" className={mode === 'key' ? 'tab active' : 'tab'} onClick={() => setMode('key')}>API key</button>
        </div>
        {mode === 'key' ? (
          <label>Admin key
            <input type="password" autoComplete="off" spellCheck={false} value={key} onChange={(e) => { setKey(e.target.value); setError(''); }} placeholder="pa_admin_…" />
          </label>
        ) : (
          <>
            <label>Email<input type="email" autoComplete="email" value={email} maxLength={254} onChange={(e) => { setEmail(e.target.value); setError(''); }} placeholder="you@company.com" /></label>
            <label>Password<input type="password" autoComplete={mode === 'signup' ? 'new-password' : 'current-password'} value={password} maxLength={128}
                                  onChange={(e) => { setPassword(e.target.value); setError(''); }} placeholder={mode === 'signup' ? 'At least 12 characters' : ''} /></label>
            {mode === 'signup' && !invite && (
              <label>Workspace name<input value={workspace} maxLength={100} onChange={(e) => setWorkspace(e.target.value)} placeholder="Acme platform team" /></label>
            )}
          </>
        )}
        {error && <div className="error-text">{error}</div>}
        <button className="primary wide" disabled={busy}>{busy ? 'Please wait…' : mode === 'signup' ? (invite ? 'Join workspace' : 'Create account') : 'Sign in'}</button>
        <p className="hint">You stay signed in with a secure, HttpOnly cookie. Keys and passwords are never stored in the browser.</p>
      </form>
    </div>
  );
}
