# WaterWatch verification plan

This is a manual integration checklist. It must be run against a configured Supabase project and at least two Android devices; no real backend/device test has been executed by the coding agent.

## Setup gate
- [ ] Apply the database migration to a fresh Supabase project.
- [ ] Configure email/password authentication.
- [ ] Register Android package `com.waterwatch` in Firebase and add the private `google-services.json` locally.
- [ ] Deploy `push-alert` and configure Firebase service-account secrets.
- [ ] Build/install the debug APK on two or more Android phones.

## Account and household
- [ ] User A signs up and signs in.
- [ ] User A creates a household and receives a one-time invitation code.
- [ ] User B signs up and joins with the code.
- [ ] Confirm both users appear in `household_members` with their correct floor.
- [ ] Confirm an invalid, expired or revoked code is rejected.

## Alert workflow
- [ ] User A triggers an alert.
- [ ] Confirm the alert is created once with reporter, floor and timestamp.
- [ ] Confirm User B's registered device receives the FCM notification while app is foregrounded.
- [ ] Repeat with app backgrounded and terminated.
- [ ] User B submits `not_my_floor`; verify a response row is stored.
- [ ] User B submits `switching_off`; verify alert moves to `in_progress`.
- [ ] A member submits `motor_off`; verify the response says motor off but does not falsely close the alert.
- [ ] Admin resolves; verify resolver and timestamp are recorded.
- [ ] Confirm a non-admin cannot resolve.
- [ ] Confirm a duplicate alert inside the 90-second cooldown is rejected.
- [ ] Confirm an unrelated user cannot read the household's alerts or responses.

## Known unverified areas
- Android foreground/background/terminated notification delivery.
- FCM token refresh and stale-token cleanup against real Firebase project.
- Concurrent alert creation race behavior (the current cooldown check is not protected by a household-level advisory lock; test and harden before production).
- Offline/reconnect behavior and retry semantics.
- Realtime screen updates (current UI refreshes manually).
- Admin member/invite management UI (server RPCs exist; screens are pending).
