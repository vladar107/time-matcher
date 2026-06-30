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
   http://localhost:8080/oauth/google/callback
   ```
   (Adjust the base URL if deploying to a remote host.)
5. Click **Create**. Copy the **Client ID** and **Client Secret** — you will need them below.

## 5. Create a Telegram bot with BotFather

1. Open Telegram and start a chat with [@BotFather](https://t.me/BotFather).
2. Send `/newbot`, follow the prompts, and copy the **bot token** it gives you.
3. Find your own Telegram user ID (e.g. via [@userinfobot](https://t.me/userinfobot)).

## 6. Run Time Matcher in Google Calendar mode

Set the following environment variables before starting the server:

```
CALENDAR_PROVIDER=google
GOOGLE_CLIENT_ID=<your OAuth client ID>
GOOGLE_CLIENT_SECRET=<your OAuth client secret>
OAUTH_REDIRECT_BASE_URL=http://localhost:8080
TELEGRAM_BOT_TOKEN=<your bot token>
TELEGRAM_HOST_USER_ID=<your Telegram user ID>
```

If none of the `GOOGLE_*` / `TELEGRAM_*` variables are set, the service starts in `inmemory` mode with no bot — no credentials required.

### How env vars map to `application.yaml`

The committed `application.yaml` uses Ktor's YAML environment-variable substitution syntax (`"$VAR:default"`):

```yaml
calendar:
    provider: "$CALENDAR_PROVIDER:inmemory"

google:
    clientId: "$GOOGLE_CLIENT_ID:"
    clientSecret: "$GOOGLE_CLIENT_SECRET:"

oauth:
    redirectBaseUrl: "$OAUTH_REDIRECT_BASE_URL:http://localhost:8080"

telegram:
    botToken: "$TELEGRAM_BOT_TOKEN:"
    hostUserId: "$TELEGRAM_HOST_USER_ID:0"
```

### Example: start with env vars

```bash
CALENDAR_PROVIDER=google \
GOOGLE_CLIENT_ID=... \
GOOGLE_CLIENT_SECRET=... \
OAUTH_REDIRECT_BASE_URL=http://localhost:8080 \
TELEGRAM_BOT_TOKEN=... \
TELEGRAM_HOST_USER_ID=... \
  ./gradlew run
```

## 7. Connect via the bot

Once the server is running with the Telegram env vars set:

1. Open Telegram and DM your bot `/connect`.
2. The bot replies with a link — tap it to open your browser.
3. You will be redirected to Google's consent page. Approve access.
4. After approval the browser redirects back to the server and the bot confirms the connection.
5. To list or remove connected calendars, send `/calendars` to the bot.
