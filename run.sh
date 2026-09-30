#!/usr/bin/env bash
# Edit these, then: ./run.sh
export JIRA_BASE_URL="https://yourco.atlassian.net"
export JIRA_DEPLOYMENT="CLOUD"          # or DATA_CENTER
export JIRA_EMAIL="you@yourco.com"      # Cloud only
export JIRA_TOKEN="paste-token-here"

java -jar target/uat-hub.jar --uathub.base-url="http://$(hostname):8080"
