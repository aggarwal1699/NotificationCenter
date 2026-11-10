# WaterWatch

A lightweight Android app for household water-overflow alerts. Family members manually report overflow, receive notifications, respond and resolve the alert. The app does **not** detect water or control the pump.

## Stack
- Kotlin + Jetpack Compose
- Supabase Auth, Postgres, Row Level Security and SQL RPCs
- Firebase Cloud Messaging via a Supabase Edge Function
- GitHub Actions debug APK builds

## Quick start
See [docs/SETUP.md](docs/SETUP.md) for Supabase, Firebase, secrets and build instructions.

## Status
Initial development scaffold committed. It is not yet deployed, fully configured, or tested on physical Android devices. FCM requires Firebase configuration and server-side service-account secrets.

## Security
Never put the Supabase service-role key or Firebase service-account JSON in the APK or Git. Only the Supabase anon key belongs in the Android client, protected by database RLS.
