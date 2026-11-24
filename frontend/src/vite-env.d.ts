/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** PostHog project key (public). Empty or unset: analytics stay off. */
  readonly VITE_POSTHOG_KEY?: string
  /** PostHog ingestion host: https://us.i.posthog.com (default) or https://eu.i.posthog.com. */
  readonly VITE_POSTHOG_HOST?: string
}
