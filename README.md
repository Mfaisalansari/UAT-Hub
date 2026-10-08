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

1. **Projects & Jira**: rename "My project" and set the Jira mapping. **UAT cycles**: rename "UAT cycle 1"
   (e.g. "October 2026 release") and add dates.
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
| UAT cycles, scenario library, runs, assignment, new builds | | ● | | ● |
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

## UAT cycles

A project has one **UAT cycle** per UAT window, e.g. "October 2026 release" and "November 2026 release".

```
Project ─┬─ Scenario library (shared by every cycle)
         ├─ UAT cycle: October 2026 ─┬─ Run 1 (build 4.2.1)
         │                           ├─ Run 2 (build 4.2.3)
         │                           └─ feedback, issues, LOB sign-offs
         └─ UAT cycle: November 2026 ─ ...
```

- **Add a cycle**: QA lead or admin → **UAT cycles** → name, release, start and end dates → **Create cycle**.
- **Switch cycles**: the **UAT cycle** picker in the sidebar, under the project. Everything you see (register,
  My scenarios, Cycle & runs) is for that cycle. The register's **Show all cycles** link lists every cycle at once.
- **Close a cycle** when its UAT window ends: its data stays viewable, but nothing new can be added to it.
- Each cycle keeps its own runs, step results, feedback, issues and sign-offs, so October stays exactly as it
  was when November starts. Feedback and Jira issues carry the cycle name.

Data created before cycles existed is moved into a cycle on start-up (named after the project's old
"current UAT cycle" field, or "UAT cycle 1").

## Testing with scenarios

1. **Scenario library** (QA lead): import scenarios from Excel or write them in the app. **Download template**
   gives a sample sheet with three example scenarios and a *How to fill* tab. Each scenario has an ID
   (e.g. SC-014), title, LOB, division, module, priority, preconditions, test data and numbered steps with
   expected results.
2. **Cycle & runs** (QA lead): start one or more runs in the cycle, e.g. "Run 1" on build "4.2.1", or parallel
   runs such as "Marine regression". A new run can start with the scenarios of an earlier run: all of them, or
   only those not passed. Then **Assign scenarios** to testers and business users (per row, or in bulk).
3. **My scenarios** (tester or business user): everything assigned to you in the cycle's open runs. Work through
   each scenario step by step, marking Pass, Fail, Blocked or N/A, with the actual result and pasted screenshots.
4. **Raise issue** on a failed or blocked step creates a feedback item pre-filled with the scenario, step,
   expected and actual result, LOB, division, module, build and the step's evidence. It then follows the
   normal flow: triage → business decision → Jira. You can also link an issue that is already logged.
5. **Deploy new build** (QA lead, on a run's page): record the new build and tick the issues it fixes. Their scenarios go back
   to the testers as **Re-test**. Finishing a re-test closes the issue as verified if the step now passes,
   or reopens it if it fails again.
6. **Sign-off** (business): sign off each LOB for the whole cycle on the **Cycle & runs** page. It uses each
   scenario's latest result across all runs. Unfinished scenarios or open issues need a note, and the open
   issues are recorded as accepted exceptions.

**Export cycle report** gives every scenario's latest result in the cycle; **Export run** on a run's page gives
that run only.

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

## Themes

Everyone can pick **Dark**, **Light** or **Auto** (follows the computer's setting) at the bottom of the sidebar.
The choice is saved in that browser. All colours are tokens at the top of `static/css/app.css`: the dark set
under `:root`, the light set under `html[data-theme="light"]`, so a new theme is one more block of tokens.

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
│               UatCycle, Scenario, ScenarioStep, TestRun, Execution, StepResult, IssueLink, CycleSignOff, enums
├── repo/       Spring Data repositories
├── security/   AccessCookieFilter (replaces login), SecurityConfig, CurrentUser
├── service/    FeedbackService, CycleService, TestingService, ScenarioService, JiraService, ExcelService,
│               TeamService, AttachmentStorage, Bootstrap, SchemaFixes
└── web/        Controllers, GlobalModel (nav + errors), Fmt (template helpers)
src/main/resources
├── templates/  Thymeleaf pages
└── static/     app.css (night ledger theme), app.js
```
