# HugMesh wiki-bff-api — developer make targets.
#
# `make pentest` runs the SAME OWASP checks the CI pentest stage runs, locally:
#   * SCA  : OWASP Dependency-Check over this repo's Maven deps (mirrors CI
#            `pentest:sca` / .pentest-sca). Uses the eclipse-temurin image like the
#            CI `test` job so it works with no host JDK; falls back to ./mvnw on the
#            host when docker is unavailable.
#   * DAST : the FULL shared harness (run-pentest.sh: env-test-build + authenticated
#            ZAP baseline + escalation/DoS probes + nuclei) from hugmesh-generator,
#            run against $DAST_TARGET_URL when set (mirrors CI `pentest:dast` /
#            .pentest-dast-baseline). Skipped (non-fatal) when no target/harness.
#
# Self-contained + non-hard-failing: with no docker / no DAST target / no harness it
# prints guidance and still exits 0 so a developer can run it anywhere.

# CVSS gate mirrors the inline P2-1 self-scan (CI gate via the template is CVSS 7).
PENTEST_FAIL_CVSS ?= 9
JAVA_IMAGE        ?= eclipse-temurin:25-jdk-noble
# Where the shared DAST harness lives. Defaults to a sibling checkout of the
# generator; override to a vendored/cloned copy.
PENTEST_HARNESS_DIR ?= ../hugmesh-generator/tests/pentest
GENERATOR_REPO      ?= https://github.com/hugmesh-net/hugmesh-generator.git

.PHONY: pentest pentest-sca pentest-dast

## pentest: run the local SCA self-scan + (when $DAST_TARGET_URL set) the DAST harness
pentest: pentest-sca pentest-dast
	@echo "[pentest] done."

## pentest-sca: OWASP Dependency-Check over this repo (mirrors CI pentest:sca)
pentest-sca:
	@echo "[pentest-sca] OWASP Dependency-Check (failBuildOnCVSS=$(PENTEST_FAIL_CVSS))"
	@if command -v docker >/dev/null 2>&1; then \
		echo "[pentest-sca] via docker image $(JAVA_IMAGE) (matches CI)"; \
		docker run --rm \
			-v "$(CURDIR):/workspace" \
			-v "$(CURDIR)/.dep-check-data:/dep-check-data" \
			-w /workspace --entrypoint "" \
			$(JAVA_IMAGE) \
			./mvnw org.owasp:dependency-check-maven:check \
				-Dformat=HTML,JSON \
				-DfailBuildOnCVSS=$(PENTEST_FAIL_CVSS) \
				-DdataDirectory=/dep-check-data \
				-DautoUpdate=true -B ; \
	else \
		echo "[pentest-sca] docker not found — using host ./mvnw"; \
		./mvnw org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=$(PENTEST_FAIL_CVSS) -B ; \
	fi

## pentest-dast: run the shared OWASP harness against $DAST_TARGET_URL (mirrors CI pentest:dast)
pentest-dast:
	@if [ -z "$$DAST_TARGET_URL" ]; then \
		echo "[pentest-dast] DAST_TARGET_URL not set — skipping live DAST (advisory)."; \
		echo "[pentest-dast] To run the full harness (ZAP baseline + probes + nuclei):"; \
		echo "[pentest-dast]   1) get the harness: git clone $(GENERATOR_REPO)"; \
		echo "[pentest-dast]      (or set PENTEST_HARNESS_DIR to an existing checkout)"; \
		echo "[pentest-dast]   2) DAST_TARGET_URL=http://localhost:8080 make pentest-dast"; \
		exit 0; \
	fi; \
	if [ -x "$(PENTEST_HARNESS_DIR)/run-pentest.sh" ]; then \
		echo "[pentest-dast] running shared harness against $$DAST_TARGET_URL"; \
		bash "$(PENTEST_HARNESS_DIR)/run-pentest.sh" --env compose --scan baseline --pipeline main ; \
	else \
		echo "[pentest-dast] harness not found at $(PENTEST_HARNESS_DIR)/run-pentest.sh"; \
		echo "[pentest-dast] clone it: git clone $(GENERATOR_REPO)"; \
		echo "[pentest-dast] then: PENTEST_HARNESS_DIR=<path>/tests/pentest make pentest-dast"; \
		exit 0; \
	fi
