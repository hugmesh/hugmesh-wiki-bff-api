# Runbook — the self-hosted `hugmesh-x86` GitHub Actions runner

**The full runbook lives in one place, so the three copies cannot drift:**
<https://github.com/hugmesh/hugmesh-forum-bff-api/blob/main/.github/SELF_HOSTED_RUNNER.md>

It covers the host requirements, the tailnet + public network reach, repo-level vs
org-level registration, the secrets to add, the security warning about self-hosted
runners on public repositories, and why all three registry inputs are `secrets.`
and never `vars.`.

Decision record: <https://github.com/hugmesh/hugmesh-generator/issues/347#issuecomment-5722430270>
(decision **48 = B**, self-hosted `hugmesh-x86` runner; **43a = A**, all-secrets spelling).

---

## Status in THIS repo

**No runner is registered.** Measured 2026-09-17:
`GET /repos/hugmesh/hugmesh-wiki-bff-api/actions/runners` -> `total_count` **0**.
The org level returns **403** to the available token, so whether an org-level
runner exists is **unknown**, not zero.

`release.yml` here now asks for `runs-on: [self-hosted, hugmesh-x86]`. Until that
runner exists the job queues and is cancelled at GitHub's 24h limit. **This repo's
workflow has never executed, and the change that added this file does not make it
green** — it makes it capable of running once the runner is registered, and makes a
missing secret fail by name rather than ten minutes later inside Maven.

## Secrets this repo needs

Add under `Settings -> Secrets and variables -> Actions` on the **Secrets** tab
(not **Variables**):

| Secret | Meaning |
|---|---|
| `GITLAB_MAVEN_TOKEN` | Read-scoped token for the GitLab group Maven registry. Reaches the image build as the BuildKit secret `ci_job_token`. |
| `GITLAB_API_V4_URL` | The in-cluster GitLab API v4 base, e.g. `https://gitlab.<tailnet>/api/v4` |
| `GITLAB_GROUP_ID` | The HugMesh GitLab group id |

**Renamed by decision 43a.** This repo's `release.yml` previously read
`secrets.GITLAB_CI_JOB_TOKEN` + `vars.CI_API_V4_URL` + `vars.GITLAB_GROUP_ID`.
`vars.` and `secrets.` are different namespaces, so a secret never satisfied those
`vars.` expressions. If Actions **Variables** named `CI_API_V4_URL` or
`GITLAB_GROUP_ID`, or a secret named `GITLAB_CI_JOB_TOKEN`, still exist here, they
are no longer read by anything — re-create them under the names in the table and
**delete the stale entries** so they cannot mislead a reader.

## Verify

```
# expect total_count >= 1, status "online", hugmesh-x86 among the labels
env -u GH_TOKEN -u GITHUB_TOKEN gh api repos/hugmesh/hugmesh-wiki-bff-api/actions/runners \
  --jq '.total_count, (.runners[] | {name, status, busy, labels: [.labels[].name]})'
```

Registration alone is not proof a job reaches it. After dispatching `Release`,
the field that settles it is `runner_name` — empty means no runner ever picked
the job up:

```
env -u GH_TOKEN -u GITHUB_TOKEN gh api repos/hugmesh/hugmesh-wiki-bff-api/actions/runs/<RUN_ID>/jobs \
  --jq '.jobs[] | {name, status, conclusion, runner_name}'
```

When registering, name this repo's runner `hugmesh-x86-wiki` in its own
directory on the host, so one box can serve all three repos concurrently.
