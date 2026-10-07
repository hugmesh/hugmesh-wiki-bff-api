# The `hugmesh-x86` runner label is retired

`release.yml` used to ask for `runs-on: [self-hosted, hugmesh-x86]`. That label
was copied from the GitLab runner tags in `.gitlab-ci.yml`; GitHub never had a
runner carrying it. The repository has no repo-level runner
(`GET /repos/hugmesh/hugmesh-wiki-bff-api/actions/runners` returns `total_count` 0), and
the org's own self-hosted runner carries only `self-hosted, Linux, X64`. A dispatch
therefore sat in `queued` until GitHub's 24h limit cancelled it.

`release.yml` now runs on github-hosted `ubuntu-latest`, which executes again.
It is deliberately the plain label and not the `RUNS_ON` repository variable:
`RUNS_ON` selects the operator's workstation runner, and a release image build
should not be routed there by a variable set for CI.

## What is still open

The image build needs the private `hugmesh-parent` / `hugmesh-commons` artifacts.
`release.yml` still reads them from the in-cluster GitLab group Maven registry
through the `GITLAB_MAVEN_TOKEN`, `GITLAB_API_V4_URL` and `GITLAB_GROUP_ID`
secrets, and that registry is tailnet-only: a github-hosted runner cannot reach it.
Until the chain is resolved from GitHub Packages instead, the preflight step fails
by name on the missing secrets. That is a separate change from the runner label.

The long runbook for registering a self-hosted `hugmesh-x86` runner that used to be
in this file (and in the forum-bff copy) described a runner that cannot be
registered with that label from the current setup; it is superseded by this note.
