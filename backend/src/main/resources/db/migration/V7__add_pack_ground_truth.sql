-- Expected labels for the SaaS support and e-commerce demo tickets, so the Evaluation view can show agreement for
-- every industry. Expand only: rows are added, nothing is changed or removed, and a row that already exists is kept.
-- The values must match packs/<id>/scenarios.json; DemoPackScenariosTest checks that.
SET LOCAL lock_timeout = '5s';

INSERT INTO evaluation_ground_truth (scenario_key, expected_category, expected_urgency) VALUES
    ('saas-sso-lockout', 'LOGIN_ACCESS', 'CRITICAL'),
    ('saas-mfa-new-phone', 'LOGIN_ACCESS', 'HIGH'),
    ('saas-duplicate-invoice', 'BILLING', 'HIGH'),
    ('saas-dashboard-slow', 'PERFORMANCE', 'MEDIUM'),
    ('saas-api-outage', 'PERFORMANCE', 'CRITICAL'),
    ('saas-webhook-401', 'INTEGRATION', 'HIGH'),
    ('saas-export-and-delete', 'DATA_REQUEST', 'LOW'),
    ('saas-custom-field-question', 'GENERAL', 'LOW'),
    ('shop-parcel-never-arrived', 'DELIVERY', 'MEDIUM'),
    ('shop-mug-broken', 'PRODUCT_ISSUE', 'MEDIUM'),
    ('shop-refund-missing', 'RETURN_REFUND', 'HIGH'),
    ('shop-two-payments', 'PAYMENT', 'HIGH'),
    ('shop-change-address', 'ORDER_STATUS', 'HIGH'),
    ('shop-checkout-down', 'PAYMENT', 'CRITICAL'),
    ('shop-size-question', 'GENERAL', 'LOW'),
    ('shop-cannot-sign-in', 'ACCOUNT_ACCESS', 'MEDIUM')
ON CONFLICT (scenario_key) DO NOTHING;
