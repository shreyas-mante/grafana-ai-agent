import { useId, useState, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { Icon, type IconName } from './Icon';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'dark';
type Size = 'sm' | 'md' | 'lg';

export function Button({ variant = 'secondary', size = 'md', icon, iconRight, loading, block, children, className = '', ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: Size; icon?: IconName; iconRight?: IconName; loading?: boolean; block?: boolean }) {
  const cls = ['btn', variant !== 'secondary' && `btn-${variant}`, size !== 'md' && `btn-${size}`, !children && 'btn-icon', block && 'btn-block', className]
    .filter(Boolean).join(' ');
  return (
    <button type="button" className={cls} disabled={loading || rest.disabled} aria-busy={loading || undefined} {...rest}>
      {loading ? <span className="spinner" aria-hidden="true" /> : icon && <Icon name={icon} />}
      {children}
      {iconRight && !loading && <Icon name={iconRight} />}
    </button>
  );
}

export function LinkButton({ href, variant = 'secondary', size = 'md', icon, iconRight, children, external, className = '' }:
  { href: string; variant?: Variant; size?: Size; icon?: IconName; iconRight?: IconName; children: ReactNode; external?: boolean; className?: string }) {
  const cls = ['btn', variant !== 'secondary' && `btn-${variant}`, size !== 'md' && `btn-${size}`, className].filter(Boolean).join(' ');
  return (
    <a className={cls} href={href} {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}>
      {icon && <Icon name={icon} />}{children}{iconRight && <Icon name={iconRight} />}
    </a>
  );
}

export function Card({ children, className = '', pad, as: Tag = 'section', ...rest }: { children: ReactNode; className?: string; pad?: boolean; as?: 'section' | 'div' | 'form' | 'article' } & Record<string, unknown>) {
  const T = Tag as any;
  return <T className={`card ${pad ? 'card-pad' : ''} ${className}`} {...rest}>{children}</T>;
}

export function CardHeader({ title, icon, children, subtitle }: { title: ReactNode; icon?: IconName; children?: ReactNode; subtitle?: ReactNode }) {
  return (
    <div className="card-header">
      {icon && <span className="muted"><Icon name={icon} size={17} /></span>}
      <div style={{ flex: 1, minWidth: 0 }}>
        <h3>{title}</h3>
        {subtitle && <div className="muted small">{subtitle}</div>}
      </div>
      {children}
    </div>
  );
}

export type Tone = 'ok' | 'bad' | 'warn' | 'info' | 'neutral';

export function Badge({ tone = 'neutral', icon, children }: { tone?: Tone; icon?: IconName; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{icon && <Icon name={icon} />}{children}</span>;
}

export function Field({ label, hint, error, children, htmlFor }: { label?: ReactNode; hint?: ReactNode; error?: string | null; children: ReactNode; htmlFor?: string }) {
  return (
    <div className="field">
      {label && <label className="field-label" htmlFor={htmlFor}>{label}</label>}
      {children}
      {error ? <div className="field-error" role="alert"><Icon name="alert" size={14} />{error}</div> : hint && <div className="field-hint">{hint}</div>}
    </div>
  );
}

export function TextInput({ label, hint, error, icon, ...rest }: InputHTMLAttributes<HTMLInputElement> & { label?: ReactNode; hint?: ReactNode; error?: string | null; icon?: IconName }) {
  const id = useId();
  const input = <input id={id} className="input" aria-invalid={!!error || undefined} {...rest} />;
  return (
    <Field label={label} hint={hint} error={error} htmlFor={id}>
      {icon ? <div className="input-icon"><Icon name={icon} />{input}</div> : input}
    </Field>
  );
}

export function TextArea({ label, hint, error, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement> & { label?: ReactNode; hint?: ReactNode; error?: string | null }) {
  const id = useId();
  return <Field label={label} hint={hint} error={error} htmlFor={id}><textarea id={id} className="textarea" aria-invalid={!!error || undefined} {...rest} /></Field>;
}

export function Select({ label, hint, options, ...rest }: SelectHTMLAttributes<HTMLSelectElement> & { label?: ReactNode; hint?: ReactNode; options: { value: string | number; label: string }[] }) {
  const id = useId();
  return (
    <Field label={label} hint={hint} htmlFor={id}>
      <select id={id} className="select" {...rest}>{options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}</select>
    </Field>
  );
}

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: { id: T; label: string; icon?: IconName }[]; value: T; onChange: (t: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.id} role="tab" className="tab" aria-selected={value === t.id} onClick={() => onChange(t.id)}>
          {t.icon && <Icon name={t.icon} />}{t.label}
        </button>
      ))}
    </div>
  );
}

