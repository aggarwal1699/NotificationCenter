# WaterWatch setup

## Architecture
- Android: Kotlin + Jetpack Compose.
- Backend: Supabase Auth + Postgres + Row Level Security + SQL RPCs.
- Push: Firebase Cloud Messaging (FCM), sent by a Supabase Edge Function.
- CI: GitHub Actions debug APK artifact.

## 1. Create Supabase project
1. Create a Free project at https://supabase.com/dashboard.
2. Open SQL Editor and run `supabase/migrations/202611100001_waterwatch.sql`.
3. Copy the Project URL and anon/public key. Never put the service-role key in the Android app.
4. In Authentication > Providers > Email, choose whether email confirmation is required. If enabled, each user must verify before sign-in.

Free-plan note (checked 10 Nov 2026): Supabase lists two free projects, 500 MB database per project, 50,000 monthly active users, 500,000 Edge Function invocations, 2 million Realtime messages and 5 GB egress. Free projects can pause after inactivity. Limits can change; verify https://supabase.com/docs/guides/platform/billing-on-supabase before deployment.

## 2. Configure Android
Add these to `~/.gradle/gradle.properties`:
```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR_SUPABASE_ANON_KEY
```
Register Android package `com.waterwatch` in Firebase Console, enable Cloud Messaging, and download `google-services.json` to `app/google-services.json`. This file is git-ignored. FCM won't initialize until this file is supplied.

## 3. Deploy push function
Install the Supabase CLI, then:
```bash
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase functions deploy push-alert
supabase secrets set FIREBASE_PROJECT_ID=YOUR_FIREBASE_PROJECT_ID
supabase secrets set FIREBASE_SERVICE_ACCOUNT_JSON='{"type":"service_account",...}'
``
Ensure the function has `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` secrets. Create a Firebase service account permitted to send FCM messages. Store its JSON only as a Supabase secret, never in Git or the APK.

## 4. Build APK
Open in Android Studio and run `app > Tasks > build > assembleDebug`, or run `gradle :app:assembleDebug`.
For GitHub Actions, add repository secrets:
- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `GOOGLE_SERVICES_JSON` (optional; full contents of google-services.json)

The workflow builds a debug APK and stores it as a 14-day Actions artifact.

## Current implementation status
The initial development scaffold includes the Compose sign-up/sign-in UI, household create/join, alert creation, response actions, admin-only resolve RPC, Postgres schema/RLS, FCM push function and APK workflow. It still requires a configured Supabase project, Firebase project, FCM service account and GitHub Actions secrets. No backend has been deployed and no APK has yet been built or device-tested.

The first version does not yet include realtime screen subscriptions, admin member/invitation management UI, automatic retry queues for offline devices, a complete response timeline UI or automated end-to-end tests. Alert history currently refreshes manually. Treat this as a development baseline, not a production release.
