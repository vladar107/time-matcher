# Deployment Guide — Oracle Always-Free VM (free tier)

This runbook deploys Time Matcher to a **free-forever** stack:

| Piece | What | Cost |
|---|---|---|
| Compute | Oracle Cloud **Always-Free ARM** VM (Ampere A1, 2 OCPU / 12 GB), EU region | $0 |
| Database | **Self-hosted `postgres:17`** in the same `docker compose` | $0 |
| Public URL / TLS | **DuckDNS** subdomain + **Caddy** (Let's Encrypt) | $0 |
| Observability | **Grafana Cloud free** via the OpenTelemetry Java agent (one OTLP endpoint) | $0 |
| CI/CD | **GitHub Actions** → build → **GHCR** (public) → SSH `docker compose pull && up -d` | $0 |

The app runs as one `docker compose` on the VM: `app` (image pulled from GHCR) + `postgres` + `caddy` (+ a DuckDNS updater). A push to `main` auto-builds an `arm64` image and rolls it out. Telemetry (traces/metrics/logs) flows to Grafana Cloud over OTLP.

> **How to use this guide:** do the numbered sections **in order** — later steps reuse values from earlier ones. Collect the values into the **`.env` template** (§8) as you go. The repo provides the `Dockerfile`, `docker-compose.prod.yml`, `Caddyfile`, and the deploy workflow; you provide the accounts, the VM, and the secrets.

---

## 0. Values you'll collect (fill these in as you go)

| Value | From | Used in |
|---|---|---|
| VM public IP | §1 Oracle | DuckDNS, GitHub `SSH_HOST` |
| `<name>.duckdns.org` + token | §2 DuckDNS | `PUBLIC_BASE_URL`, DuckDNS updater |
| Grafana OTLP endpoint + instance ID + token | §3 Grafana | `.env` OTEL_* |
| Google client id + secret | §4 Google | `.env` |
| Telegram bot token + your user id + webhook secret | §5 Telegram | `.env` |
| SSH deploy key (pair) | §6 GitHub | GitHub secrets + VM `authorized_keys` |

Your public base URL is **`https://<name>.duckdns.org`** — reused for the Google OAuth redirect and the Telegram webhook.

---

## 1. Oracle Cloud — the VM

1. **Sign up** at [cloud.oracle.com](https://cloud.oracle.com). A **credit card is required for identity verification** (not charged on Always-Free).
2. **Pick an EU home region at signup** (e.g. Frankfurt `eu-frankfurt-1`, Amsterdam `eu-amsterdam-1`, Zürich `eu-zurich-1`). ⚠️ The home region is **permanent** — choose EU now.
3. **Create the instance:** Compute → Instances → Create.
   - Shape: **Ampere (Arm) — VM.Standard.A1.Flex**, **2 OCPUs / 12 GB** (the current Always-Free ceiling).
   - Image: **Ubuntu 24.04**.
   - Add your **SSH public key**.
   - ⚠️ If you see *"Out of host capacity"*, retry across the Availability Domains / a bit later — ARM free capacity is often contended (a well-known annoyance; usually succeeds within a few tries).
   - **Note the assigned public IP.**
4. **Install Docker** on the VM (SSH in as `ubuntu`):
   ```bash
   sudo apt-get update && sudo apt-get install -y docker.io docker-compose-plugin
   sudo usermod -aG docker ubuntu    # log out/in to take effect
   sudo systemctl enable --now docker
   ```
5. **Open ports — in BOTH places** (this is the #1 gotcha on Oracle):
   - **OCI Console:** Networking → your VCN → Security List → add **Ingress** rules for TCP **80**, **443** (source `0.0.0.0/0`); keep **22**.
   - **On the VM (iptables):** Oracle's Ubuntu image blocks everything but 22 at the OS level:
     ```bash
     sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
     sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
     sudo netfilter-persistent save
     ```
6. **Auto-patching (recommended):**
   ```bash
   sudo apt-get install -y unattended-upgrades && sudo dpkg-reconfigure -plow unattended-upgrades
   ```
7. **Keep it non-idle:** Oracle may reclaim Always-Free instances idle >7 days (CPU p95 <10%). The always-on webhook server normally keeps it active; nothing extra needed unless it goes truly idle.

---

## 2. DuckDNS — your public hostname

1. Go to [duckdns.org](https://www.duckdns.org), sign in (GitHub/Google).
2. Create a subdomain **`<name>`** → it becomes **`<name>.duckdns.org`**.
3. Set its **current IP** to the VM's public IP.
4. **Copy your DuckDNS token** (top of the page). The repo's DuckDNS updater uses it to keep the record current if the IP changes.

---

## 3. Grafana Cloud — observability

1. Create a free account at [grafana.com](https://grafana.com) (no card).
2. In your stack, open **Connections → OpenTelemetry (OTLP)**.
3. Collect: the **OTLP endpoint** (`https://otlp-gateway-<region>.grafana.net/otlp`), your **instance ID** (a number), and generate an **API token** (a Cloud Access Policy token with `metrics:write`, `logs:write`, `traces:write`).
4. The app authenticates with header `Authorization: Basic <base64("<instanceID>:<token>")>`. You can precompute it:
   ```bash
   printf '%s' '<instanceID>:<token>' | base64
   ```

---

## 4. Google Cloud — OAuth (calendar connect)

1. Console → **APIs & Services** → enable the **Google Calendar API**.
2. **OAuth consent screen:** External; add **your own Google account as a Test user**.
3. **Credentials → Create OAuth client → type: Web application.**
4. **Authorized redirect URI:** `https://<name>.duckdns.org/oauth/google/callback` (exact match required).
5. **Note the client id + client secret.**

*(This supersedes the old single-refresh-token flow — the bot obtains + stores the token via this OAuth client.)*

---

## 5. Telegram — the bot

1. DM **@BotFather** → `/newbot` → follow prompts → **copy the bot token**.
2. DM **@userinfobot** → note your **numeric user id** (restricts the bot to you).
3. Choose a random **webhook secret**: `openssl rand -hex 16`.

---

## 6. GitHub — deploy access

1. Generate an SSH deploy key pair locally: `ssh-keygen -t ed25519 -f deploy_key -N ''`.
2. Add the **public** half (`deploy_key.pub`) to the VM's `~/.ssh/authorized_keys`.
3. Repo → **Settings → Secrets and variables → Actions** → add **Secrets**:
   - `SSH_HOST` = the VM public IP
   - `SSH_USER` = `ubuntu`
   - `SSH_KEY` = the **private** key (`deploy_key` contents)
4. After the VM is ready and `.env` is in place, enable the deploy job: **Settings → Secrets and variables → Actions → Variables** → add variable `DEPLOY_ENABLED` = `true`. The deploy workflow is gated on this variable; leave it unset until the VM is fully provisioned to avoid failed deploy runs.
5. After the first workflow run pushes the image, make the **GHCR package public**: repo → Packages → the package → Package settings → Change visibility → Public. (Public → the VM pulls with no auth.)
6. **SSH hardening on the VM:** disable password auth (`PasswordAuthentication no` in `sshd_config`), keys only; optional `fail2ban`.

---

## 7. VM prep for the app

Create the deploy directory on the VM. The deploy workflow (`deploy.yml`) automatically scps the `deploy/` folder contents (`docker-compose.prod.yml`, `Caddyfile`) to `/opt/time-matcher` on every deploy — **you do not need to place them manually**.

```bash
sudo mkdir -p /opt/time-matcher && sudo chown ubuntu:ubuntu /opt/time-matcher
```

Place the `.env` file here (see §8) before the first deploy run.

---

## 8. The `.env` file (on the VM, at `/opt/time-matcher/.env`)

Create it once with your collected values. **This file is secret — never commit it** (`.env*` is gitignored).

```dotenv
# --- Database ---
DB_PASSWORD=<choose-a-strong-password>

# --- App / URLs ---
CALENDAR_PROVIDER=google
PUBLIC_BASE_URL=https://<name>.duckdns.org

# --- Google OAuth ---
GOOGLE_CLIENT_ID=<from §4>
GOOGLE_CLIENT_SECRET=<from §4>

# --- Telegram ---
TELEGRAM_BOT_TOKEN=<from §5>
TELEGRAM_HOST_USER_ID=<your numeric id from §5>
TELEGRAM_WEBHOOK_SECRET=<from §5>

# --- Public hostname (DuckDNS) ---
PUBLIC_HOSTNAME=<name>.duckdns.org
DUCKDNS_DOMAIN=<name>
DUCKDNS_TOKEN=<from §2>

# --- Observability (Grafana Cloud OTLP) ---
OTEL_SDK_DISABLED=false
OTEL_SERVICE_NAME=time-matcher
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp-gateway-<region>.grafana.net/otlp
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <base64(instanceID:token) from §3>
OTEL_LOGS_EXPORTER=otlp
```

---

## 9. Deploy

- **Automatic:** push to `main` → GitHub Actions builds the `arm64` image, pushes it to GHCR, SSHes to the VM, and runs `docker compose -f docker-compose.prod.yml pull app && docker compose -f docker-compose.prod.yml up -d`.
- **Manual (first time / debugging), on the VM:**
  ```bash
  cd /opt/time-matcher
  docker compose -f docker-compose.prod.yml up -d
  docker compose -f docker-compose.prod.yml logs -f app
  ```
- On startup the app runs Flyway migrations against Postgres and (with the Telegram env set) **registers its webhook** automatically.

---

## 10. First-run wiring & verification

1. `https://<name>.duckdns.org/` — the booking page loads with a **valid TLS cert** (Caddy issued it).
2. DM your bot **`/connect`** → tap the link → approve in the browser → the callback stores the calendar → the bot replies ✅. Then **`/calendars`** to see it (★ = booking target).
3. Create an event type + working hours (via the bot in a later slice, or the API), then **book a slot** on the page → confirm the event appears in your real Google Calendar.
4. **Grafana Cloud** → Explore → you should see the app's **traces, metrics, and logs**.

---

## 11. Operations

- **Backups:** a daily `pg_dump` runs (repo-provided cron/sidecar) to the VM disk; copy off-box (e.g. OCI Object Storage free tier) for safety. Restore = `psql < dump.sql` into a fresh `postgres` volume.
- **TLS:** Caddy auto-renews; just keep port 80 reachable.
- **Rollback:** images are tagged by commit SHA — redeploy a previous tag (`docker compose … pull` after re-pointing the tag, or `docker run` the old SHA).
- **Logs:** live in Grafana Cloud (and `docker compose logs`).

---

## 12. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Site unreachable on 443 | Ports not open on **both** OCI Security List **and** VM iptables (§1.5). |
| ARM instance won't create | "Out of host capacity" — retry across ADs / later (§1.3). |
| Google "redirect_uri_mismatch" | The OAuth client's redirect URI must **exactly** equal `https://<name>.duckdns.org/oauth/google/callback` (§4.4). |
| Bot doesn't respond | `TELEGRAM_WEBHOOK_SECRET` mismatch, or `PUBLIC_BASE_URL` not the public DuckDNS URL, or the webhook didn't register (check app logs for `setWebhook`). |
| Cert not issued | Port 80 blocked (Let's Encrypt HTTP-01 needs it), or DuckDNS not pointing at the VM IP. |
| No data in Grafana | `OTEL_EXPORTER_OTLP_*` wrong — check the endpoint region + the base64 `Authorization` header. |

---

*The stack is a portable Docker image + Postgres emitting standard OTLP, so nothing here locks you in — you can move to another host or repoint observability with config changes, not a rewrite.*
