#!/usr/bin/env bash
# run_itest.sh Run integration tests and retrieve logs from localstack.


# set -e    # (errexit): Exit immediately if a command exits with a non-zero status.
# set -x  # (xtrace): Print each command to stdout before executing it (useful for debugging).

# SCRIPT_DIR is the directory where the script is located.
# All the operations are relative to this location regardless of where you are when the
# script is executed.
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
GW=$(realpath $SCRIPT_DIR/../../gradlew)

cd $SCRIPT_DIR

TEST_FILTER="${1:-}"

if [[ -n "$TEST_FILTER" ]]; then
    (set -x; $GW integrationTest --tests "$TEST_FILTER")

else
    (set -x; $GW integrationTest --rerun-tasks)
fi
GW_STATUS=$?

sleep 5 # Give it a bit of time to complete logging.
./export_awslogs.sh

exit $GW_STATUS