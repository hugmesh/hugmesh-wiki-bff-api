#!/usr/bin/env python3
"""Shape checks for .github/workflows/release.yml. Pure text, no network.

Run: python3 scripts/test-release-workflow.py   (needs PyYAML; fails, never skips, without it)

What it pins, and why:
  * No pull_request / pull_request_target trigger. This workflow holds packages:write and may
    run on the self-hosted runner; a PR must never reach it.
  * Triggers are exactly: push to main, push of v* tags, workflow_dispatch.
  * Every job uses the one runs-on line that picks vars.RUNS_ON_PUBLISH only for a non-PR event
    on main or a v* / studio-v* tag, and ubuntu-latest otherwise.
  * Image tags come only from the version guard (scripts/release-version.sh); no literal
    :latest anywhere in the workflow.
  * The existing gates stay: the cross-repo package preflight, the BuildKit
    packages_read_token secret, and the refuse-to-overwrite check on release runs.
  * Only the github-release job gets contents: write, and only on a release run.
"""
import pathlib
import sys

try:
    import yaml
except ImportError:  # fail closed: a missing parser must not look like a pass
    print("FAIL PyYAML is not installed; cannot check release.yml", file=sys.stderr)
    sys.exit(1)

ROOT = pathlib.Path(__file__).resolve().parent.parent
WF_PATH = ROOT / ".github" / "workflows" / "release.yml"
RUNS_ON = (
    "${{ fromJSON(github.event_name != 'pull_request' && github.event_name != 'pull_request_target'"
    " && (github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v')"
    " || startsWith(github.ref, 'refs/tags/studio-v')) && vars.RUNS_ON_PUBLISH || '\"ubuntu-latest\"') }}"
)

failures = 0


def check(desc, ok):
    global failures
    if ok:
        print(f"ok   {desc}")
    else:
        print(f"FAIL {desc}", file=sys.stderr)
        failures += 1


text = WF_PATH.read_text(encoding="utf-8")
wf = yaml.safe_load(text)
# YAML 1.1 reads the bare key `on` as boolean True.
on = wf.get("on", wf.get(True))
jobs = wf.get("jobs", {})

check("triggers are exactly push, workflow_dispatch",
      isinstance(on, dict) and set(on) == {"push", "workflow_dispatch"})
check("no pull_request or pull_request_target trigger anywhere",
      isinstance(on, dict) and not ({"pull_request", "pull_request_target"} & set(on)))
push = (on or {}).get("push") or {}
check("push runs on branch main only", push.get("branches") == ["main"])
check("push runs on v* tags only", push.get("tags") == ["v*"])

check("workflow default permissions are contents: read only",
      wf.get("permissions") == {"contents": "read"})
check("jobs are version, image, github-release",
      set(jobs) == {"version", "image", "github-release"})
for name, job in jobs.items():
    check(f"job {name} uses the RUNS_ON_PUBLISH runner line", job.get("runs-on") == RUNS_ON)

image = jobs.get("image", {})
steps = image.get("steps", [])
by_name = {s.get("name", ""): s for s in steps}
check("image job keeps packages: write and contents: read",
      image.get("permissions") == {"contents": "read", "packages": "write"})
pre = by_name.get("Preflight, cross-repo package read", {})
check("image job keeps the cross-repo package preflight",
      "PACKAGES_READ_TOKEN" in str(pre.get("env", {})) and "maven.pkg.github.com" in pre.get("run", ""))
refuse = by_name.get("Refuse to overwrite a published version", {})
check("release runs refuse to overwrite a published version",
      refuse.get("if") == "needs.version.outputs.kind == 'release'"
      and "imagetools inspect" in refuse.get("run", ""))
build = by_name.get("Build and push image", {}).get("with", {})
check("image tags come only from the version guard",
      build.get("tags") == "${{ needs.version.outputs.tags }}")
check("BuildKit packages_read_token secret is kept",
      "packages_read_token=${{ secrets.PACKAGES_READ_TOKEN }}" in str(build.get("secrets", "")))
check("OCI source label links the package to this repo",
      "org.opencontainers.image.source=https://github.com/${{ github.repository }}" in str(build.get("labels", "")))
code = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))
check("no literal :latest tag in the workflow (comments aside)", ":latest" not in code)

rel = jobs.get("github-release", {})
check("github-release runs only on a release run",
      rel.get("if") == "needs.version.outputs.kind == 'release'")
check("only github-release gets contents: write", rel.get("permissions") == {"contents": "write"}
      and all("write" not in str(j.get("permissions", {}).get("contents", ""))
              for n, j in jobs.items() if n != "github-release"))
rel_run = "".join(s.get("run", "") for s in rel.get("steps", []))
check("a beta release is prerelease and never latest",
      "--prerelease --latest=false" in rel_run and "--verify-tag" in rel_run)

guard = jobs.get("version", {})
check("version job runs scripts/release-version.sh",
      any("scripts/release-version.sh" in s.get("run", "") for s in guard.get("steps", [])))
# This repository has no GitHub CI workflow, so the release workflow is where these tests run.
runs = [s.get("run", "") for s in guard.get("steps", [])]


def first(fragment):
    return next((i for i, r in enumerate(runs) if fragment in r), None)


g = first("bash scripts/release-version.sh")
tv = first("bash scripts/test-release-version.sh")
tw = first("python3 scripts/test-release-workflow.py")
check("version job runs both release tests before the guard",
      None not in (g, tv, tw) and tv < g and tw < g)

if failures:
    print(f"\ntest-release-workflow: {failures} failure(s)", file=sys.stderr)
    sys.exit(1)
print("\ntest-release-workflow: all checks passed")
