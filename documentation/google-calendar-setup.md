# Google Calendar Setup

This guide walks through the one-time steps to connect Time Matcher to your Google Calendar.

## 1. Create a Google Cloud project

1. Go to [Google Cloud Console](https://console.cloud.google.com/) and sign in.
2. Click **Select a project** → **New Project**. Give it a name (e.g. `time-matcher`) and click **Create**.

## 2. Enable the Google Calendar API

1. In the left sidebar, go to **APIs & Services** → **Library**.
2. Search for **Google Calendar API** and click **Enable**.

## 3. Configure the OAuth consent screen

1. Go to **APIs & Services** → **OAuth consent screen**.
2. Choose **External** (unless you have a Google Workspace domain), then click **Create**.
3. Fill in the required fields (app name, support email). Under **Scopes** you can skip for now.
4. Under **Test users**, click **Add users** and add your own Google account email. This lets you authenticate while the app is in "Testing" mode.
5. Save and continue.

## 4. Create an OAuth client (Web application type)

1. Go to **APIs & Services** → **Credentials**.
2. Click **Create credentials** → **OAuth client ID**.
3. Choose **Web application** as the application type. Give it a name.
4. Under **Authorized redirect URIs**, click **Add URI** and enter:
   ```
   https://<your-public-host>/oauth/google/callback
   ```
   For local testing with cloudflared use the cloudflared HTTPS URL (see step 6). You can add multiple URIs — add both the cloudflared URL and any deployed URL.
5. Click **Create**. Copy the **Client ID** and **Client Secret** — you will need them below.

## 5. Create a Telegram bot with BotFather

1. Open Telegram and start a chat with [@BotFather](https://t.me/BotFather).
2. Send `/newbot`, follow the prompts, and copy the **bot token** it gives you.
3. Find your own Telegram user ID (e.g. via [@userinfobot](https://t.me/userinfobot)). Copy this number — it becomes `TELEGRAM_HOST_USER_ID`. The bot silently ignores all messages from any other user ID.

## 6. Run with Docker Compose

The recommended way to run Time Matcher is with Docker Compose, which starts the app and a Postgres database together.

### 6a. Configure environment variables

Copy `.env.example` to `.env` (gitignored) and fill in your values:

```bash
cp .env.example .env
```

```dotenv
DB_PASSWORD=timematcher          # Postgres password (used by both db and app services)
CALENDAR_PROVIDER=google         # set to "google" to enable Google Calendar
PUBLIC_BASE_URL=https://your-host.example.com   # public HTTPS base URL (see 6b)
GOOGLE_CLIENT_ID=<your client ID>
GOOGLE_CLIENT_SECRET=<your client secret>
TELEGRAM_BOT_TOKEN=<your bot token>
TELEGRAM_HOST_USER_ID=<your Telegram user ID>
TELEGRAM_WEBHOOK_SECRET=<random secret string>   # e.g. output of: openssl rand -hex 32
```

### 6b. Webhooks and OAuth need a public HTTPS URL

Both the Telegram webhook and the Google OAuth callback require a public HTTPS URL. Set `PUBLIC_BASE_URL` to that URL in `.env`. The app registers the webhook at startup (`POST /telegram/webhook/{TELEGRAM_WEBHOOK_SECRET}`) and uses the same base URL for OAuth callbacks (`/oauth/google/callback`).

**For local testing:** use the bundled cloudflared tunnel profile, which assigns a temporary `trycloudflare.com` HTTPS URL:

```bash
docker compose --profile tunnel up
```

Copy the printed cloudflared URL (e.g. `https://abc123.trycloudflare.com`) into `PUBLIC_BASE_URL` in `.env`, and add `https://abc123.trycloudflare.com/oauth/google/callback` to the Authorized redirect URIs in your Google Cloud project (step 4). Then restart:

```bash
docker compose --profile tunnel up --build
```

### 6c. Start

```bash
docker compose up --build
```

This builds the image (including the JDK 25 AOT training pass for fast cold start), starts Postgres, and then starts the app. Flyway migrations run automatically on startup.

## 7. Dev mode (no Docker)

For development without Docker, `./gradlew run` works with H2 (no Postgres required):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
cd time-matcher
CALENDAR_PROVIDER=google \
PUBLIC_BASE_URL=https://your-cloudflared-url.trycloudflare.com \
GOOGLE_CLIENT_ID=... \
GOOGLE_CLIENT_SECRET=... \
TELEGRAM_BOT_TOKEN=... \
TELEGRAM_HOST_USER_ID=... \
TELEGRAM_WEBHOOK_SECRET=... \
  ./gradlew run
```

Note: the webhook registration at startup requires a live network connection to Telegram. The OAuth callback and webhook endpoint are functional once the server is reachable at `PUBLIC_BASE_URL`.

### How env vars map to `application.yaml`

The committed `application.yaml` uses Ktor's YAML environment-variable substitution (`"$VAR:default"`):

```yaml
db:
    url: "$DB_URL:jdbc:h2:file:./data/timematcher;DB_CLOSE_DELAY=-1"
    user: "$DB_USER:sa"
    password: "$DB_PASSWORD:"

calendar:
    provider: "$CALENDAR_PROVIDER:inmemory"

google:
    clientId: "$GOOGLE_CLIENT_ID:"
    clientSecret: "$GOOGLE_CLIENT_SECRET:"

publicBaseUrl: "$PUBLIC_BASE_URL:http://localhost:8080"

telegram:
    botToken: "$TELEGRAM_BOT_TOKEN:"
    hostUserId: "$TELEGRAM_HOST_USER_ID:0"
    webhookSecret: "$TELEGRAM_WEBHOOK_SECRET:"
```

## 8. Connect via the bot

Once the server is running with the Telegram and Google env vars set:

1. Open Telegram and DM your bot `/connect`.
2. The bot replies with an inline button — tap **🔗 Connect Google Calendar**.
3. Your browser opens Google's consent page. Approve access.
4. After approval the browser redirects back to the server (`/oauth/google/callback`), which stores the refresh token in the database. The bot sends a confirmation message.
5. To list, set a booking target (★), or remove connected calendars, send `/calendars` to the bot.
