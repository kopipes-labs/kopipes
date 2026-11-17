#!/usr/bin/env bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
GW=$(realpath $SCRIPT_DIR/../../gradlew)

cd $SCRIPT_DIR

LOGS=./.awslocal_logs
mkdir -p $LOGS

# I use lnav to view the logs. This prefilter makes them easier to parse.
prefilter() {
	jq -c '.events[]'
}

function parsevalue() {
  perl -ne 'print "$1\n" if /\s*=\s*"([^\"]*)"/';
}

# Clean up older files.
rm -f $LOGS/payments-*.json

awslocal logs describe-log-groups | gron | grep logGroupName | while read LINE
do
  LOG_GROUP_NAME=$(echo $LINE | parsevalue)
  LOG_FILE=$(echo $LOG_GROUP_NAME |  perl -pe 's/-local-/-/g' )
  LOG_FILE=$(basename $LOG_FILE)
  awslocal logs filter-log-events --log-group-name "$LOG_GROUP_NAME" | prefilter > $LOGS/$LOG_FILE.json
done
