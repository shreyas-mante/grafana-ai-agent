import { useState } from 'react';
import { Icon, type IconName } from '../components/Icon';
import { CodeBlock, LinkButton } from '../components/ui';
import { useDocumentTitle } from '../lib/hooks';
import { href, routes } from '../lib/router';
import ProductPreview from './ProductPreview';
import { useReveal } from '../lib/reveal';

const FEATURES: { icon: IconName; title: string; body: string }[] = [
  { icon: 'fileCode', title: 'Exact line, author and commit', body: 'Stack traces are mapped to the file and line at the deployed commit, with git blame, the linked pull request, and the newest change near the failure.' },
  { icon: 'chart', title: 'Evidence from your telemetry', body: 'Queries Prometheus, Loki and Tempo, compares the incident window with the baseline, and never sends raw samples to the model.' },
  { icon: 'book', title: 'Learns from your runbooks', body: 'Retrieval over runbooks and past postmortems. Every finished investigation becomes a searchable incident for next time.' },
  { icon: 'sparkles', title: 'Grounded fix suggestions', body: 'A likely cause, confidence, a suggested diff and next steps, with every claim tied to a tool result you can inspect.' },
  { icon: 'shield', title: 'Approve before anything runs', body: 'The agent is read-only. Rollbacks and restarts are proposals that an owner approves, sent to your automation with a signed webhook.' },
  { icon: 'bell', title: 'Starts on its own', body: 'Grafana alerts, app exceptions and scheduled health checks start investigations automatically, with results posted to Slack.' },
  { icon: 'message', title: 'Ask follow-up questions', body: 'Chat with the investigation: “Should we roll back?”, “What changed in the last deploy?”, “Is checkout affected too?”' },
  { icon: 'globe', title: 'Embed it anywhere', body: 'A REST API, a Spring Boot SDK, and a one-line chat widget for your internal tools, each with its own least-privilege key.' },
];

const STEPS: { icon: IconName; title: string; body: string }[] = [
  { icon: 'bell', title: 'Something breaks', body: 'An alert fires, an exception is reported, or someone asks “why is checkout slow?”' },
  { icon: 'search', title: 'Evidence is gathered', body: 'Metrics, logs, traces, recent commits, the service map and your runbooks, in parallel and read-only.' },
  { icon: 'target', title: 'The cause is pinpointed', body: 'Signals are correlated against the baseline and the failing code path is traced to the commit that changed it.' },
  { icon: 'checkCircle', title: 'You decide what to do', body: 'Review the report, ask follow-ups, and approve a fix. Nothing changes without a human.' },
];

const INTEGRATIONS = ['Prometheus', 'Grafana', 'Loki', 'Tempo', 'GitHub', 'Slack', 'Spring Boot', 'Gemini', 'Claude', 'OpenAI', 'Groq', 'Ollama', 'PostgreSQL'];

const SECURITY: { icon: IconName; title: string; body: string }[] = [
  { icon: 'users', title: 'Isolated workspaces', body: 'Every query is scoped to the caller’s verified workspace, with owner and member roles.' },
  { icon: 'lock', title: 'Encrypted secrets', body: 'Integration tokens are AES-256-GCM encrypted at rest and never returned to the browser.' },
  { icon: 'key', title: 'Hashed, scoped keys', body: 'Only key hashes are stored. Ingest and widget keys can’t read your data.' },
  { icon: 'network', title: 'SSRF protection', body: 'Outbound URLs must be https and can’t reach private networks or cloud metadata.' },
  { icon: 'eye', title: 'Redaction', body: 'Tokens, passwords and emails are stripped from logs, traces and documents before the model sees them.' },
  { icon: 'list', title: 'Audit log', body: 'Logins, key changes, settings and every approval decision are recorded.' },
];

const SNIPPETS: Record<string, string> = {
  'Spring Boot SDK': `<dependency>
  <groupId>com.pulseagent</groupId>
  <artifactId>pulseagent-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>

# application.yml
pulseagent:
  enabled: true
  endpoint: https://your-pulseagent-host
  ingest-key: \${PULSEAGENT_INGEST_KEY}
  service: payment-service
  commit-sha: \${GIT_COMMIT}`,
  'REST API': `curl -X POST https://your-pulseagent-host/api/v1/investigations \\
  -H "X-API-Key: $PULSEAGENT_ADMIN_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"question":"Why did p95 latency rise?","service":"checkout","lookbackMinutes":30}'`,
  'Chat widget': `<script src="https://your-pulseagent-host/widget.js"
        data-key="pa_widget_…"
        data-service="payment-service"
        async></script>`,
  'Grafana alerts': `Contact point → Webhook
URL:            https://your-pulseagent-host/api/v1/webhooks/grafana
Authorization:  Bearer <ingest key>`,
};

