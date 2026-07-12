# Three-minute reviewer walkthrough

1. Open the local or deployed reviewer URL. The page is labelled as a demo and
   contains synthetic records only.
2. Enter the shared demo passcode and open the isolated workspace.
3. In **Ticket inbox**, scan the seeded queue. Use **Load
   charger-offline-site-wide** to create a fresh synthetic case.
4. Open the new case. Review the category, urgency, SLA risk, deterministic
   priority, exact evidence, policy, next actions, and suggested reply.
5. Check the decision trace. `AI_VALIDATED` means the provider output passed
   evidence and policy validation; `RULES_FALLBACK` means the deterministic
   safe path handled a provider or validation failure.
6. Select a different urgency and save a correction. The original result stays
   immutable while feedback is appended.
7. Open **Evaluation** to see agreement, correction rate, latency, failures,
   and provider usage calculated from persisted records.
8. Open **Operations** to see unresolved queue failures and the recovery action.

The strongest product discussion is not “the model classified a ticket.” It is
that a reviewer can inspect why the result was produced, correct it, and see
the quality signal change without surrendering control to the model.
