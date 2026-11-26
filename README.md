# PulseAgent: AI incident investigator for Prometheus, your code, and your runbooks

When an API fails, PulseAgent finds out **why**, and it's built as a multi-team product:

- **Evidence from everywhere:** Prometheus metrics, Loki logs, Tempo traces, Grafana dashboards, your service map, and app exceptions from the SDK.
- **Code:** the stack trace mapped to the **exact file and line at the deployed commit**, with git blame (author, commit, PR) and the newest change near the failure.
- **Knowledge (RAG):** your runbooks and past incidents; every finished investigation is saved as a new incident.
- **Fix and act:** a grounded fix suggestion, follow-up chat, and **remediations that run only after an owner approves them** (signed webhook or GitHub issue).
- **Ways in:** web UI, REST API, Grafana alert webhook, **embeddable chat widget**, **scheduled checks**, and **Slack** notifications.
- **Platform:** workspaces, accounts and invites, roles, hashed API keys, encrypted per-workspace settings, an audit log, and a durable job queue on Postgres.
- **Bring your own AI:** Gemini or Groq (free tiers), **Claude** (official Anthropic SDK, default `claude-opus-5-5`), OpenAI, or local Ollama. With no key it runs a free offline mode.

**Stack:** Java 21 and Spring Boot 3, PostgreSQL (Neon) or embedded H2, Flyway; React and TypeScript UI bundled into the jar.

```
backend/     Spring Boot API, agent loop, tools, RAG, tenancy, security
frontend/    React UI and public/widget.js (built into backend/src/main/resources/static)
sdk/         pulseagent-spring-boot-starter: reports exceptions + commit SHA
demo-app/    Payment service with an intentional bug, for the demo
knowledge/   Starter runbooks and incidents (copied into each new workspace)
prometheus/  Local Prometheus config and alert rules
```

## Run locally (5 minutes)

Requires Java 21, Maven, Node 20+, and Prometheus (`brew install maven node prometheus`).

```bash
scripts/gen-keys.sh        # creates .env.local with your keys (chmod 600)
scripts/dev.sh             # builds and starts Prometheus, PulseAgent and the demo service
```

Open http://127.0.0.1:8080, choose **API key**, and sign in with `PULSEAGENT_ADMIN_KEY` from `.env.local`. Then use **Settings** to connect integrations and invite people.

**Break the demo and watch it find the bug:**
```bash
scripts/chaos.sh on        # the payment gateway gets slow, and the pool exhausts
```
Wait about 30 seconds, then go to **Errors**, choose **Investigate**, and it points to `PaymentService.java` and commit *"run gateway authorization inside the pooled unit of work"*.

**Turn on AI mode (free):** in **Settings**, then **Integrations**, then **AI provider**, choose `gemini` and paste a key from https://aistudio.google.com/apikey, then choose **Test connection**. Or choose `anthropic` for Claude. Without a key, PulseAgent runs a deterministic offline investigation.

Stop everything with `scripts/stop.sh`.

## Deploy for free on Render

**Full step-by-step guide: [DEPLOY.md](DEPLOY.md)** (Render + Grafana Cloud + Gemini, all free). In short:

1. Push this repo to GitHub (keep it **private**).
2. In Render, choose **New**, then **Blueprint**, and pick the repo. It reads `render.yaml` (free plan, Docker).
3. Enter the secret values when Render asks for them:
   - `APP_ADMIN_KEY_SHA256`, `APP_INGEST_KEY_SHA256`: copy from your `.env.local` (these are hashes, not keys)
   - `GEMINI_API_KEY`: your free Gemini key
   - `APP_PROMETHEUS_URL` (+ `APP_PROMETHEUS_TOKEN`): e.g. a free Grafana Cloud Prometheus endpoint
   - `APP_GITHUB_REPO` + `GITHUB_TOKEN`: a fine-grained, **read-only** token (Contents: read, Pull requests: read)
4. Open the `onrender.com` URL and sign in with your admin key.

On the free plan the service sleeps after 15 minutes idle (the first request takes about 30–60 s), and data in memory resets on restart.

## Integrate your apps

- **SDK:** add `pulseagent-spring-boot-starter`, set `pulseagent.endpoint`, `pulseagent.ingest-key` (from an environment variable) and `pulseagent.commit-sha`.
- **REST API:** `POST /api/v1/investigations` with `X-API-Key`. Poll `GET /api/v1/investigations/{id}`.
- **Grafana alerts:** add a webhook contact point to `/api/v1/webhooks/grafana` with `Authorization: Bearer <ingest key>`.

The **Integrate** page in the UI has copy-paste snippets.

## Security

See [SECURITY.md](SECURITY.md): hashed keys, least-privilege ingest key, HttpOnly SameSite cookies, CSP and headers, rate limits, input validation, read-only tools, secret redaction, prompt-injection defense, and a non-root container.
