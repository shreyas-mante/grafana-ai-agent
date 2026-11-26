function Snippet({ title, code, children }: { title: string; code: string; children?: any }) {
  return (
    <div className="card">
      <h3>{title}</h3>
      {children}
      <div className="snippet">
        <pre className="code">{code}</pre>
        <button className="small copy" onClick={() => navigator.clipboard?.writeText(code)}>Copy</button>
      </div>
    </div>
  );
}

export default function Integrate() {
  const origin = window.location.origin;
  return (
    <div className="page">
      <h1>Integrate</h1>
      <p className="muted">Four ways to connect your applications. Create keys in Settings → API keys. Use the <strong>ingest key</strong> for apps and webhooks (it can only send errors and alerts), and keep the admin key for people.</p>

      <Snippet title="1. Report exceptions from a Spring Boot app" code={`<dependency>
  <groupId>com.pulseagent</groupId>
  <artifactId>pulseagent-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>

# application.yml
pulseagent:
  enabled: true
  endpoint: ${origin}
  ingest-key: \${PULSEAGENT_INGEST_KEY}   # from an environment variable, never committed
  service: payment-service
  commit-sha: \${GIT_COMMIT}             # lets PulseAgent show the exact line, author and commit`}>
        <p className="muted small">Unhandled exceptions are sent asynchronously with stack frames and the deployed commit. Reporting never blocks or breaks your app.</p>
      </Snippet>

      <Snippet title="2. Start investigations from your backend (REST API)" code={`curl -X POST ${origin}/api/v1/investigations \\
  -H "X-API-Key: $PULSEAGENT_ADMIN_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"question":"Why did p95 latency rise?","service":"payment-service","lookbackMinutes":30}'

# → 202 {"id":"inv_…","status":"QUEUED","statusUrl":"/api/v1/investigations/inv_…"}
curl ${origin}/api/v1/investigations/inv_… -H "X-API-Key: $PULSEAGENT_ADMIN_KEY"`} />

      <Snippet title="3. Embed the chat widget in your internal tools" code={`<script src="${origin}/widget.js"
        data-key="pa_widget_…"
        data-service="payment-service"
        async></script>`}>
        <p className="muted small">Create a <strong>widget</strong> key in Settings → API keys and list the sites allowed to use it. Widget keys can only ask questions and read short answers.</p>
      </Snippet>

      <Snippet title="4. Auto-investigate Grafana alerts (webhook)" code={`Grafana → Alerting → Contact points → New → Webhook
URL:                 ${origin}/api/v1/webhooks/grafana
HTTP method:         POST
Authorization header: Bearer <your ingest key>`}>
        <p className="muted small">Each firing alert starts one investigation. Repeats of the same alert within 10 minutes are ignored.</p>
      </Snippet>
    </div>
  );
}
