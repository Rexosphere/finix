package finix.authz_test

import data.finix.authz
import future.keywords.if

test_customer_own_account if {
	authz.allow with input as {
		"role": "customer",
		"subject": "user-1",
		"action": "view",
		"resource": {"owner": "user-1"},
	}
}

test_customer_other_account_denied if {
	not authz.allow with input as {
		"role": "customer",
		"subject": "user-1",
		"action": "view",
		"resource": {"owner": "user-2"},
	}
}

test_teller_transfer_within_limit if {
	authz.allow with input as {
		"role": "teller",
		"action": "transfer",
		"resource": {"amount_minor": 50000},
		"limits": {"teller_max_minor": 100000},
	}
}

test_teller_transfer_over_limit_denied if {
	not authz.allow with input as {
		"role": "teller",
		"action": "transfer",
		"resource": {"amount_minor": 200000},
		"limits": {"teller_max_minor": 100000},
	}
}

test_compliance_read_only if {
	authz.allow with input as {"role": "compliance", "action": "view"}
	not authz.allow with input as {"role": "compliance", "action": "transfer"}
}
