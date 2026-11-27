import { useState, type FormEvent } from 'react';
import { api } from '../lib/api';
import { navigate, routes } from '../lib/router';
import type { InvestigationStatus } from '../lib/types';
import { useFeedback } from './Feedback';
import { Icon } from './Icon';
import { Badge, Button, Select, TextInput } from './ui';

export function StatusBadge({ status, confidence }: { status: InvestigationStatus; confidence?: string | null }) {
  if (status === 'COMPLETED') {
    const tone = confidence === 'high' ? 'ok' : confidence === 'low' ? 'warn' : 'ok';
    return <Badge tone={tone} icon="checkCircle">{confidence ? `${confidence[0].toUpperCase()}${confidence.slice(1)} confidence` : 'Done'}</Badge>;
  }
  if (status === 'FAILED') return <Badge tone="bad" icon="xCircle">Failed</Badge>;
  return <Badge tone="info"><span className="spinner" style={{ width: 10, height: 10, borderWidth: 1.5 }} />{status === 'QUEUED' ? 'Queued' : 'Investigating'}</Badge>;
}

export function TriggerTag({ trigger }: { trigger: string }) {
  const labels: Record<string, string> = { user: 'Manual', alert: 'Alert', error: 'Error', schedule: 'Scheduled', widget: 'Widget' };
  return <span className="tag">{labels[trigger] || trigger}</span>;
}

const EXAMPLES = [
  'Why is payment-service returning 5xx errors?',
  'Why did p95 latency increase in the last 15 minutes?',
  'Is anything unusual happening right now?',
];

const WINDOWS = [5, 15, 30, 60, 180, 720, 1440].map((m) => ({ value: m, label: m < 60 ? `Last ${m} minutes` : `Last ${m / 60} hour${m > 60 ? 's' : ''}` }));

/** The main "ask a question" box, used on Overview and Investigations. */
export function AskBox({ compact }: { compact?: boolean }) {
  const { toast } = useFeedback();
  const [question, setQuestion] = useState('');
  const [service, setService] = useState('');
  const [lookback, setLookback] = useState(15);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!question.trim()) return setError('Describe what you want investigated.');
    if (service && !/^[A-Za-z0-9._-]+$/.test(service)) return setError('Service names can use letters, numbers, dots, dashes and underscores.');
    setBusy(true);
    try {
      const inv = await api('/api/investigations', 'POST', { question: question.trim(), service: service.trim() || null, lookbackMinutes: lookback });
      toast('Investigation started');
      navigate(routes.investigation(inv.id));
    } catch (err: any) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="ask" onSubmit={submit} noValidate>
      <label htmlFor="ask-q" className="sr-only">What should PulseAgent investigate?</label>
      <textarea id="ask-q" className="ask-input" rows={compact ? 2 : 3} maxLength={1000} value={question}
                placeholder="Describe the problem, e.g. “Why is checkout returning 500s since 10:15?”"
                onChange={(e) => { setQuestion(e.target.value); setError(''); }}
                onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) submit(e as unknown as FormEvent); }} />
      <div className="ask-row">
        <TextInput label="Service (optional)" value={service} maxLength={100} placeholder="payment-service" icon="server" onChange={(e) => { setService(e.target.value); setError(''); }} />
        <Select label="Time window" value={lookback} onChange={(e) => setLookback(Number(e.target.value))} options={WINDOWS} />
        <span className="spacer" />
        <Button type="submit" variant="primary" icon="sparkles" loading={busy}>Investigate</Button>
      </div>
      {error && <div className="field-error" role="alert"><Icon name="alert" size={14} />{error}</div>}
      {!compact && (
        <div className="chips" aria-label="Examples">
          {EXAMPLES.map((x) => <button type="button" key={x} className="chip" onClick={() => setQuestion(x)}>{x}</button>)}
        </div>
      )}
    </form>
  );
}
