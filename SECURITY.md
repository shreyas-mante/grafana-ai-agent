# Security model

PulseAgent reads production telemetry and source code for many teams, so it is built to fail closed.

## Tenancy
- Every request resolves to an **Actor** (workspace, identity, role) from verified credentials only. Every database query is scoped to that workspace; IDs from the URL or body never choose the workspace.
- Roles: **owner** (settings, keys, members, approvals), **member** (investigate, knowledge, schedules), **ingest** key (send errors and alerts only), **widget** key (ask questions from allowed sites only).
- Roles are re-checked against the database on every request, so removing someone takes effect immediately.
- Tests cover cross-workspace isolation, role checks, one-time invites and key revocation.

## Secrets
- Passwords: bcrypt (cost 12), at least 12 characters. Login timing is equalised so it doesn't reveal which emails exist.
- API keys: 256-bit random. Only **SHA-256 hashes** are stored; the plain key is shown once.
- Integration secrets (AI keys, tokens, webhooks): **AES-256-GCM** encrypted at rest with `APP_ENCRYPTION_KEY`. They are never returned by the API (the UI only sees "set"), never put in prompts, and never logged.
- Server secrets come only from environment variables. `.gitignore` and CI (gitleaks) keep them out of git.

## Sessions and browser
- The web UI uses an **HttpOnly, Secure, SameSite=Strict** cookie with an HMAC-signed, 8-hour session, revoked on logout. JavaScript never holds a key or token.
- Cookie-authenticated writes also need a custom header (CSRF defence in depth).
- Strict CSP (`script-src 'self'`, `frame-ancestors 'none'`), `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, HSTS, Permissions-Policy, COOP and CORP (cross-origin only for `/widget.js` and the widget API).
- The widget runs in a closed Shadow DOM, renders server text with `textContent` only, and never sends cookies. CORS is allowed only on `/api/widget/**`, and each widget key works only from its allowlisted origins.

## Outbound requests (SSRF)
Every URL a workspace configures must use **https**, may not contain credentials or a query string, and may not resolve to loopback, private, link-local (including cloud metadata `169.254.169.254`), CGNAT, multicast or unspecified addresses. This is checked when settings are saved and again before every request; redirects are never followed. Slack webhooks must be on `hooks.slack.com`. Local-network targets are allowed only when the operator sets `APP_ALLOW_PRIVATE_TARGETS=true` (local development).

## Abuse and cost controls
- Per-IP rate limits for login/sign-up, AI endpoints, ingest and the general API. Request bodies are capped at 256 KB, chunked requests included.
- Per-workspace caps: investigations per hour, scheduled checks, error groups. Old investigations are pruned after 30 days.
- Sign-up is invite-only by default. Each workspace brings its own AI key; only the operator's bootstrap workspace can use the server's environment key.

## AI safety
- The model can only *request* tools from an allowlist that depends on what the workspace configured. The backend validates every argument and enforces time-range, step, series, log-line and trace limits.
- All investigation tools are **read-only**. The only write path is `propose_remediation`, which only creates a pending proposal. Nothing runs until an **owner approves** it, and execution is a separate, audited path (HMAC-signed webhook or GitHub issue).
- Tool output, logs, stack traces, code and documents are treated as untrusted data (prompt-injection defence in the system prompt). Secrets and personal data are redacted before storage or model use.
- Facts like file, line, author, commit and PR are shown from tool results, not from the model's wording.

## Durability
Investigations are stored after every step in PostgreSQL (or H2 locally) and run from a database-backed queue; jobs interrupted by a crash are retried up to 3 times.

## "Can someone reverse engineer it?"
All logic, prompts and integrations run on the server. Users only receive a minified UI bundle with **no source maps** and nothing secret in it. Keep the repository **private**, and the jar and container are never distributed.

## Data and privacy
Free AI tiers may use prompts to improve their products. For other companies' proprietary code, use a paid key (Gemini paid, Claude, or OpenAI), or no AI key (offline mode).

## Reporting
Please report vulnerabilities privately to the repository owner, not in a public issue.
