import { Component, createContext, useCallback, useContext, useEffect, useRef, useState, type ErrorInfo, type ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { Button } from './ui';

/* ---------------- Toasts ---------------- */

type ToastTone = 'ok' | 'bad' | 'info';
interface Toast { id: number; tone: ToastTone; text: string }

interface ConfirmOptions { title: string; body?: ReactNode; confirmLabel?: string; danger?: boolean }

interface FeedbackApi {
  toast: (text: string, tone?: ToastTone) => void;
  confirm: (opts: ConfirmOptions) => Promise<boolean>;
}

const Ctx = createContext<FeedbackApi>({ toast: () => {}, confirm: async () => false });

export const useFeedback = () => useContext(Ctx);

export function FeedbackProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [dialog, setDialog] = useState<(ConfirmOptions & { resolve: (v: boolean) => void }) | null>(null);
  const nextId = useRef(1);

  const toast = useCallback((text: string, tone: ToastTone = 'ok') => {
    const id = nextId.current++;
    setToasts((t) => [...t.slice(-3), { id, tone, text }]);
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), tone === 'bad' ? 7000 : 4000);
  }, []);

  const confirm = useCallback((opts: ConfirmOptions) => new Promise<boolean>((resolve) => setDialog({ ...opts, resolve })), []);

  const close = (v: boolean) => {
    dialog?.resolve(v);
    setDialog(null);
  };

  return (
    <Ctx.Provider value={{ toast, confirm }}>
      {children}
      <div className="toast-stack" aria-live="polite">
        {toasts.map((t) => {
          const icon: IconName = t.tone === 'ok' ? 'checkCircle' : t.tone === 'bad' ? 'xCircle' : 'info';
          return (
            <div key={t.id} className={`toast toast-${t.tone}`} role={t.tone === 'bad' ? 'alert' : 'status'}>
              <Icon name={icon} />
              <span>{t.text}</span>
              <button className="btn btn-ghost btn-sm btn-icon toast-close" aria-label="Dismiss" onClick={() => setToasts((x) => x.filter((y) => y.id !== t.id))}><Icon name="x" /></button>
            </div>
          );
        })}
      </div>
      {dialog && <ConfirmDialog {...dialog} onClose={close} />}
    </Ctx.Provider>
  );
}

function ConfirmDialog({ title, body, confirmLabel = 'Confirm', danger, onClose }: ConfirmOptions & { onClose: (v: boolean) => void }) {
  const confirmRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    confirmRef.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(false); }}>
      <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title">
        <div className="modal-body">
          <div className={`modal-icon ${danger ? 'danger' : ''}`}><Icon name={danger ? 'alert' : 'info'} /></div>
          <div className="stack-sm">
            <h2 id="confirm-title" style={{ fontSize: 16 }}>{title}</h2>
            {body && <div className="text-2">{body}</div>}
          </div>
        </div>
        <div className="modal-actions">
          <Button onClick={() => onClose(false)}>Cancel</Button>
          <button ref={confirmRef} className={`btn ${danger ? 'btn-danger-solid' : 'btn-primary'}`} onClick={() => onClose(true)}>{confirmLabel}</button>
        </div>
      </div>
    </div>
  );
}

/* ---------------- Error boundary ---------------- */

export class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: string }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(_error: Error, _info: ErrorInfo) {
    // Intentionally not logged to the console in production builds (console calls are stripped).
  }

  componentDidUpdate(prev: { resetKey?: string }) {
    if (prev.resetKey !== this.props.resetKey && this.state.failed) this.setState({ failed: false });
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="empty" role="alert">
        <div className="empty-icon"><Icon name="alert" /></div>
        <h3>Something went wrong on this page</h3>
        <p>Try reloading. If it keeps happening, the rest of the app still works.</p>
        <Button icon="refresh" onClick={() => window.location.reload()}>Reload</Button>
      </div>
    );
  }
}
