# 0014. The API accepts Google ID tokens directly

- Status: accepted
- Date: 2026-11-22

## Context

Users sign in with Google on Android. The API must know who is calling on every request, must not
store passwords, and has one instance on a $5 plan. The design note first proposed exchanging the
Google token for TrackHub's own short-lived JWT.

## Decision

- The Android app sends the Google ID token as `Authorization: Bearer ...` on every call. The API is
  an OAuth2 resource server (Spring Security) and issues no tokens of its own.
- Verification: signature against Google's published keys (cached), expiry, issuer
  `accounts.google.com` in either documented form, audience equal to our OAuth client id
  (`GOOGLE_CLIENT_ID`), and `email_verified`. The audience check is the important one: without it,
  any app's Google token would sign its user in here.
- Users are keyed on Google's `sub`, which never changes, not email. The first request creates the
  user (`INSERT ... ON CONFLICT DO NOTHING`, so two first requests can't create two users).
- Stateless: no sessions or cookies, so CSRF protection is off; there is nothing for a forged
  cross-site request to ride on.
- Another user's parcel is a 404, not a 403, so ids can't be probed for existence.
- Errors are RFC 9457 problem documents with a stable `code` the app switches on.

## Alternatives rejected

- **Our own JWT after a Google sign-in.** Lets us choose lifetimes and add claims, but means
  signing keys to manage and rotate, a refresh flow, and revocation, for no gain at this size.
  Google ID tokens last an hour and the Android SDK refreshes them silently.
- **Server sessions.** Simple on the web, awkward on mobile, and needs shared session storage the
  moment there are two instances.
- **Spring's `issuer-uri` auto-configuration.** Checks a single issuer string; Google uses two.

## Consequences

- Only Google sign-in. Adding email/password later would bring back token issuing.
- Each request costs one indexed `SELECT` on `users`; PR 7 can cache `sub` to id if it shows up.
- Signing out on the phone doesn't revoke a token already issued; it expires within an hour.
- Tests use `spring-security-test`'s `jwt()` for the API and test the Google checks directly, so CI
  never calls Google.

## Interview answer

_To be written by Joshua._

## Measured result

_After Phase 1: p95 latency of an authenticated `GET /api/shipments`._
