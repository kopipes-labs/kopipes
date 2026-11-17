#!/usr/bin/env bash
# deploy_stacks.sh Deploy one or more payments stacks to LocalStack.
#
# Usage:
#   ./deploy_stacks.sh
#   ./deploy_stacks.sh payments-event-hub
#   ./deploy_stacks.sh payments-event-hub payments-control-service payments-bff
#   ./deploy_stacks.sh -d payments-event-hub

set -e    # (errexit): Exit immediately if a command exits with a non-zero status.
# set -x  # (xtrace): Print each command to stdout before executing it (useful for debugging).

# SCRIPT_DIR is the directory where the script is located.
# All the operations are relative to this location regardless of where the
# script is executed.
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
GW=$(realpath $SCRIPT_DIR/../../gradlew)
export CDK_DISABLE_LEGACY_EXPORT_WARNING=1 # Disable annoying warning.

# This is the logical order to deploy the stacks.
STACKS=(
	"payments-event-hub"
	"payments-event-fault-monitor"
	"payments-event-lake"
	"payments-core-checking-ingress-gateway"
	"payments-core-checking-egress-gateway"
	"payments-control-service"
	"payments-bff"
)

usage() {
	echo "Usage: $0 [-d] [stack ...]"
	echo
	echo "Options:"
	echo "  -d  Delete localstack state before deploying"
	echo
	echo "Available stacks:"
	printf '  %s\n' "${STACKS[@]}"
}

is_known_stack() {
	local stack="$1"

	for known_stack in "${STACKS[@]}"; do
		if [[ "$stack" == "$known_stack" ]]; then
			return 0
		fi
	done

	return 1
}

cdklocal_deploy() {
	(
		cd "$SCRIPT_DIR/$1/infra"
		cdklocal bootstrap
		cdklocal deploy "$1-local" --require-approval never
	)
}

if [[ "$#" -eq 0 ]]; then
	STACKS_TO_DEPLOY=("${STACKS[@]}")
else
	DELETE_LOCALSTACK_STATE=false
	ARGS=()

	for arg in "$@"; do
		if [[ "$arg" == "-d" ]]; then
			DELETE_LOCALSTACK_STATE=true
		else
			ARGS+=("$arg")
		fi
	done

	if [[ "${#ARGS[@]}" -eq 0 ]]; then
		STACKS_TO_DEPLOY=("${STACKS[@]}")
	else
		STACKS_TO_DEPLOY=("${ARGS[@]}")
	fi

	for stack in "${STACKS_TO_DEPLOY[@]}"; do
		if ! is_known_stack "$stack"; then
			echo "Unknown stack: $stack" >&2
			echo >&2
			usage >&2
			exit 1
		fi
	done
fi

(
	# The framework's tests and this example's, then each service's jar.
	cd "$SCRIPT_DIR/../.."
	$GW :core:test :aws:test :cdk:test
	cd "$SCRIPT_DIR"
	$GW test shadowJar
)

if [[ "${DELETE_LOCALSTACK_STATE:-false}" == true ]]; then
	curl -X DELETE http://localhost:4566/_localstack/state
fi

for stack in "${STACKS[@]}"; do
	for requested_stack in "${STACKS_TO_DEPLOY[@]}"; do
		if [[ "$stack" == "$requested_stack" ]]; then
			cdklocal_deploy "$stack"
			break
		fi
	done
done
