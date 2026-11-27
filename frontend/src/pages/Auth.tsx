import { useEffect, useState, type FormEvent } from 'react';
import { Icon } from '../components/Icon';
import { Alert, Button, TextInput } from '../components/ui';
import { api } from '../lib/api';
import { useDocumentTitle } from '../lib/hooks';
import { href, navigate, routes } from '../lib/router';

type Mode = 'login' | 'signup' | 'join';

const POINTS = [
  { icon: 'fileCode' as const, text: 'Exact file, line and commit behind each failure' },
  { icon: 'chart' as const, text: 'Evidence from metrics, logs, traces and runbooks' },
  { icon: 'shield' as const, text: 'Read-only investigations; you approve every fix' },
];

export default function Auth({ mode, invite, onDone }: { mode: Mode; invite?: string; onDone: () => void }) {
  useDocumentTitle(mode === 'login' ? 'Sign in' : mode === 'join' ? 'Join workspace' : 'Create account');
  const [useKey, setUseKey] = useState(false);
  const [signupMode, setSignupMode] = useState<string | null>(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [workspace, setWorkspace] = useState('');
  const [key, setKey] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api('/api/auth/config').then((c) => setSignupMode(c.signup)).catch(() => setSignupMode('invite'));
  }, []);

  const creating = mode === 'signup' || mode === 'join';

  function validate() {
    const e: Record<string, string> = {};
    if (useKey && !creating) {
      if (!key.trim()) e.key = 'Enter your admin key.';
    } else {
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) e.email = 'Enter a valid email address.';
      if (!password) e.password = 'Enter your password.';
      else if (creating && password.length < 12) e.password = 'Use at least 12 characters.';
    }
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  async function submit(ev: FormEvent) {
    ev.preventDefault();
    setFormError('');
    if (!validate()) return;
    setBusy(true);
    try {
      if (!creating && useKey) await api('/api/auth/login', 'POST', { apiKey: key.trim() });
      else if (!creating) await api('/api/auth/login', 'POST', { email: email.trim(), password });
      else await api('/api/auth/signup', 'POST', { email: email.trim(), password, workspaceName: workspace.trim() || null, inviteToken: invite || null });
      setKey('');
      setPassword('');
      navigate(routes.app, true);
      onDone();
    } catch (err: any) {
      setFormError(err.message);
    } finally {
      setBusy(false);
    }
  }

  const signupClosed = mode === 'signup' && signupMode !== null && signupMode !== 'open';

  return (
    <div className="auth">
      <aside className="auth-side">
        <a className="brand brand-light" href={href(routes.home)}><span className="brand-mark"><Icon name="logo" size={18} /></span>PulseAgent</a>
        <div className="auth-side-copy">
          <h2>Investigate incidents in minutes, not hours.</h2>
          <ul>{POINTS.map((p) => <li key={p.text}><Icon name={p.icon} />{p.text}</li>)}</ul>
        </div>
        <p className="auth-side-foot">Sessions use secure, HttpOnly cookies. Keys and passwords are never stored in your browser.</p>
      </aside>

      <main className="auth-main">
        <a className="auth-back" href={href(routes.home)}><Icon name="arrowLeft" size={14} />Back to home</a>
        <div className="auth-card fade-in">
          <h1>{mode === 'login' ? 'Welcome back' : mode === 'join' ? 'Join your team' : 'Create your workspace'}</h1>
          <p className="muted">
            {mode === 'login' ? 'Sign in to your PulseAgent workspace.'
              : mode === 'join' ? 'You’ve been invited to a workspace. Create an account to accept.'
              : 'Free to start. Bring your own AI key later, or run in offline mode.'}
          </p>

          {signupClosed ? (
            <div className="stack" style={{ marginTop: 22 }}>
              <Alert tone="info">This server is invite-only. Ask a workspace owner for an invite link, or sign in if you already have an account.</Alert>
              <a className="btn btn-primary btn-block" href={href(routes.login)}>Go to sign in</a>
            </div>
          ) : (
            <form className="form-grid" style={{ marginTop: 22 }} onSubmit={submit} noValidate>
              {formError && <Alert tone="bad" onClose={() => setFormError('')}>{formError}</Alert>}
              {!creating && useKey ? (
                <TextInput label="Admin API key" type="password" autoComplete="off" spellCheck={false} icon="key" value={key}
                           placeholder="pa_admin_…" error={errors.key} onChange={(e) => { setKey(e.target.value); setErrors({}); }} autoFocus />
              ) : (
                <>
                  <TextInput label="Work email" type="email" autoComplete="email" icon="user" value={email} maxLength={254}
                             placeholder="you@company.com" error={errors.email} onChange={(e) => { setEmail(e.target.value); setErrors({}); }} autoFocus />
                  <TextInput label="Password" type="password" autoComplete={creating ? 'new-password' : 'current-password'} icon="lock" value={password} maxLength={128}
                             placeholder={creating ? 'At least 12 characters' : ''} error={errors.password}
                             hint={creating ? 'Use 12 or more characters. A passphrase works well.' : undefined}
                             onChange={(e) => { setPassword(e.target.value); setErrors({}); }} />
                  {mode === 'signup' && (
                    <TextInput label="Workspace name" value={workspace} maxLength={100} placeholder="Acme platform team" icon="layers"
                               hint="You can rename or invite teammates later." onChange={(e) => setWorkspace(e.target.value)} />
                  )}
                </>
              )}
              <Button type="submit" variant="primary" size="lg" block loading={busy}>
                {mode === 'login' ? 'Sign in' : mode === 'join' ? 'Accept invite' : 'Create workspace'}
              </Button>
              {!creating && (
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setUseKey(!useKey); setErrors({}); setFormError(''); }}>
                  <Icon name={useKey ? 'user' : 'key'} />{useKey ? 'Sign in with email instead' : 'Sign in with an admin API key'}
                </button>
              )}
            </form>
          )}

          <div className="auth-switch muted small">
            {mode === 'login'
              ? (signupMode === 'open' ? <>New here? <a href={href(routes.signup)}>Create a workspace</a></> : <>Need access? Ask a workspace owner for an invite link.</>)
              : <>Already have an account? <a href={href(routes.login)}>Sign in</a></>}
          </div>
        </div>
      </main>
    </div>
  );
}
