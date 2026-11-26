# Security model

PulseAgent reads production telemetry and source code, so it is built to fail closed.

## Secrets
- All secrets come from environment variables (or the host's secret store). None are in git, and `.gitignore` and CI (gitleaks) guard against commits.
- API keys are never stored: the server keeps only their **SHA-256 hashes** and compares in constant time.
- Missing key configuration disables that key type instead of opening access.
- The Gemini key, GitHub token and Prometheus token are sent only in request headers, never in URLs, logs, prompts or API responses. `/api/meta` reports only whether integrations are configured.
- `scripts/gen-keys.sh` writes local keys to `.env.local` with `600` permissions and doesn't print them.

## Authentication and authorization
| Credential | Can do |
|---|---|
| Admin key | Everything: UI login, investigations, knowledge base, REST API |
| Ingest key | Only `POST /api/v1/errors` and `POST /api/v1/webhooks/grafana` |

- The web UI exchanges the admin key for an **HttpOnly, Secure, SameSite=Strict** cookie holding an HMAC-SHA256 signed session (8 h, revoked on logout). JavaScript never holds a key or token.
- Cookie-authenticated writes also require a custom `X-Requested-With` header (CSRF defense in depth).
- Login is rate-limited (10/min per IP) and slowed on failure.

## Hardening
- Strict Content-Security-Policy (`script-src 'self'`, `frame-ancestors 'none'`), `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, HSTS, Permissions-Policy, COOP and CORP.
- Per-IP rate limits for the API, AI endpoints (cost control) and ingest; request bodies are capped at 256 KB, including chunked uploads.
- All inputs are validated with bounded sizes and allow-list patterns. Errors return short generic messages, with no stack traces or internals.
- Only `/actuator/health` is exposed.
- The Docker image runs as a non-root user and contains only the compiled jar, with no source or build tools.

## AI safety
- The model can only *request* tools from a fixed allow-list. The backend validates every argument, enforces time-range, step and series limits, and runs everything **read-only**.
- Tool output, logs, stack traces, code and documents are treated as untrusted data, and the system prompt tells the model to ignore instructions inside them (prompt-injection defense).
- Secrets and personal data (tokens, keys, JWTs, private keys, card-like numbers, emails) are **redacted** before anything is stored or sent to the model.
- Facts like file, line, author, commit and PR are shown from tool results directly, not from the model's wording.
- Step, time and tool-output budgets bound every investigation.

## "Can someone reverse engineer it?"
- All logic, prompts and integrations run on the server. Users only receive the compiled, minified UI bundle, with **no source maps** and console calls stripped. Browser code can never be fully hidden, so it contains nothing secret.
- The backend jar is never distributed. Keep the GitHub repository **private**.

## Data and privacy
- With the free Gemini tier, Google may use prompts to improve its products. Don't send other companies' proprietary code through the free tier; use a paid key, or leave the key empty for offline mode.
- Investigations and error groups are kept in memory, bounded (200 and 500). Knowledge documents persist in `APP_DATA_DIR`.

## Reporting
Please report vulnerabilities privately to the repository owner rather than in a public issue.