const FAQ: { q: string; a: string }[] = [
  { q: 'Does it change anything in my systems?', a: 'No. Every investigation tool is read-only. The only way to act is a remediation proposal that a workspace owner approves; it’s then sent to your own automation with an HMAC signature, or opened as a GitHub issue.' },
  { q: 'Which AI models can I use?', a: 'Bring your own key: Gemini or Groq (both have free tiers), Claude, OpenAI, or a local model through Ollama. Without a key, PulseAgent still runs a deterministic offline investigation.' },
  { q: 'Is my source code sent to the AI?', a: 'Only about 25 lines around the failing frame, after secrets are redacted. Free AI tiers may use prompts to improve their products, so use a paid key or offline mode for confidential code.' },
  { q: 'Can it run for free?', a: 'Yes. It deploys on Render’s free plan with a free Neon Postgres database and Grafana Cloud’s free tier. The deploy guide walks through it in about 30 minutes.' },
  { q: 'What do I need to instrument?', a: 'Nothing new for metrics: it reads the Prometheus you already have. For code-level answers, add the Spring Boot starter (one dependency) or send errors to the REST API.' },
  { q: 'Can I self-host it?', a: 'Yes. It’s a single Docker image with PostgreSQL. All configuration comes from environment variables and the Settings page.' },
];

