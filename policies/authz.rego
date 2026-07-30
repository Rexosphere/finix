# FINIX ABAC policies — evaluated by the OPA sidecar (compose --profile security).
package finix.authz

import future.keywords.if
import future.keywords.in

default allow := false

# Customers may act only on their own accounts.
allow if {
	input.role == "customer"
	input.action in {"view", "transfer"}
	input.resource.owner == input.subject
}

# Tellers may view any account but transfer only up to the configured limit.
allow if {
	input.role == "teller"
	input.action == "view"
}

allow if {
	input.role == "teller"
	input.action == "transfer"
	input.resource.amount_minor <= input.limits.teller_max_minor
}

# Compliance / regulator: read anything, write nothing.
allow if {
	input.role in {"compliance", "regulator"}
	input.action == "view"
}

# Admins and service accounts: full access within their own APIs.
allow if {
	input.role in {"admin", "service-account"}
}
