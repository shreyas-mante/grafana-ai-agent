import { useState } from 'react';
import { Icon, type IconName } from '../../components/Icon';
import { Alert, Card, CardHeader, CodeBlock, LinkButton, PageHeader } from '../../components/ui';
import { useDocumentTitle } from '../../lib/hooks';
import { href, routes } from '../../lib/router';

type Id = 'sdk' | 'api' | 'widget' | 'grafana';

export default function Integrate() {
  useDocumentTitle('Integrate');
  const origin = window.location.origin;
  const [tab, setTab] = useState<Id>('sdk');

  const guides: Record<Id, { icon: IconName; title: string; key: string; desc: string; steps: { title: string; body?: string; code?: string }[] }> = {
    sdk: {
      icon: 'code', title: 'Spring Boot SDK', key: 'ingest',
      desc: 'Reports unhandled exceptions with stack frames and the deployed commit, so investigations can point at the exact line. Reporting is asynchronous and never blocks or breaks your app.',
      steps: [
        { title: 'Add the dependency', code: `<dependency>\n  <groupId>com.pulseagent</groupId>\n  <artifactId>pulseagent-spring-boot-starter</artifactId>\n  <version>0.1.0</version>\n</dependency>` },
        { title: 'Configure it', body: 'Keep the key in an environment variable. Never commit it.', code: `# application.yml\npulseagent:\n  enabled: true\n  endpoint: ${origin}\n  ingest-key: \${PULSEAGENT_INGEST_KEY}\n  service: payment-service\n  environment: production\n  commit-sha: \${GIT_COMMIT}   # lets PulseAgent find the exact line, author and commit` },
        { title: 'Deploy and check', body: 'Exceptions appear on the Errors page within seconds.' },
      ],
    },
    api: {
      icon: 'terminal', title: 'REST API', key: 'admin',
      desc: 'Start investigations from CI, chatops bots or your own tools, then poll for the report.',
      steps: [
        { title: 'Start an investigation', code: `curl -X POST ${origin}/api/v1/investigations \\\n  -H "X-API-Key: $PULSEAGENT_ADMIN_KEY" \\\n  -H "Content-Type: application/json" \\\n  -d '{"question":"Why did p95 latency rise?","service":"payment-service","lookbackMinutes":30}'\n\n# 202 → {"id":"inv_…","status":"QUEUED","statusUrl":"/api/v1/investigations/inv_…"}` },
        { title: 'Poll for the result', body: 'Status moves from QUEUED to RUNNING to COMPLETED (or FAILED). The report is in the "report" field.', code: `curl ${origin}/api/v1/investigations/inv_… -H "X-API-Key: $PULSEAGENT_ADMIN_KEY"` },
        { title: 'Send errors without the SDK', code: `curl -X POST ${origin}/api/v1/errors \\\n  -H "X-API-Key: $PULSEAGENT_INGEST_KEY" -H "Content-Type: application/json" \\\n  -d '{"service":"checkout","exceptionClass":"java.lang.IllegalStateException","message":"…",\n       "frames":[{"className":"com.acme.Checkout","methodName":"pay","fileName":"Checkout.java","lineNumber":42}]}'` },
      ],
    },
    widget: {
      icon: 'globe', title: 'Chat widget', key: 'widget',
      desc: 'Adds an “Ask about your system” button to internal tools. Widget keys only work from the sites you allow, and can only ask questions and read short answers.',
      steps: [
        { title: 'Create a widget key', body: 'In Settings → API keys, choose the widget scope and list the exact origins, e.g. https://admin.acme.com.' },
        { title: 'Add the script tag', code: `<script src="${origin}/widget.js"\n        data-key="pa_widget_…"\n        data-service="payment-service"\n        data-title="Ask about payments"\n        async></script>` },
      ],
    },
    grafana: {
      icon: 'bell', title: 'Grafana alerts', key: 'ingest',
      desc: 'Every firing alert starts an investigation automatically. Repeats of the same alert within 10 minutes are ignored.',
      steps: [
        { title: 'Create a contact point', body: 'In Grafana: Alerting → Contact points → Add contact point → Webhook.' },
        { title: 'Fill in the webhook', code: `URL:                   ${origin}/api/v1/webhooks/grafana\nHTTP method:           POST\nAuthorization header:  Bearer <your ingest key>` },
        { title: 'Route alerts to it', body: 'Add the contact point to a notification policy. Include a "service" label on alerts so investigations focus on the right service.' },
      ],
    },
  };

  const g = guides[tab];
  return (
    <div className="page fade-in">
      <PageHeader title="Integrate" description="Connect your apps to PulseAgent. Each integration uses its own least-privilege key."
                  actions={<LinkButton href={href(routes.settings)} icon="key">Manage API keys</LinkButton>} />
      <div className="grid-4">
        {(Object.keys(guides) as Id[]).map((id) => (
          <button key={id} className={`card card-pad`} style={{ textAlign: 'left', cursor: 'pointer', borderColor: tab === id ? 'var(--accent)' : undefined, boxShadow: tab === id ? 'var(--ring)' : undefined }}
                  aria-pressed={tab === id} onClick={() => setTab(id)}>
            <div className="integration-head"><span className="integration-icon"><Icon name={guides[id].icon} /></span><div><div className="strong">{guides[id].title}</div><div className="muted small">{guides[id].key} key</div></div></div>
          </button>
        ))}
      </div>
      <Card>
        <CardHeader title={g.title} icon={g.icon} subtitle={g.desc} />
        <div className="card-body">
          <ol className="stack" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {g.steps.map((s, i) => (
              <li key={i} className="row" style={{ alignItems: 'flex-start', gap: 14 }}>
                <span className="avatar" style={{ background: 'var(--accent-bg)', color: 'var(--accent-text)' }}>{i + 1}</span>
                <div className="stack-sm" style={{ flex: 1, minWidth: 0 }}>
                  <h4 style={{ fontSize: 14, paddingTop: 5 }}>{s.title}</h4>
                  {s.body && <p className="text-2">{s.body}</p>}
                  {s.code && <CodeBlock code={s.code} />}
                </div>
              </li>
            ))}
          </ol>
        </div>
      </Card>
      <Alert tone="info">Use an <b>ingest</b> key for apps and Grafana. It can only send errors and alerts, so a leaked key can’t read your data. Keep <b>admin</b> keys for your own scripts.</Alert>
    </div>
  );
}
