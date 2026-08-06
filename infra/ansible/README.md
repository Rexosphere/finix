# FINIX server provisioning

Everything the production host needs, as an idempotent playbook. It replaces the one-shot
cloud-init script that used to configure the server once, at first boot, and could never be re-run
without rebuilding the machine — and whose output (`/opt/finix/docker-compose.override.yml`,
`monitoring.env`, the Caddyfile, `deploy.sh`) existed nowhere but on that machine.

```bash
# From a workstation with SSH access to the server:
make provision                       # ansible-playbook -i inventory/production.yml site.yml
make provision-check                 # --check --diff, changes nothing

# On the server itself (this is what cloud-init runs at first boot):
ansible-pull -U https://github.com/Rexosphere/finix.git -d /opt/finix/repo \
  -i infra/ansible/inventory/localhost.yml infra/ansible/site.yml
```

## What each role owns

| Role | Responsibility |
|---|---|
| `base` | packages, timezone, swap, unattended-upgrades, sshd hardening |
| `docker` | Docker Engine from the official repo, `daemon.json` (log rotation, live-restore) |
| `firewall` | ufw + ufw-docker, so Docker's own iptables rules cannot bypass the default-deny |
| `caddy` | TLS termination and reverse proxy for the public hostnames |
| `finix` | app directory, generated secrets, `finix.env`, nightly Postgres backup timer |

Deploying the application is deliberately *not* an Ansible role: that is
`infra/deploy/deploy.sh`, driven by the Deploy workflow (ADR-0007). Provisioning prepares a host;
deploying moves a release onto it.

## Configuration

All environment-specific values are inventory variables — nothing is hardcoded to
`roboti.qzz.io`:

```yaml
finix_domain: roboti.qzz.io
finix_admin_domain: admin.roboti.qzz.io
finix_auth_domain: auth.roboti.qzz.io
finix_grafana_domain: grafana.roboti.qzz.io
finix_acme_email: you@example.com
```

## Secrets

The playbook never carries a credential. It runs `scripts/gen-secrets.sh`, which creates any
*missing* secret on the host and leaves existing ones alone, so re-running the playbook cannot
invalidate the Postgres roles. Read the console logins on the server with:

```bash
/opt/finix/app/scripts/gen-secrets.sh --show
```
