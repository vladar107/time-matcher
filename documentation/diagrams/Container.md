C4 Container Diagram

> Phase 1 (implemented): in-app availability finder — `GET /availability/slots` over in-memory calendars. External calendar sync, persistence, booking, and bots are later phases.

> Phase 2a (implemented): EventTypes + booking. Config (settings, event types, connected calendars) in H2; bookings written to the calendar via the CalendarWriter port. Real Google calendar + host auth are later slices.

> Phase 2c (implemented): public booking page at `GET /book/{slug}` — a self-contained HTML/JS page (attendee timezone; 1-day mobile / 7-day desktop) driving the booking JSON API. (Host admin Telegram bot and deployment are separate, later.)

```mermaid
C4Container
title C4 Container Diagram

Person(host, "User", "Manage available time slots")
Person(attendee, "Attendee", "Meeting creator")

Container_Ext(extCalendar, "External Calendar")

Container_Boundary(container, "Time Matcher") {
    Container(adminBot, "Admin Bot", kotlin, "Admin Telegram Bot", robot,)
    Container(meetingBot, "Meeting Bot", kotlin, "Meeting Telegram Bot", robot,)
    Container(server, "Time Matcher App", kotlin, "Web Server")
    ContainerDb(mainDb, "Main Storage", "????")
}

BiRel(host, adminBot, "telegram")
Rel(adminBot, server, "REST")

BiRel(attendee, meetingBot, "telegram")
Rel(meetingBot, server, "REST")

Rel(server, extCalendar, "REST")
Rel(server, mainDb, "??")

```
