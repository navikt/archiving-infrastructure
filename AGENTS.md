# AGENTS.md

Local infrastructure, mocks, and integration tests for the archiving flow. It exercises `innsending-api`, `soknadsmottaker`, and `soknadsarkiverer` against simulated JOARK and SAF services.

## End-to-end tests

`system-tests/system-tests/src/test/kotlin/no/nav/soknad/arkivering/arkiveringsystemtests/EndToEndTests.kt`
is the main regression test for submissions across these services. It checks the
result in the archive mock and exercises no-login attachment upload and
deletion. Keep cross-service submission coverage there rather than
adding a separate test that mocks the same API requests. Add another test file
only when it checks behavior the end-to-end tests cannot reasonably cover.

Run `mise install`, then `mise exec -- ./run-end-to-end-tests.sh` from the
repository root. The script builds the component images before running the
Testcontainers suite. If those images are already current, run
`mise exec -- mvn clean install` from `system-tests/` instead.

`.github/workflows/on-pr.yml` runs the suite against this repository's PR
branch. Other repositories, including `innsending-api` in
`.github/workflows/run-e2e-tests.yml`, check out this repository and run its
Maven tests against their own PR branch. They use `archiving-infrastructure`
main by default, so coordinate changes to test clients before removing an API
endpoint and restore any temporary branch override after the client PR merges.
