# PulseAgent: AI incident investigator for Prometheus, your code, and your runbooks

When an API fails, PulseAgent finds out **why**:

- **Metrics:** queries Prometheus for traffic, errors, p95 latency and saturation, and compares the incident window with the baseline.
- **Code:** maps the stack trace to the **exact file and line at the deployed commit**, with **git blame** (author, commit, PR) and the newest change near the failure.
- **Knowledge (RAG):** searches your runbooks and past incidents, and saves every finished investigation as a new incident.
- **Fix:** proposes a grounded fix and lets you ask follow-up questions in chat.

Ask a question in the UI, call the REST API, or send Grafana alerts to the webhook to start investigations automatically.

**Stack:** Java 21 and Spring Boot 3 backend; React and TypeScript UI (bundled into the jar); Gemini API (free tier) with an offline fallback; Spring Boot starter SDK for error reporting.

```
backend/     Spring Boot API, agent loop, tools, RAG, security
frontend/    React UI (builds into backend/src/main/resources/static)
sdk/         pulseagent-spring-boot-starter: reports exceptions + commit SHA
demo-app/    Payment service with an intentional bug, for the demo
knowledge/   Seed runbooks and past incidents
prometheus/  Local Prometheus config and alert rules
```

## Run locally (5 minutes)

Requires Java 21, Maven, Node 20+, and Prometheus (`brew install maven node prometheus`).

```bash
scripts/gen-keys.sh        # creates .env.local with your keys (chmod 600)
scripts/dev.sh             # builds and starts Prometheus, PulseAgent and the demo service
```

Open http://127.0.0.1:8080 and sign in with `PULSEAGENT_ADMIN_KEY` from `.env.local`.

**Break the demo and watch it find the bug:**
```bash
scripts/chaos.sh on        # the payment gateway gets slow, and the pool exhausts
```
Wait about 30 seconds, then go to **Errors**, choose **Investigate**, and it points to `PaymentService.java` and commit *"run gateway authorization inside the pooled unit of work"*.

**Turn on AI mode (free):** get a key at https://aistudio.google.com/apikey, put it in `.env.local` as `GEMINI_API_KEY=...`, and run `scripts/dev.sh` again. Without a key, PulseAgent runs a deterministic offline investigation.

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
