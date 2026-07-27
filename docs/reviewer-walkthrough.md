# Three-minute reviewer walkthrough

1. Open [https://d27d60ya5pvyzq.cloudfront.net](https://d27d60ya5pvyzq.cloudfront.net).
   The page is labelled as a demo and contains synthetic records only.
2. Select **Launch live demo**. CaseLens creates an isolated, 24-hour
   workspace containing synthetic records only. No account is required.
3. In **Ticket inbox**, scan the seeded queue. Use **Load
   charger-offline-site-wide** to create a fresh synthetic case.
4. Open the new case. Review the category, urgency, SLA risk, deterministic
   priority, exact evidence, policy, next actions, and suggested reply.
5. Check **Durable processing trace**. Point out the outbox event, queue job,
   worker attempt, Groq model, decision source, and latency recorded by the
   backend. `AI_VALIDATED` means the provider output passed
   evidence and policy validation; `RULES_FALLBACK` means the deterministic
   safe path handled a provider or validation failure.
6. Select a different urgency and save a correction. The original result stays
   immutable while feedback is appended.
7. Open **Evaluation** to see agreement, correction rate, latency, failures,
   and provider usage calculated from persisted records.
8. Return to Inbox and select **Run controlled retry demo**. The backend records
   one synthetic provider timeout. Open **Operations**, inspect the retryable
   job, and use **Retry failed case**. The replay guard prevents a failure loop.

The reviewer can inspect why the result was produced, trace its durable queue
states, correct it, and recover a failed job. The model supplies a bounded
recommendation while the application owns priority, evidence validation,
persistence, and recovery.
