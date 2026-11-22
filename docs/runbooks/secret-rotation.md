# Runtime secret rotation runbook

The api, worker and relay Lambda functions read the Secrets Manager secret
`caselens-demo/application` once, during SnapStart init. The values are then
part of the snapshot of each published version. Changing the secret does not
change a running version, and it does not change the versions behind the
`live` aliases.

So rotating a secret (the Neon password, the session secret or a provider key)
means publishing new versions and moving the aliases.

## Steps

1. Write the new value into the secret. Never copy it into Git, a ticket, a
   task report or chat.
2. For a value the old one must keep working beside (the Neon password), keep
   the old value valid until step 5 is done. The live versions still use it.
3. Publish new versions of all three functions, for example by running the CI
   deploy (`workflow_dispatch` with `CASELENS_LAMBDA_DEPLOY_ENABLED=true`). The
   deploy waits until each published version is `Active` and stops on
   `Failed`.
4. Move the three `live` aliases to the new versions with one reviewed
   Terraform plan that changes only `api_live_version`, `worker_live_version`
   and `relay_live_version` (see `docs/deployment.md`, step 9).
5. Check the site, then revoke the old value.

Rotating `CASELENS_SESSION_SECRET` ends every open demo session: their tokens
were signed with the old secret. The page then says the session has ended and
offers a new demo.

## What is not in the secret

`CASELENS_WEB_ORIGINS` (the browser origins allowed to call the API) is set
by Terraform from `web_origins` on all three functions. The runtime ignores it
in the secret, so change it with a Terraform plan. Like a secret, the new value
reaches visitors only once versions published after the change are behind the
`live` aliases.
