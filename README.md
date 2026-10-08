# UAT Hub

Give UAT users test scenarios to run, raise issues from failed steps, get a business decision on each
issue, and push the approved ones to Jira.
One Spring Boot JAR, an embedded H2 file database, no installed software and no passwords.

## Run it

Needs Java 17+ and Maven.

```bash
mvn clean package
java -jar target/uat-hub.jar
```

On first start the console prints the **admin link**:

```
================ UAT Hub admin access ================
  Admin: http://localhost:8080/join/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Open it, then:

1. **Projects & Jira**: rename "My project", set the UAT cycle and the Jira mapping.
2. **Team & access**: add people with a role and project, copy each personal link and send it to them.

The link is printed again on every start, so the admin can't get locked out.

## Running on a shared machine

Set the address people will use, so links are built correctly:

```bash
java -jar target/uat-hub.jar --uathub.base-url=http://your-vm-name:8080
```

Or edit `src/main/resources/application.properties`. `run.bat` / `run.sh` wrap this with the Jira settings.

## Jira

Set these as environment variables before starting (never commit the token):

| Variable | Cloud | Data Center / Server |
|---|---|---|
| `JIRA_BASE_URL` | `https://yourco.atlassian.net` | `https://jira.yourco.com` |
| `JIRA_DEPLOYMENT` | `CLOUD` | `DATA_CENTER` |
| `JIRA_EMAIL` | your Atlassian email | not used |
| `JIRA_TOKEN` | API token | personal access token |

Per project (in **Projects & Jira**): Jira project key, issue types for Defect and Enhancement/Future,
optional components, extra labels and fix version. Components and fix versions must already exist in Jira.

Each issue gets the summary `[UAT] <title>`, a description with expected/actual, QA note, business decision and
rationale, labels `UAT-Feedback`, the cycle, LOB, division and the UAT ID, plus all attachments.

## How access works (no login)

- Each person has a secret personal link: `/join/<token>`.
- Opening it shows a welcome page; **Continue** sets an HttpOnly cookie, and the browser is recognised from then on.
  Link previews in Teams/Outlook only fetch the page, so they can't sign anyone in.
- Roles are enforced on the server for every request, not only hidden in the UI:

| | Tester | QA lead | Business | Admin |
|---|---|---|---|---|
| Run assigned scenarios, raise issues from steps | ● | ● | ● | ● |
| Scenario library, test runs, assignment, new builds | | ● | | ● |
| LOB sign-off | | | ● | ● |
| Log general feedback | ● | ● | | ● |
| Triage, edit, import Excel | | ● | | ● |
| Record decision | | | ● | ● |
| Push to Jira | | ● | | ● |
| Team & projects | | | | ● |

- **New link** replaces the token: the old link and any browser using it stop working immediately.
- **Revoke** blocks the person entirely. **Reactivate** gives them a fresh link.
- Optional: **Ask for a PIN on new browsers** adds a 4-digit PIN (stored as a BCrypt hash).
- Every action is recorded in the item's trail with the person's name.

Treat links like passwords. For use beyond the internal network, put the app behind HTTPS
(the cookie is then sent as Secure automatically).

## Testing with scenarios

1. **Scenario library** (QA lead): import the scenario sheet or write scenarios in the app. Each has an ID
   (e.g. SC-014), title, LOB, division, module, priority, preconditions, test data and numbered steps with
   expected results.
2. **Test run** (QA lead): start a run, e.g. "UAT cycle 3" on build "4.2.1", then **Assign scenarios** to
   testers and business users (per row, or tick several and assign in bulk).
3. **My scenarios** (tester or business user): work through each scenario step by step, marking Pass, Fail,
   Blocked or N/A, with the actual result and pasted screenshots.
4. **Raise issue** on a failed or blocked step creates a feedback item pre-filled with the scenario, step,
   expected and actual result, LOB, division, module, build and the step's evidence. It then follows the
   normal flow: triage → business decision → Jira. You can also link an issue that is already logged.
5. **Deploy new build** (QA lead): record the new build and tick the issues it fixes. Their scenarios go back
   to the testers as **Re-test**. Finishing a re-test closes the issue as verified if the step now passes,
   or reopens it if it fails again.
6. **Sign-off** (business): sign off each LOB on the Test run page. Unfinished scenarios or open issues need
   a note, and the open issues are recorded as accepted exceptions.

**Export report** on the Test run page downloads every scenario's status, step counts and linked issues.

### Scenario Excel import

One row per step. Rows with the same Scenario ID become one scenario, and its first row supplies the
scenario-level columns. Headers are matched by name, in any order:

| Column | Also accepted |
|---|---|
| Scenario ID | ID, Test ID, TC ID |
| Title | Scenario title, Scenario name, Summary |
| LOB | Line of business |
| Division | Region |
| Module | Case type, Area, Feature |
| Priority | Severity (High / Medium / Low, or P1–P4) |
| Preconditions | Pre-requisites |
| Test data | Data |
| Step | Step no |
| Action (required) | Test step, Step description |
| Expected | Expected result |

Importing a Scenario ID that already exists updates it. Steps that already have results can be reworded but
not removed; archive the scenario and create a new one instead.

## Workflow

```
Logged → (Needs info ↺) → Business review → Decided → In Jira
                                           ↘ Closed (comment / works as designed / duplicate)
```

## Data and backup

Everything lives in `./data` next to the JAR:

- `data/uat-hub.mv.db`: the database
- `data/attachments/<id>/`: screenshots and files on feedback items
- `data/attachments/steps/<id>/`: evidence captured on scenario steps

Back up by stopping the app and copying the `data` folder. Change the location with `--uathub.data-dir=...`.

## Excel import

**Import Excel** on the register reads the first sheet and matches columns by header name
(order doesn't matter). Recognised headers include: Title / Summary / Feedback (required), Description / Details /
Comments, LOB / Line of business, Division, Module / Case type / Stage, Screen, Type / Category,
Severity / Priority, Expected, Actual. Imported items start at *Logged*, ready for triage.

## Reference lists

LOBs and divisions are set in `application.properties` (`uathub.lobs`, `uathub.divisions`).

## Project layout

```
src/main/java/com/uathub
├── config/     UatHubProperties
├── domain/     Project, AppUser, Feedback, Attachment, AuditEntry,
│               Scenario, ScenarioStep, TestRun, Execution, StepResult, IssueLink, LobSignOff, enums
├── repo/       Spring Data repositories
├── security/   AccessCookieFilter (replaces login), SecurityConfig, CurrentUser
├── service/    FeedbackService, TestingService, ScenarioService, JiraService, ExcelService,
│               TeamService, AttachmentStorage, Bootstrap
└── web/        Controllers, GlobalModel (nav + errors), Fmt (template helpers)
src/main/resources
├── templates/  Thymeleaf pages
└── static/     app.css (night ledger theme), app.js
```
