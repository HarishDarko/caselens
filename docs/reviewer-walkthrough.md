# Three-minute reviewer walkthrough

1. Open [https://caselens.harishdarko.com](https://caselens.harishdarko.com).
   The page is labelled as a demo and contains synthetic records only.
   The CloudFront URL remains a fallback if the custom domain is unavailable:
   [https://d27d60ya5pvyzq.cloudfront.net](https://d27d60ya5pvyzq.cloudfront.net).
2. Pick a sample queue and select **Open the ... queue**. CaseLens creates an
   isolated, 24-hour workspace containing synthetic records only. No account
   is required.
3. The **Inbox** opens on the most urgent recorded ticket. Its evidence quotes
   are highlighted in the customer's own words, numbered to the reasons.
4. Open a ticket under **Not triaged yet** to run it live. Review the category,
   urgency, deterministic priority, evidence, policy, next steps, and
   suggested reply.
5. Expand the trace line. Point out the outbox event, queue job,
   worker attempt, Groq model, decision source, and latency recorded by the
   backend. `AI_VALIDATED` means the provider output passed
   evidence and policy validation; `RULES_FALLBACK` means the deterministic
   safe path handled a provider or validation failure.
6. **Accept** the call or **Override urgency**. The original result stays
   immutable while each review is appended.
7. Open **Quality and operations** to see the live queue, reviewer acceptance,
   override rate, benchmark match, live latency and provider usage.
8. Select **Simulate provider timeout**. The backend records one synthetic
   provider timeout and the banner turns to **Recovery required**. Use
   **Retry case**. The replay guard prevents a failure loop.

The reviewer can inspect why the result was produced, trace its durable queue
states, correct it, and recover a failed job. The model supplies a bounded
recommendation while the application owns priority, evidence validation,
persistence, and recovery.
