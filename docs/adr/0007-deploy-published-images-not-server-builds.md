# 7. Deploy published images, never build on the server

- Status: Accepted
- Date: 2026-08-06

## Context

Two pipelines existed side by side and never met. `publish-images.yml` built and pushed a
per-service image to GHCR on every push to `master`. `deploy.sh` — which lived only inside a
cloud-init heredoc — ignored those images entirely: it git-pulled `master` on the server, ran nine
Gradle `bootJar` tasks, `sed`-ed tracked HTML in place to rewrite `http://localhost:3000` links, and
ran `docker compose up -d --build`.

The consequences were not theoretical:

- **Production ran an artifact no CI job had ever seen.** Different machine, different Gradle cache,
  different base-image pull time. "It passed CI" said nothing about what was running.
- **`publish-images.yml` raced CI**, both triggered by the same push, so images could be published
  from a commit whose tests were still running — or had already failed.
- **There was no rollback.** The previous state was a `git reset --hard` and a 10-minute rebuild away.
- **Deploys took as long as a full build**, on a box also serving traffic.
- **The `sed` fought the deploy**: it dirtied the working tree, which is why the script had to
  `reset --hard` on every run.

## Decision

**Chain the workflows, ship the images.**

```
CI (JVM + Go + Python + Node + scans)  →  Publish images (sha-<short>)  →  Deploy (that sha)
```

`publish-images.yml` triggers on `workflow_run` of a *successful* CI; `deploy.yml` triggers on a
successful publish and passes `workflow_run.head_sha` through to the server. Because a
`workflow_run` event reports the default-branch head in `github.sha`, both workflows use
`head_sha` explicitly for checkout and for the image tag.

`infra/deploy/deploy.sh` is tracked in the repository (not embedded in cloud-init) and:

1. checks out that exact commit, detached — compose files, nginx configs and static assets all
   match the images;
2. pulls `ghcr.io/…/<service>:sha-<short>` and starts the stack with `--no-build`;
3. waits until **every** container reports healthy, using the `HEALTHCHECK`s that now live in the
   images rather than curling four hardcoded ports;
4. on failure, redeploys the previous entry in `/opt/finix/releases.log` and still exits non-zero,
   so production is left working and the workflow goes red.

The `sed` is gone. Cross-app URLs are served by nginx at `/env.js`, rendered from the container
environment at start, with the localhost values kept as the HTML default so `make demo` is
unchanged. The `apps/admin` fallback to `http://localhost:8086` — which only ever worked for a
browser on the server itself — is deleted in favour of the same-origin `/api/vault/` path that
`nginx.conf` already proxies.

## Consequences

**Positive.** What CI verified is byte-for-byte what runs. A deploy is a pull, not a build (minutes
to seconds, no JDK or Gradle cache on the server). Rollback is `infra/deploy/rollback.sh` — the old
images are still in GHCR. Every release is addressable: `sha-<short>` is immutable, unlike `latest`.

**Negative.** A deploy cannot outrun the image build, so a hotfix waits for CI and publish
(~10 minutes) instead of building on the box. GHCR becomes a hard dependency of deploying: if it is
down or the packages go private, deploys stop — the failure is loud rather than silent. The server
still needs a git checkout, because compose files, nginx templates and the static apps come from the
repo rather than from an image.

**Deferred.** No staging environment: promotion safety comes from the health gate and automatic
rollback, not from a second stack. Adding one is a third compose project on the same host and a
promotion job between publish and deploy.