export function EmptyState({ icon = 'inbox', title, children, action }: { icon?: IconName; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-icon"><Icon name={icon} /></div>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action && <div style={{ marginTop: 6 }}>{action}</div>}
    </div>
  );
}

export function Alert({ tone = 'info', children, onClose }: { tone?: 'bad' | 'warn' | 'ok' | 'info'; children: ReactNode; onClose?: () => void }) {
  const icon: IconName = tone === 'bad' ? 'xCircle' : tone === 'warn' ? 'alert' : tone === 'ok' ? 'checkCircle' : 'info';
  return (
    <div className={`alert alert-${tone}`} role={tone === 'bad' ? 'alert' : 'status'}>
      <Icon name={icon} />
      <div style={{ flex: 1 }}>{children}</div>
      {onClose && <button className="btn btn-ghost btn-sm btn-icon" aria-label="Dismiss" onClick={onClose}><Icon name="x" /></button>}
    </div>
  );
}

export function Skeleton({ width = '100%', height = 14, style }: { width?: number | string; height?: number; style?: React.CSSProperties }) {
  return <div className="skeleton" style={{ width, height, ...style }} aria-hidden="true" />;
}

export function SkeletonRows({ rows = 4 }: { rows?: number }) {
  return (
    <div className="card-body stack" aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="row"><Skeleton width={28} height={28} style={{ borderRadius: 8 }} /><div className="stack-sm" style={{ flex: 1 }}><Skeleton width="45%" /><Skeleton width="70%" height={11} /></div></div>
      ))}
    </div>
  );
}

export function Stat({ label, icon, value, sub, tone }: { label: string; icon?: IconName; value: ReactNode; sub?: ReactNode; tone?: 'bad' | 'ok' }) {
  return (
    <div className="card stat">
      <div className="stat-label">{icon && <Icon name={icon} />}{label}</div>
      <div className={`stat-value ${tone || ''}`}>{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <Button size="sm" icon={done ? 'check' : 'copy'} className="copy-btn" aria-label={label}
            onClick={() => { navigator.clipboard?.writeText(text).then(() => { setDone(true); setTimeout(() => setDone(false), 1500); }); }}>
      {done ? 'Copied' : label}
    </Button>
  );
}

export function CodeBlock({ code, copy = true }: { code: string; copy?: boolean }) {
  return (
    <div className="code-block">
      <pre><code>{code}</code></pre>
      {copy && <CopyButton text={code} />}
    </div>
  );
}

export function PageHeader({ title, description, actions, back }: { title: ReactNode; description?: ReactNode; actions?: ReactNode; back?: { href: string; label: string } }) {
  return (
    <header className="page-header">
      {back && <a className="back-link" href={back.href}><Icon name="arrowLeft" size={14} />{back.label}</a>}
      <div className="page-header-row">
        <div style={{ minWidth: 0, flex: 1 }}>
          <h1>{title}</h1>
          {description && <p className="page-desc">{description}</p>}
        </div>
        {actions && <div className="row-wrap">{actions}</div>}
      </div>
    </header>
  );
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return <span className="row muted small" role="status"><span className="spinner" aria-hidden="true" />{label}</span>;
}