export default function Landing({ signedIn }: { signedIn: boolean }) {
  useDocumentTitle('');
  const [snippet, setSnippet] = useState('Spring Boot SDK');
  const [menuOpen, setMenuOpen] = useState(false);
  const nav = [['#features', 'Features'], ['#how', 'How it works'], ['#developers', 'Developers'], ['#security', 'Security'], ['#faq', 'FAQ']];
  useReveal('.l-section-head, .l-step, .l-feature, .l-sec-item, .l-faq-item, .l-dev-code, .l-cta-inner, .l-strip p, .l-strip ul');

  const go = (id: string) => (e: React.MouseEvent) => {
    e.preventDefault();
    setMenuOpen(false);
    document.querySelector(id)?.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <div className="landing">
      <a className="skip-link" href="#main" onClick={go('#main')}>Skip to content</a>
      <header className="l-nav">
        <div className="l-container l-nav-inner">
          <a className="brand" href={href(routes.home)} aria-label="PulseAgent home"><span className="brand-mark"><Icon name="logo" size={18} /></span>PulseAgent</a>
          <nav className={`l-links ${menuOpen ? 'open' : ''}`} aria-label="Main">
            {nav.map(([id, label]) => <a key={id} href={id} onClick={go(id)}>{label}</a>)}
            <div className="l-links-cta">
              {signedIn ? <LinkButton href={href(routes.app)} variant="primary" iconRight="arrowRight">Open app</LinkButton> : <>
                <LinkButton href={href(routes.login)} variant="ghost">Sign in</LinkButton>
                <LinkButton href={href(routes.signup)} variant="primary">Get started</LinkButton>
              </>}
            </div>
          </nav>
          <button className="btn btn-ghost btn-icon l-menu" aria-label={menuOpen ? 'Close menu' : 'Open menu'} aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>
            <Icon name={menuOpen ? 'x' : 'menu'} />
          </button>
        </div>
      </header>

      <main id="main">
        <section className="l-hero">
          <div className="l-glow" aria-hidden="true" />
          <div className="l-container l-hero-grid">
            <div className="l-hero-copy">
              <div className="l-eyebrow"><Icon name="sparkles" size={14} />AI incident investigator for Prometheus and Grafana</div>
              <h1>Find the line of code behind <span className="grad-text">every incident.</span></h1>
              <p className="l-lead">PulseAgent investigates alerts and failing APIs like a senior SRE: it reads your metrics, logs, traces, code and runbooks, then shows the exact commit that caused it and how to fix it.</p>
              <div className="row-wrap l-hero-cta">
                <LinkButton href={href(signedIn ? routes.app : routes.signup)} variant="primary" size="lg" iconRight="arrowRight">{signedIn ? 'Open your workspace' : 'Get started free'}</LinkButton>
                <a className="btn btn-lg" href="#how" onClick={go('#how')}><Icon name="play" />See how it works</a>
              </div>
              <ul className="l-checks">
                <li><Icon name="check" size={15} />Read-only by design</li>
                <li><Icon name="check" size={15} />Bring your own AI, or run offline</li>
                <li><Icon name="check" size={15} />Deploys on free tiers</li>
              </ul>
            </div>
            <div className="l-hero-visual">
              <ProductPreview />
              <p className="l-caption">Example investigation with sample data</p>
            </div>
          </div>
        </section>

        <section className="l-strip" aria-label="Works with">
          <div className="l-container">
            <p>Works with the tools you already run</p>
            <ul>{INTEGRATIONS.map((i) => <li key={i}>{i}</li>)}</ul>
          </div>
        </section>

        <section id="how" className="l-section">
          <div className="l-container">
            <div className="l-section-head">
              <span className="l-kicker">How it works</span>
              <h2>From alert to root cause, without the tab-hopping</h2>
              <p>Dashboards show symptoms. PulseAgent follows the evidence from the symptom to the change that caused it.</p>
            </div>
            <ol className="l-steps">
              {STEPS.map((s, i) => (
                <li key={s.title} className="l-step">
                  <div className="l-step-num">{i + 1}</div>
                  <div className="l-step-icon"><Icon name={s.icon} /></div>
                  <h3>{s.title}</h3>
                  <p>{s.body}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section id="features" className="l-section l-alt">
          <div className="l-container">
            <div className="l-section-head">
              <span className="l-kicker">Features</span>
              <h2>Everything an on-call engineer does in the first 20 minutes</h2>
            </div>
            <div className="l-features">
              {FEATURES.map((f) => (
                <article key={f.title} className="l-feature">
                  <div className="l-feature-icon"><Icon name={f.icon} /></div>
                  <h3>{f.title}</h3>
                  <p>{f.body}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        <section id="developers" className="l-section">
          <div className="l-container l-dev">
            <div className="l-section-head left">
              <span className="l-kicker">Developers</span>
              <h2>Four ways in, each with its own key</h2>
              <p>Report exceptions with one dependency, start investigations from CI, embed the chat in internal tools, or let Grafana alerts trigger it.</p>
              <ul className="l-dev-list">
                <li><Icon name="key" size={16} /><span><b>Ingest keys</b> can only send errors and alerts.</span></li>
                <li><Icon name="globe" size={16} /><span><b>Widget keys</b> only work on the sites you allow.</span></li>
                <li><Icon name="code" size={16} /><span><b>Admin keys</b> unlock the full REST API for your scripts.</span></li>
              </ul>
            </div>
            <div className="l-dev-code">
              <div className="segmented" role="group" aria-label="Integration examples">
                {Object.keys(SNIPPETS).map((k) => <button key={k} aria-pressed={snippet === k} onClick={() => setSnippet(k)}>{k}</button>)}
              </div>
              <CodeBlock code={SNIPPETS[snippet]} />
            </div>
          </div>
        </section>

        <section id="security" className="l-section l-alt">
          <div className="l-container">
            <div className="l-section-head">
              <span className="l-kicker">Security</span>
              <h2>Built for production data from day one</h2>
              <p>PulseAgent reads sensitive telemetry and source code, so it’s built to fail closed.</p>
            </div>
            <div className="l-security">
              {SECURITY.map((s) => (
                <div key={s.title} className="l-sec-item">
                  <Icon name={s.icon} />
                  <div><h3>{s.title}</h3><p>{s.body}</p></div>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section id="faq" className="l-section">
          <div className="l-container l-faq-wrap">
            <div className="l-section-head left">
              <span className="l-kicker">FAQ</span>
              <h2>Questions, answered</h2>
            </div>
            <div className="l-faq">
              {FAQ.map((f) => (
                <details key={f.q} className="disclosure l-faq-item">
                  <summary><Icon name="chevronRight" />{f.q}</summary>
                  <p>{f.a}</p>
                </details>
              ))}
            </div>
          </div>
        </section>

        <section className="l-cta">
          <div className="l-container l-cta-inner">
            <h2>Spend the next incident fixing, not searching.</h2>
            <p>Connect Prometheus and your repo in a few minutes. No credit card, no agents to install.</p>
            <div className="row-wrap" style={{ justifyContent: 'center' }}>
              <LinkButton href={href(signedIn ? routes.app : routes.signup)} variant="primary" size="lg" iconRight="arrowRight">{signedIn ? 'Open your workspace' : 'Get started free'}</LinkButton>
              {!signedIn && <LinkButton href={href(routes.login)} size="lg">Sign in</LinkButton>}
            </div>
          </div>
        </section>
      </main>

      <footer className="l-footer">
        <div className="l-container l-footer-inner">
          <div className="stack-sm">
            <a className="brand" href={href(routes.home)}><span className="brand-mark"><Icon name="logo" size={18} /></span>PulseAgent</a>
            <p className="muted small">AI incident investigation for teams running Prometheus and Grafana.</p>
          </div>
          <nav className="l-footer-links" aria-label="Footer">
            <div><h4>Product</h4><a href="#features" onClick={go('#features')}>Features</a><a href="#how" onClick={go('#how')}>How it works</a><a href="#security" onClick={go('#security')}>Security</a></div>
            <div><h4>Developers</h4><a href="#developers" onClick={go('#developers')}>Integrations</a><a href="#faq" onClick={go('#faq')}>FAQ</a></div>
            <div><h4>Account</h4><a href={href(routes.login)}>Sign in</a><a href={href(routes.signup)}>Create account</a></div>
          </nav>
        </div>
        <div className="l-container l-footer-bottom muted small">
          <span>© {new Date().getFullYear()} PulseAgent</span>
          <span>Grafana, Prometheus, Loki and Tempo are trademarks of their respective owners. PulseAgent is not affiliated with Grafana Labs.</span>
        </div>
      </footer>
    </div>
  );
}
