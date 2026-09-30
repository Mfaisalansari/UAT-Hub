@echo off
REM Edit these, then double-click run.bat
set JIRA_BASE_URL=https://yourco.atlassian.net
set JIRA_DEPLOYMENT=CLOUD
set JIRA_EMAIL=you@yourco.com
set JIRA_TOKEN=paste-token-here

java -jar target\uat-hub.jar --uathub.base-url=http://%COMPUTERNAME%:8080
pause
