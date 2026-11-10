package com.harishdarko.caselens.triage;

/** A small non-EV pack, built in the test, to exercise the pack path without shipping a second pack yet. */
final class TestPacks {
    static final String SAAS_JSON = """
            {
              "id": "test-saas",
              "name": "SaaS support (test)",
              "categories": ["LOGIN", "INVOICE", "GENERAL"],
              "generalCategory": "GENERAL",
              "categoryDescriptions": {
                "LOGIN": "Sign-in and password problems.",
                "INVOICE": "Questions about an invoice.",
                "GENERAL": "Anything else."
              },
              "policyVersion": "t1",
              "policies": [
                { "id": "ACCOUNT_LOCKOUT", "guidance": "Verify the user's identity before unlocking the account." },
                { "id": "INVOICE_DISPUTE", "guidance": "Collect the invoice number and escalate to billing." }
              ],
              "rules": {
                "category": [
                  { "value": "LOGIN", "terms": ["password", "locked out", "login"] },
                  { "value": "INVOICE", "terms": ["invoice"] }
                ],
                "urgency": [
                  { "value": "CRITICAL", "terms": ["all users"] },
                  { "value": "HIGH", "terms": ["locked out"] }
                ],
                "slaRisk": [ { "value": "HIGH", "terms": ["all users"] } ],
                "sentiment": [ { "value": "ANGRY", "terms": ["furious"] } ],
                "policies": [
                  { "id": "ACCOUNT_LOCKOUT", "category": "LOGIN", "allOf": [["locked out"]] },
                  { "id": "INVOICE_DISPUTE", "allOf": [["invoice"], ["wrong", "dispute"]] }
                ],
                "actions": {
                  "LOGIN": ["Verify the user's identity", "Clear the account lockout"],
                  "INVOICE": ["Check the invoice lines"],
                  "GENERAL": ["Clarify the requested outcome"]
                }
              },
              "evidenceSupport": {
                "category": { "LOGIN": ["password", "login", "locked"], "INVOICE": ["invoice"], "GENERAL": ["question"] },
                "urgency": { "LOW": ["question"], "MEDIUM": ["issue"], "HIGH": ["locked"], "CRITICAL": ["all users"] }
              },
              "priority": {
                "PAYMENT_IMPACT": { "explanation": "Invoice impact detected", "terms": ["invoice"] },
                "SERVICE_BLOCKED": { "explanation": "Sign-in is blocked", "terms": ["locked out"] },
                "BROAD_IMPACT": { "explanation": "Many users are affected", "terms": ["all users"] },
                "POSITIVE_GENERAL_REQUEST": { "explanation": "Positive general request", "terms": ["thanks"] }
              }
            }
            """;

    static PackRules saas() {
        return PackRules.parse(SAAS_JSON, "test-saas");
    }

    private TestPacks() {}
}
