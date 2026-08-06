#!/usr/bin/env bash
# Apply branch protection to master via the GitHub API.
#
#   ./scripts/setup-branch-protection.sh [owner/repo] [branch]
#
# Branch protection is repository *settings*, not repository *files*: nothing in
# this codebase can enforce it, and a reviewer cannot tell from the source
# whether it is on. This script makes the intended configuration explicit,
# reviewable and re-appliable — run it with a token that has admin rights.
#
# Verify what is actually in force:
#   gh api repos/<owner>/<repo>/branches/master/protection | jq
set -euo pipefail

REPO="${1:-Rexosphere/finix}"
BRANCH="${2:-master}"

command -v gh >/dev/null 2>&1 || {
  echo "gh CLI is required: https://cli.github.com" >&2
  exit 1
}

# Every job in .github/workflows/ci.yml. Deploy is deliberately absent: it runs
# after the merge, so requiring it here would deadlock.
read -r -d '' PAYLOAD <<'JSON' || true
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "JVM verify",
      "Go — payment-hub",
      "Python — risk-ai-service",
      "Node — notification-service",
      "Compose config",
      "Ansible syntax",
      "Secret scan",
      "Dependency CVEs",
      "CodeQL (go)",
      "CodeQL (python)",
      "CodeQL (javascript)",
      "Conventional commits"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": 1,
    "require_code_owner_reviews": true,
    "dismiss_stale_reviews": true
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
JSON

echo "==> Applying branch protection to $REPO@$BRANCH"
echo "$PAYLOAD" | gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  "repos/$REPO/branches/$BRANCH/protection" \
  --input - >/dev/null

echo "==> In force now:"
gh api "repos/$REPO/branches/$BRANCH/protection" \
  --jq '{checks: .required_status_checks.contexts,
         reviews: .required_pull_request_reviews.required_approving_review_count,
         code_owners: .required_pull_request_reviews.require_code_owner_reviews,
         linear_history: .required_linear_history.enabled,
         force_pushes: .allow_force_pushes.enabled}'
