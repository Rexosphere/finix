# What and why

<!-- What changes, and what problem it solves. Link the issue or ADR if there is one. -->

## How it was verified

<!-- The commands you actually ran, with their outcome. "CI is green" is not verification
     of anything CI does not cover — a compose change needs a stack that started. -->

- [ ] `make verify` (JVM: compile, unit, ArchUnit, coverage, detekt)
- [ ] `make demo` starts and `bash tests/e2e/smoke.sh` passes
- [ ] Non-JVM tests where touched (`go test ./...`, `pytest -q`, `npm test`)

## Risk

<!-- What breaks if this is wrong, and how it is undone. A deploy is rolled back with
     infra/deploy/rollback.sh; a schema change may not be. -->

## Checklist

- [ ] No credential, token or key in the diff — including in test fixtures and comments
- [ ] A decision worth challenging later is recorded in `docs/adr/`
- [ ] Runbooks updated if this changes how an incident is handled
- [ ] Commits follow Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:` …)
