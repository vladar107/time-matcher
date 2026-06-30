# Task 2 Report: GoogleCalendarApi + provider/writer adapters

## What was built

Three new files implementing the Google Calendar HTTP layer and port adapters:

1. **`GoogleCalendarApi.kt`** — Thin Ktor-client wrapper with:
   - `freeBusy(calendarId, window)`: POSTs to `googleapis.com/calendar/v3/freeBusy`, maps response slots to `List<TimeInterval>`.
   - `insertEvent(calendarId, event)`: POSTs to `googleapis.com/calendar/v3/calendars/{id}/events?sendUpdates=all` with optional attendee.
   - Private DTOs annotated `@Serializable` (file-private).
   - `call()` helper: wraps network exceptions as `CalendarException`, throws on non-2xx status.

2. **`GoogleCalendarProvider.kt`** — Implements `CalendarProvider`: delegates to `api.freeBusy()`, wraps results as `BusyInterval(interval, calendarId)`.

3. **`GoogleCalendarWriter.kt`** — Implements `CalendarWriter`: delegates to `api.insertEvent()`, using the injected `calendarId` (ignores the per-call parameter per spec).

## Ktor Client API Adjustments

- **`TextContent` cast** (`req.body as io.ktor.http.content.TextContent`): Worked as-is in Ktor 3.5.1 — no adaptation needed. The brief's fallback (`toByteArray().decodeToString()`) was not required.
- All other Ktor 3.5.1 APIs (`post {}`, `header()`, `contentType()`, `setBody()`, `response.body<T>()`, `response.status.isSuccess()`) matched exactly.
- **`runBlocking<Unit>` fix**: JUnit4 requires test methods to return void. `nonSuccessThrowsCalendarException` used `runBlocking { assertFailsWith<...> { ... } }` which returns the exception (non-void). Fixed by using `runBlocking<Unit> { ... }` — consistent with `GoogleTokenSourceTest`.

## TDD Evidence

### RED — test written, implementation absent

Command:
```
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  ./gradlew -p time-matcher test --tests "io.vladar107.data.google.GoogleCalendarApiTest"
```

Output:
```
> Task :compileTestKotlin FAILED
e: ...GoogleCalendarApiTest.kt:35:19 Unresolved reference 'GoogleCalendarApi'.
e: ...GoogleCalendarApiTest.kt:43:19 Unresolved reference 'GoogleCalendarApi'.
e: ...GoogleCalendarApiTest.kt:51:19 Unresolved reference 'GoogleCalendarApi'.
BUILD FAILED in 1s
```

### GREEN — focused tests pass after implementation

Command (same):

Output:
```
> Task :test
BUILD SUCCESSFUL in 1s
3 tests: freeBusyMapsBusyIntervals, insertEventSendsAttendeeAndSucceeds, nonSuccessThrowsCalendarException — all PASSED
```

### Full build green

```
./gradlew build
> Task :build
BUILD SUCCESSFUL in 11s
12 actionable tasks: 8 executed, 4 up-to-date
```

## Files Changed

All new (4 files):
- `time-matcher/src/main/kotlin/io/vladar107/data/google/GoogleCalendarApi.kt`
- `time-matcher/src/main/kotlin/io/vladar107/data/google/GoogleCalendarProvider.kt`
- `time-matcher/src/main/kotlin/io/vladar107/data/google/GoogleCalendarWriter.kt`
- `time-matcher/src/test/kotlin/io/vladar107/data/google/GoogleCalendarApiTest.kt`

No existing files were modified.

## Commit

SHA: `fd00bbe`
Message: `feat: add Google Calendar API client and provider/writer adapters`
Author: `Vladislav Ramazaev <vladar107@gmail.com>`
No co-author trailer.

## Self-Review

- **Correctness**: All three tests cover the required behaviors (freeBusy mapping, insertEvent payload with attendee+summary, non-2xx → CalendarException).
- **Error handling**: `call()` wraps all non-`CalendarException` exceptions; re-throws existing `CalendarException` unmodified; checks `isSuccess()` on the response.
- **Spec compliance**: Writer correctly uses `this.calendarId` (ignores per-call param). `insertEvent` adds `?sendUpdates=all`. Attendee only added when `attendeeEmail != null`.
- **No DI wiring** done (Task 3 responsibility). No status pages modified. No unrelated files touched.

## Concerns

Minor (non-blocking):
- Google freeBusy API can return an `errors` field per calendar entry (when the calendar couldn't be queried). The current implementation silently returns an empty list in that case. Acceptable for this slice — can be hardened later.
- `TextContent` cast in test is internal Ktor API; fragile across major Ktor versions.

---

## Code-Review Fix (applied on top of initial implementation)

### Fix 1 — freeBusy must not treat an errored calendar as "free"

Added `FbError` DTO and `errors` field to `FbCalendar`. In `freeBusy`, after parsing: if the calendar entry is missing OR `errors` is non-empty, a `CalendarException` is thrown with the calendar ID and reasons. Previously, an unreadable calendar was silently treated as fully free.

### Fix 2 — harden insertEvent test

Added `assertTrue(req.url.toString().contains("sendUpdates=all"))` assertion inside the MockEngine lambda. Body capture updated from `(req.body as TextContent).text` (fragile due to review comment) to the same cast via `io.ktor.http.content.TextContent` — verified at runtime: Ktor 3.5.1 serializes the body as `TextContent`, so this cast is correct. The `toByteArray().decodeToString()` approach in the review instructions does not compile against `OutgoingContent` in Ktor 3.5.1.

### Regression test added

`freeBusyPerCalendarErrorThrowsCalendarException`: MockEngine returns HTTP 200 with a per-calendar `errors` entry (no `busy`) → asserts `freeBusy("primary", window)` throws `CalendarException`.

### Covering-test command and output

```
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  /Users/vramazaev/src/time-matcher/time-matcher/gradlew \
  -p /Users/vramazaev/src/time-matcher/time-matcher test --tests "io.vladar107.data.google.GoogleCalendarApiTest"
```

Output:
```
> Task :test
BUILD SUCCESSFUL in 1s
5 actionable tasks: 2 executed, 3 up-to-date
```

4 tests passed:
- `freeBusyMapsBusyIntervals` — PASSED
- `insertEventSendsAttendeeAndSucceeds` — PASSED (now also asserts sendUpdates=all)
- `nonSuccessThrowsCalendarException` — PASSED
- `freeBusyPerCalendarErrorThrowsCalendarException` — PASSED (new regression test)

Full build also green: `BUILD SUCCESSFUL in 11s`

### Files changed

- `time-matcher/src/main/kotlin/io/vladar107/data/google/GoogleCalendarApi.kt` — added `FbError`, `errors` field on `FbCalendar`, error-guard logic in `freeBusy`
- `time-matcher/src/test/kotlin/io/vladar107/data/google/GoogleCalendarApiTest.kt` — hardened `insertEventSendsAttendeeAndSucceeds`, added `freeBusyPerCalendarErrorThrowsCalendarException`
