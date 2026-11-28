import { useState, type FormEvent } from 'react';
import { useFeedback } from '../../components/Feedback';
import { Alert, Badge, Button, Card, CardHeader, EmptyState, PageHeader, Select, SkeletonRows, TextArea, TextInput } from '../../components/ui';
import { api } from '../../lib/api';
import { ago } from '../../lib/format';
import { useApi, useDocumentTitle } from '../../lib/hooks';
import { href, routes } from '../../lib/router';
import type { Schedule } from '../../lib/types';

const INTERVALS = [15, 30, 60, 180, 360, 720, 1440].map((m) => ({ value: m, label: m < 60 ? `Every ${m} minutes` : m === 60 ? 'Every hour' : m === 1440 ? 'Every day' : `Every ${m / 60} hours` }));
const every = (m: number) => INTERVALS.find((i) => i.value === m)?.label || `Every ${m} min`;

export default function Schedules() {
  useDocumentTitle('Scheduled checks');
  const { toast, confirm } = useFeedback();
  const { data, error, loading, reload } = useApi<Schedule[]>('/api/schedules', { poll: 30000 });
  const [name, setName] = useState('');
  const [question, setQuestion] = useState('Is anything unusual with errors, latency or saturation?');
  const [service, setService] = useState('');
  const [interval, setIntervalMin] = useState(60);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);

  async function create(e: FormEvent) {
    e.preventDefault();
    const errs: Record<string, string> = {};
    if (!name.trim()) errs.name = 'Give the check a name.';
    if (!question.trim()) errs.question = 'Add the question to ask.';
    if (service && !/^[A-Za-z0-9._-]+$/.test(service)) errs.service = 'Use letters, numbers, dots, dashes and underscores.';
    setErrors(errs);
    if (Object.keys(errs).length) return;
    setSaving(true);
    try {
      await api('/api/schedules', 'POST', { name: name.trim(), question: question.trim(), service: service.trim() || null, intervalMinutes: interval });
      setName('');
      toast('Scheduled check added');
      reload();
    } catch (err: any) { toast(err.message, 'bad'); } finally { setSaving(false); }
  }

  async function toggle(s: Schedule) {
    try { await api(`/api/schedules/${s.id}/toggle`, 'POST', { enabled: !s.enabled }); toast(s.enabled ? 'Paused' : 'Resumed'); reload(); } catch (e: any) { toast(e.message, 'bad'); }
  }

  async function remove(s: Schedule) {
    if (!(await confirm({ title: `Delete “${s.name}”?`, body: 'Past investigations it created are kept.', confirmLabel: 'Delete', danger: true }))) return;
    try { await api(`/api/schedules/${s.id}`, 'DELETE'); toast('Deleted'); reload(); } catch (e: any) { toast(e.message, 'bad'); }
  }

  return (
    <div className="page fade-in">
      <PageHeader title="Scheduled checks" description="Recurring investigations that catch problems before users notice. Results are posted to Slack when it’s connected." />
      {error && <Alert tone="bad">{error}</Alert>}
      <div className="grid-2 grid-wide-left">
        <Card>
          <CardHeader title="Your checks" icon="clock" />
          {loading ? <SkeletonRows /> : (data || []).length === 0 ? (
            <EmptyState icon="clock" title="No scheduled checks yet">Add an hourly health check for your most important service.</EmptyState>
          ) : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Check</th><th>Frequency</th><th>Last run</th><th /></tr></thead>
                <tbody>
                  {data!.map((s) => (
                    <tr key={s.id}>
                      <td style={{ maxWidth: 320 }}>
                        <div className="row"><span className="strong">{s.name}</span>{!s.enabled && <Badge tone="warn">Paused</Badge>}</div>
                        <div className="muted small clamp-2">{s.question}</div>
                        {s.service && <span className="tag" style={{ marginTop: 4 }}>{s.service}</span>}
                      </td>
                      <td className="small nowrap">{every(s.interval_minutes)}</td>
                      <td className="small nowrap">{s.last_run_at && s.last_investigation_id
                        ? <a href={href(routes.investigation(s.last_investigation_id))}>{ago(s.last_run_at)}</a>
                        : <span className="muted">Not yet</span>}</td>
                      <td className="actions-cell">
                        <Button size="sm" variant="ghost" icon={s.enabled ? 'pause' : 'play'} onClick={() => toggle(s)}>{s.enabled ? 'Pause' : 'Resume'}</Button>
                        <Button size="sm" variant="ghost" icon="trash" aria-label={`Delete ${s.name}`} onClick={() => remove(s)} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card as="form" onSubmit={create} noValidate>
          <CardHeader title="Add a check" icon="plus" />
          <div className="card-body form-grid">
            <TextInput label="Name" value={name} maxLength={100} placeholder="Payments hourly health" error={errors.name} onChange={(e) => { setName(e.target.value); setErrors({}); }} />
            <TextArea label="Question" rows={3} value={question} maxLength={1000} error={errors.question} onChange={(e) => { setQuestion(e.target.value); setErrors({}); }} />
            <TextInput label="Service (optional)" value={service} maxLength={100} placeholder="payment-service" error={errors.service} onChange={(e) => { setService(e.target.value); setErrors({}); }} />
            <Select label="Frequency" value={interval} onChange={(e) => setIntervalMin(Number(e.target.value))} options={INTERVALS}
                    hint="Each run uses your AI provider’s quota. On free tiers, hourly or slower is safest." />
          </div>
          <div className="card-footer row"><span className="spacer" /><Button type="submit" variant="primary" icon="plus" loading={saving}>Add check</Button></div>
        </Card>
      </div>
    </div>
  );
}
