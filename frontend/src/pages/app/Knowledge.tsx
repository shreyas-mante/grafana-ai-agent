import { useState, type FormEvent } from 'react';
import { useFeedback } from '../../components/Feedback';
import { Icon } from '../../components/Icon';
import { Alert, Badge, Button, Card, CardHeader, EmptyState, PageHeader, Select, SkeletonRows, TextArea, TextInput } from '../../components/ui';
import { api } from '../../lib/api';
import { ago } from '../../lib/format';
import { useApi, useDocumentTitle } from '../../lib/hooks';
import type { KnowledgeDoc, KnowledgeHit } from '../../lib/types';

const TYPE_LABEL: Record<string, string> = { runbook: 'Runbook', incident: 'Incident', doc: 'Doc' };

export default function Knowledge() {
  useDocumentTitle('Knowledge base');
  const { toast, confirm } = useFeedback();
  const docs = useApi<KnowledgeDoc[]>('/api/knowledge');
  const [q, setQ] = useState('');
  const [results, setResults] = useState<KnowledgeHit[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [filter, setFilter] = useState('all');
  const [title, setTitle] = useState('');
  const [type, setType] = useState('runbook');
  const [content, setContent] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);

  async function search(e: FormEvent) {
    e.preventDefault();
    if (!q.trim()) return setResults(null);
    setSearching(true);
    try {
      setResults((await api(`/api/knowledge/search?q=${encodeURIComponent(q.trim())}`)).results);
    } catch (err: any) { toast(err.message, 'bad'); } finally { setSearching(false); }
  }

  async function add(e: FormEvent) {
    e.preventDefault();
    const errs: Record<string, string> = {};
    if (!title.trim()) errs.title = 'Add a title.';
    if (!content.trim()) errs.content = 'Add some content.';
    setErrors(errs);
    if (Object.keys(errs).length) return;
    setSaving(true);
    try {
      await api('/api/knowledge', 'POST', { title: title.trim(), type, content });
      setTitle(''); setContent('');
      toast('Added to the knowledge base');
      docs.reload();
    } catch (err: any) { toast(err.message, 'bad'); } finally { setSaving(false); }
  }

  async function remove(d: KnowledgeDoc) {
    if (!(await confirm({ title: `Delete “${d.title}”?`, body: 'The agent won’t use it in future investigations. This can’t be undone.', confirmLabel: 'Delete', danger: true }))) return;
    try { await api(`/api/knowledge/${d.id}`, 'DELETE'); toast('Deleted'); docs.reload(); } catch (err: any) { toast(err.message, 'bad'); }
  }

  const list = (docs.data || []).filter((d) => filter === 'all' || d.type === filter);

  return (
    <div className="page fade-in">
      <PageHeader title="Knowledge base" description="Runbooks, past incidents and team docs. The agent searches these in every investigation, and finished AI investigations are saved here automatically." />

      <Card pad>
        <form className="input-group" onSubmit={search}>
          <div className="input-icon" style={{ flex: 1 }}>
            <Icon name="search" />
            <input className="input" type="search" value={q} maxLength={1000} aria-label="Search the knowledge base" placeholder="Search like the agent does, e.g. “connection is not available, request timed out”" onChange={(e) => setQ(e.target.value)} />
          </div>
          <Button type="submit" variant="primary" loading={searching}>Search</Button>
        </form>
        {results && (
          <div className="stack" style={{ marginTop: 16 }}>
            {results.length === 0 ? <p className="muted">No matches.</p> : results.map((h) => (
              <details key={h.docId} className="disclosure card card-pad" open={results.indexOf(h) === 0}>
                <summary><Icon name="chevronRight" /><Badge tone="neutral">{TYPE_LABEL[h.type] || h.type}</Badge><span className="strong truncate">{h.title}</span><span className="muted small nowrap" style={{ marginLeft: 'auto' }}>{Math.round(h.score * 100)}% match</span></summary>
                <pre className="small text-2" style={{ whiteSpace: 'pre-wrap', fontFamily: 'var(--font)', margin: '10px 0 0 22px' }}>{h.excerpt}</pre>
              </details>
            ))}
          </div>
        )}
      </Card>

      <div className="grid-2 grid-wide-left">
        <Card>
          <CardHeader title="Documents" icon="book" subtitle={`${docs.data?.length ?? 0} total`}>
            <div className="segmented" role="group" aria-label="Filter documents">
              {['all', 'runbook', 'incident', 'doc'].map((t) => <button key={t} aria-pressed={filter === t} onClick={() => setFilter(t)}>{t === 'all' ? 'All' : TYPE_LABEL[t] + 's'}</button>)}
            </div>
          </CardHeader>
          {docs.loading ? <SkeletonRows /> : docs.error ? <div className="card-body"><Alert tone="bad">{docs.error}</Alert></div> : list.length === 0 ? (
            <EmptyState icon="book" title="Nothing here yet">Add your first runbook or postmortem with the form.</EmptyState>
          ) : (
            <div>
              {list.map((d) => (
                <div key={d.id} className="list-row">
                  <span className="list-icon"><Icon name={d.type === 'incident' ? 'alert' : d.type === 'runbook' ? 'book' : 'file'} /></span>
                  <div className="list-main">
                    <div className="strong truncate">{d.title}</div>
                    <div className="muted small">{TYPE_LABEL[d.type]} · {d.source.startsWith('auto') ? 'saved from an investigation' : d.source === 'starter' ? 'starter content' : 'uploaded'} · {ago(d.createdAt)}</div>
                  </div>
                  <Button size="sm" variant="ghost" icon="trash" aria-label={`Delete ${d.title}`} onClick={() => remove(d)} />
                </div>
              ))}
            </div>
          )}
        </Card>

        <Card as="form" onSubmit={add} noValidate>
          <CardHeader title="Add a runbook or postmortem" icon="plus" />
          <div className="card-body form-grid">
            <TextInput label="Title" value={title} maxLength={200} placeholder="Redis connection storms" error={errors.title} onChange={(e) => { setTitle(e.target.value); setErrors({}); }} />
            <Select label="Type" value={type} onChange={(e) => setType(e.target.value)} options={[{ value: 'runbook', label: 'Runbook' }, { value: 'incident', label: 'Past incident' }, { value: 'doc', label: 'Doc' }]} />
            <TextArea label="Content" rows={12} value={content} maxLength={200000} error={errors.content}
                      hint="Markdown works best: use ## Symptoms, ## Cause and ## Fix headings."
                      placeholder={'## Symptoms\n…\n\n## Fix\n…'} onChange={(e) => { setContent(e.target.value); setErrors({}); }} />
            <Alert tone="info">Tokens, keys, passwords and emails are redacted automatically before saving.</Alert>
          </div>
          <div className="card-footer row"><span className="spacer" /><Button type="submit" variant="primary" icon="plus" loading={saving}>Add document</Button></div>
        </Card>
      </div>
    </div>
  );
}
