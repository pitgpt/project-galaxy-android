# Deploy Galaxy Backend with a zero-budget-first setup

This is the first deployment target for the Galaxy Android backend:

- Cloud Run for the FastAPI container.
- Upstash Redis Free for shared production rate limits and session leases.
- Google Secret Manager for credentials.
- No VPS and no permanent VM.

This is a best-effort $0/month setup, not a guarantee that every Google Cloud line item will remain at zero. Cloud Run's compute/request free tier is generous, but network egress, build storage, Secret Manager versions, taxes, and usage outside free quotas can still cost money. Cloud Run's 1 GiB/month free internet-egress allowance is for North America; use us-central1 for the initial cost-minimizing deployment. The mobile app sends audio directly to OpenAI over WebRTC; Galaxy's backend only handles authentication, short-lived session credentials, and lease heartbeats, so the backend does not proxy the audio stream.

## 1. Create the free Redis database

1. Create an account at https://upstash.com/.
2. Create a Redis database on the Free plan.
3. Prefer a US region close to us-central1 if available. The Redis database is small and stores only rate-limit counters and active-session lease records.
4. Copy the TLS connection URL (rediss://...) from the database details. Treat it as a password.

Upstash currently lists its Free Redis plan as one database, 256 MB data, 10 GB monthly bandwidth, and 500,000 commands. If the free quota is exhausted, requests may be rate-limited; do not enable automatic paid upgrades.

## 2. Create the Google Cloud project

1. Open https://console.cloud.google.com/ and create a dedicated project, for example galaxy-backend-dev.
2. Link a billing account if Google requires it. A billing account does not mean a VM must be purchased, but usage beyond free quotas can be billed.
3. Enable Cloud Run, Cloud Build, Artifact Registry, and Secret Manager APIs.
4. Choose us-central1 for this first deployment to maximize the North America free egress allowance.
5. Create a monthly budget and enable notifications. If the Spend cap enforcement option is available for the project, prefer it. Ordinary alert-only budgets do not stop charges automatically.

Google Cloud budget alerts are not hard caps. See https://cloud.google.com/billing/docs/how-to/budgets and https://cloud.google.com/billing/docs/how-to/budgets-spend-caps.

## 3. Prepare Supabase Auth

The Android app already expects Supabase Auth. In the Supabase project:

1. Configure an asymmetric ES256 or RS256 JWT signing key. The backend intentionally rejects unsupported HS256 verification.
2. Record the project URL, project reference, and publishable key.
3. The backend values will be:
   - AUTH_JWKS_URL=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
   - AUTH_ISSUER=https://<project-ref>.supabase.co/auth/v1
   - AUTH_AUDIENCE=authenticated

Do not put the Supabase secret/service-role key in the Android app. The backend only needs the public JWKS endpoint to verify signed access tokens.

## 4. Add backend secrets

Create these three secrets in Google Secret Manager:

- OPENAI_API_KEY: the permanent OpenAI API key. Backend only.
- SAFETY_IDENTIFIER_HMAC_KEY: a fresh random value, at least 32 random bytes.
- REDIS_URL: the Upstash TLS URL beginning with rediss://.

Grant the Cloud Run runtime service account access to these three secrets with Secret Manager Secret Accessor. Pin each secret to a numbered version when attaching it to the service.

Never commit these values, put them in an Android build, or send them in chat.

## 5. Deploy the container

From the repository root, deploy the backend directory as the source directory. The included Dockerfile is used automatically.

Recommended initial Cloud Run settings:

- Service: galaxy-backend
- Region: us-central1
- Container port: 8080 (the container now reads Cloud Run's PORT)
- CPU: 1
- Memory: 512 MiB
- Billing: request-based
- Minimum instances: 0
- Maximum instances: 1 for the first private test
- Concurrency: 40
- Ingress: all
- Authentication at Cloud Run layer: allow unauthenticated requests; Galaxy itself still requires and verifies the Supabase Bearer JWT on protected API routes.

The service's public URL is not a secret. The protected API endpoints remain inaccessible without a valid JWT. /health and /ready are intentionally public liveness/readiness probes and reveal no credentials.

Non-secret runtime variables:

- GALAXY_ENVIRONMENT=production
- AUTH_JWKS_URL as above
- AUTH_ISSUER as above
- AUTH_AUDIENCE=authenticated
- GALAXY_ALLOWED_VOICES=alloy,ash,ballad,coral,echo,sage,shimmer,verse
- GALAXY_ALLOWED_CHARACTER_IDS=*
- REALTIME_MODEL=gpt-realtime
- REALTIME_CLIENT_SECRET_TTL_SECONDS=600
- REALTIME_REQUESTS_PER_MINUTE=5
- REALTIME_SESSIONS_PER_DAY=100
- REALTIME_MAX_ACTIVE_SESSIONS=2
- REALTIME_SESSION_LEASE_SECONDS=90

Attach the three Secret Manager values as runtime environment variables named OPENAI_API_KEY, SAFETY_IDENTIFIER_HMAC_KEY, and REDIS_URL. Do not paste them into ordinary environment-variable fields.

## 6. Verify before connecting Android

After deployment, test:

- GET https://<service-url>/health returns 200 { status: ok }.
- GET https://<service-url>/ready returns 200 { status: ready }; this also verifies Redis connectivity.
- An unauthenticated POST /v1/realtime/sessions returns 401.
- A valid Supabase access token can create a session and receives only a short-lived Realtime client secret.

Do not test with a real OpenAI key until the Cloud Run service has the correct secrets and the expected spend controls are in place.

## 7. Connect the Android app

After the backend URL and Supabase project are confirmed, configure Android build values:

- GALAXY_API_BASE_URL=https://<service-url>
- SUPABASE_URL=https://<project-ref>.supabase.co
- SUPABASE_PUBLISHABLE_KEY=<publishable-key>

These are build-time app settings. The permanent OpenAI API key and Redis URL must never be added to Android.

## Cost and operational guardrails

- Keep minimum instances at 0.
- Keep maximum instances at 1 until the first end-to-end tests pass.
- Do not enable paid Upstash upgrades.
- Keep the Cloud Run monthly budget and notifications enabled.
- Check Cloud Run, Artifact Registry, Cloud Build, Secret Manager, and network egress charges after the first deployment.
- If the spend cap feature is unavailable, understand that email budget alerts alone do not prevent billing.
- The first version has no TURN relay. Restrictive NAT/firewall networks may still need a separate TURN solution later.
- Do not increase the maximum instance count until the Redis-backed limiter and session registry have been tested under concurrent requests.

## Rollback / shutdown

To stop the service, set minimum instances to zero (already the default) and delete the Cloud Run service when it is no longer needed. Also delete unused Artifact Registry images and revoke/delete secrets and the Upstash database if the experiment is abandoned. Deleting the service does not automatically delete every other resource or stop all possible charges.
