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

## 4. Create an OAuth client (Desktop type)

1. Go to **APIs & Services** → **Credentials**.
2. Click **Create credentials** → **OAuth client ID**.
3. Choose **Desktop app** as the application type. Give it a name and click **Create**.
4. Copy the **Client ID** and **Client Secret** — you will need them in step 6.

## 5. Get a refresh token via the OAuth Playground

1. Open [OAuth 2.0 Playground](https://developers.google.com/oauthplayground/).
2. Click the gear icon (⚙) in the top-right corner and check **Use your own OAuth credentials**.
3. Enter your **Client ID** and **Client Secret** from step 4, then close the panel.
4. In the **Step 1** box, find or type the scope:
   ```
   https://www.googleapis.com/auth/calendar
   ```
   Click **Authorize APIs** and sign in with the Google account you added as a test user.
5. In **Step 2**, click **Exchange authorization code for tokens**.
6. Copy the **Refresh token** from the response — this is a long-lived credential.

> **Keep the refresh token secret.** Anyone with the client secret + refresh token can access your calendar.

## 6. Run Time Matcher in Google Calendar mode

Set the following environment variables before starting the server:

```
CALENDAR_PROVIDER=google
GOOGLE_CLIENT_ID=<your OAuth client ID>
GOOGLE_CLIENT_SECRET=<your OAuth client secret>
GOOGLE_REFRESH_TOKEN=<your refresh token>
GOOGLE_CALENDAR_ID=primary
```

`GOOGLE_CALENDAR_ID` defaults to `primary` if omitted. To use a different calendar, set it to the calendar's ID (visible in Google Calendar settings under "Integrate calendar").

### How env vars map to `application.yaml`

The committed `application.yaml` uses Ktor's YAML environment-variable substitution syntax (`"$VAR:default"`):

```yaml
calendar:
    provider: "$CALENDAR_PROVIDER:inmemory"

google:
    clientId: "$GOOGLE_CLIENT_ID:"
    clientSecret: "$GOOGLE_CLIENT_SECRET:"
    refreshToken: "$GOOGLE_REFRESH_TOKEN:"
    calendarId: "$GOOGLE_CALENDAR_ID:primary"
```

If none of the variables are set, the service starts in `inmemory` mode (the default) — no Google credentials required.

### Example: start with env vars

```bash
CALENDAR_PROVIDER=google \
GOOGLE_CLIENT_ID=... \
GOOGLE_CLIENT_SECRET=... \
GOOGLE_REFRESH_TOKEN=... \
GOOGLE_CALENDAR_ID=primary \
  ./gradlew run
```
