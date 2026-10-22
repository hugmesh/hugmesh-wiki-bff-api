#!/usr/bin/env python3
"""Shape checks for .github/workflows/release.yml. Pure text, no network.

Run: python3 scripts/test-release-workflow.py   (needs PyYAML; fails, never skips, without it)

What it pins, and why:
  * No pull_request / pull_request_target trigger. This workflow holds packages:write and may
    run on the self-hosted runner; a PR must never reach it.
  * Triggers are exactly: push to main, push of v* tags, workflow_dispatch.
  * version and github-release use the one runs-on line that picks vars.RUNS_ON_PUBLISH only
    for a non-PR event on main or a v* / studio-v* tag, and ubuntu-latest otherwise.
  * The image job (build, push, sign) runs on the GitHub-hosted ubuntu-latest, written
    literally: a published image is never built on a self-hosted runner. It signs the pushed
    DIGEST with cosign keyless and pushes provenance and SBOM attestations, and only it holds
    id-token: write and attestations: write.
  * notify-site is the only other job: hosted, no permissions, v* tags only, after the Release.
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
check("jobs are version, image, github-release, notify-site",
      set(jobs) == {"version", "image", "github-release", "notify-site"})
for name in ("version", "github-release"):
    check(f"job {name} uses the RUNS_ON_PUBLISH runner line", jobs.get(name, {}).get("runs-on") == RUNS_ON)
# The signed publish job never runs on the self-hosted runner: literal hosted label only.
check("job image runs on the hosted ubuntu-latest runner, written literally",
      jobs.get("image", {}).get("runs-on") == "ubuntu-latest")

image = jobs.get("image", {})
steps = image.get("steps", [])
by_name = {s.get("name", ""): s for s in steps}
check("image job has exactly contents: read, packages: write, id-token: write, attestations: write",
      image.get("permissions") == {"contents": "read", "packages": "write",
                                   "id-token": "write", "attestations": "write"})
check("only the image job gets id-token or attestations",
      all(not ({"id-token", "attestations"} & set(j.get("permissions") or {}))
          for n, j in jobs.items() if n != "image")
      and not ({"id-token", "attestations"} & set(wf.get("permissions") or {})))
names = [s.get("name", "") for s in steps]
ids = {s.get("id"): i for i, s in enumerate(steps) if s.get("id")}
signref = steps[ids["signref"]] if "signref" in ids else {}
check("signing resolves the digest from the build step and refuses latest and non-sha256",
      "build" in ids and "signref" in ids and ids["build"] < ids["signref"]
      and signref.get("if") == "steps.build.outcome == 'success'"
      and "steps.build.outputs.digest" in str(signref.get("env", {}))
      and 'sha256:[0-9a-f]{64}' in signref.get("run", "")
      and '"latest" in tags' in signref.get("run", ""))


def after_signref(name):
    i = names.index(name) if name in names else -1
    return i > ids.get("signref", 10**6) and steps[i].get("if") == "steps.signref.outcome == 'success'"


sign = by_name.get("Sign the image digest (cosign keyless, GitHub OIDC)", {})
check("image is signed by digest with cosign keyless",
      after_signref("Sign the image digest (cosign keyless, GitHub OIDC)")
      and sign.get("run", "").strip() == 'cosign sign --yes "$REF"'
      and sign.get("env", {}).get("REF") == "${{ steps.signref.outputs.repo }}@${{ steps.signref.outputs.digest }}")
for step, action in (("Attest build provenance (pushed to the registry)", "actions/attest-build-provenance@"),
                     ("Attest the SBOM (pushed to the registry)", "actions/attest-sbom@")):
    st = by_name.get(step, {})
    w = st.get("with", {})
    check(f"{step}: {action} on the pushed digest",
          after_signref(step) and str(st.get("uses", "")).startswith(action)
          and w.get("subject-digest") == "${{ steps.signref.outputs.digest }}"
          and w.get("push-to-registry") is True)
note = jobs.get("notify-site", {})
check("notify-site is hosted, has no permissions, runs only for v* tags after the Release",
      note.get("runs-on") == "ubuntu-latest" and note.get("permissions") == {}
      and note.get("if") == "startsWith(github.ref, 'refs/tags/v')"
      and note.get("needs") == ["github-release"])
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
