package com.uathub.service;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.*;
import com.uathub.repo.*;
import com.uathub.web.FeedbackForm;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Test runs, assignment, step-by-step execution, issues raised from steps, re-tests and LOB sign-off. */
@Service
public class TestingService {

    public record Totals(long passed, long failed, long blocked, long retest, long notRun, long total) {
        public long done() { return passed + failed + blocked; }
        public int percentDone() { return total == 0 ? 0 : (int) Math.round(done() * 100.0 / total); }
    }

    public record LobRow(String lob, Totals totals, CycleSignOff signOff, long openIssues) {}

    /** A run with its own totals, for the cycle overview. */
    public record RunCard(TestRun run, Totals totals) {}

    public record IssueRow(Feedback feedback, String impact) {}

    public record TesterRow(AppUser person, long total, long done) {}

    private static final List<ExecStatus> QUEUE_ORDER = List.of(ExecStatus.RETEST, ExecStatus.IN_PROGRESS,
            ExecStatus.NOT_STARTED, ExecStatus.BLOCKED, ExecStatus.FAILED, ExecStatus.PASSED);

    private final TestRunRepository runs;
    private final ExecutionRepository executions;
    private final StepResultRepository results;
    private final IssueLinkRepository links;
    private final CycleSignOffRepository signOffs;
    private final ScenarioRepository scenarios;
    private final AppUserRepository users;
    private final FeedbackRepository feedbackRepo;
    private final FeedbackService feedbackService;
    private final AttachmentStorage storage;
    private final UatHubProperties props;

    public TestingService(TestRunRepository runs, ExecutionRepository executions, StepResultRepository results,
                          IssueLinkRepository links, CycleSignOffRepository signOffs, ScenarioRepository scenarios,
                          AppUserRepository users, FeedbackRepository feedbackRepo, FeedbackService feedbackService,
                          AttachmentStorage storage, UatHubProperties props) {
        this.runs = runs;
        this.executions = executions;
        this.results = results;
        this.links = links;
        this.signOffs = signOffs;
        this.scenarios = scenarios;
        this.users = users;
        this.feedbackRepo = feedbackRepo;
        this.feedbackService = feedbackService;
        this.storage = storage;
        this.props = props;
    }

    // ---------------------------------------------------------------- runs

    public List<TestRun> runs(UatCycle cycle) {
        return cycle == null ? List.of() : runs.findByUatCycleOrderByIdDesc(cycle);
    }

    public List<RunCard> runCards(UatCycle cycle) {
        return runs(cycle).stream().map(r -> new RunCard(r, totals(executions.findByRun(r)))).toList();
    }

    public TestRun loadRun(Long id, Project project) {
        TestRun r = runs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!r.getProject().getId().equals(project.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return r;
    }

    /**
     * Starts a run in a UAT cycle. Other runs in the cycle stay open, so several can run in parallel.
     * copyMode: "none", "all" (every scenario of copyFrom, same people) or "unfinished" (everything not passed).
     */
    @Transactional
    public TestRun createRun(UatCycle cycle, AppUser by, String name, String build, Long copyFromId, String copyMode) {
        if (!cycle.isOpen()) throw new IllegalArgumentException(cycle.getName() + " is closed. Reopen it to add runs.");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name the run, e.g. Run 2 or Marine regression");
        TestRun r = new TestRun();
        r.setProject(cycle.getProject());
        r.setUatCycle(cycle);
        r.setName(name.trim());
        r.setBuild(blankToNull(build));
        r.setCreatedBy(by);
        r = runs.save(r);
        if (copyFromId != null && copyMode != null && !"none".equals(copyMode)) {
            TestRun from = loadRun(copyFromId, cycle.getProject());
            for (Execution old : executions.findByRun(from)) {
                if ("unfinished".equals(copyMode) && old.getStatus() == ExecStatus.PASSED) continue;
                if (!old.getScenario().isActive()) continue;
                Execution e = new Execution();
                e.setRun(r);
                e.setScenario(old.getScenario());
                e.setAssignee(old.getAssignee() != null && old.getAssignee().isActive() ? old.getAssignee() : null);
                executions.save(e);
            }
        }
        return r;
    }

    /** Closing a run stops results being recorded in it; its history stays visible. */
    @Transactional
    public void setRunOpen(TestRun run, boolean open) {
        if (open && run.getUatCycle() != null && !run.getUatCycle().isOpen()) {
            throw new IllegalArgumentException("Reopen the UAT cycle before reopening its runs");
        }
        run.setActive(open);
        runs.save(run);
    }

    // ---------------------------------------------------------------- assignment

    /** People who may run scenarios in this project: Testers, Business reviewers, QA leads and Admins. */
    public List<AppUser> assignable(Project project) {
        return users.findAllByOrderByActiveDescNameAsc().stream()
                .filter(AppUser::isActive)
                .filter(u -> u.canAccess(project))
                .toList();
    }

    public Map<Long, Execution> executionsByScenario(TestRun run) {
        Map<Long, Execution> m = new HashMap<>();
        for (Execution e : executions.findByRun(run)) m.put(e.getScenario().getId(), e);
        return m;
    }

    /**
     * Applies the assignment form: scenarioId → userId (null = not in this run).
     * A scenario already being worked on keeps its history; it is only unassigned, never deleted.
     */
    @Transactional
    public int assign(TestRun run, Map<Long, Long> wanted) {
        Map<Long, Execution> current = executionsByScenario(run);
        int changed = 0;
        for (Map.Entry<Long, Long> w : wanted.entrySet()) {
            Execution e = current.get(w.getKey());
            Long userId = w.getValue();
            if (userId == null) {
                if (e == null) continue;
                boolean untouched = e.getStatus() == ExecStatus.NOT_STARTED && e.getAttempt() == 1
                        && links.findByExecution(e).isEmpty()
                        && results.findByExecutionAndAttempt(e, 1).isEmpty();
                if (untouched) executions.delete(e);
                else { e.setAssignee(null); executions.save(e); }
                changed++;
                continue;
            }
            AppUser person = users.findById(userId).filter(AppUser::isActive)
                    .filter(u -> u.canAccess(run.getProject()))
                    .orElseThrow(() -> new IllegalArgumentException("That person can't be assigned to this project"));
            if (e == null) {
                Scenario s = scenarios.findById(w.getKey())
                        .filter(x -> x.getProject().getId().equals(run.getProject().getId()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                e = new Execution();
                e.setRun(run);
                e.setScenario(s);
            } else if (e.getAssignee() != null && e.getAssignee().getId().equals(userId)) {
                continue;
            }
            e.setAssignee(person);
            executions.save(e);
            changed++;
        }
        return changed;
    }

    // ---------------------------------------------------------------- my queue and execution

    /** Everything assigned to this person in the cycle's open runs. */
    public List<Execution> myQueue(UatCycle cycle, AppUser me) {
        if (cycle == null) return List.of();
        List<Execution> list = new ArrayList<>(executions.findByRunUatCycleAndRunActiveTrueAndAssignee(cycle, me));
        list.sort(Comparator.comparingInt((Execution e) -> QUEUE_ORDER.indexOf(e.getStatus()))
                .thenComparing(e -> e.getScenario().getCode())
                .thenComparing(e -> -e.getRun().getId()));
        return list;
    }

    /** The assignee can run it; QA leads and admins can open any execution in their project. */
    public Execution loadExecution(Long id, AppUser me) {
        Execution e = executions.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!me.canAccess(e.getRun().getProject())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        boolean mine = e.getAssignee() != null && e.getAssignee().getId().equals(me.getId());
        boolean manager = me.getRole() == Role.QA_LEAD || me.getRole() == Role.ADMIN;
        if (!mine && !manager) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return e;
    }

    public boolean canRecord(Execution e, AppUser me) {
        boolean mine = e.getAssignee() != null && e.getAssignee().getId().equals(me.getId());
        boolean cycleOpen = e.getRun().getUatCycle() == null || e.getRun().getUatCycle().isOpen();
        return e.getRun().isActive() && cycleOpen && (mine || me.getRole() == Role.ADMIN);
    }

    /** Results for the current attempt, keyed by step id. */
    public Map<Long, StepResult> results(Execution e) {
        Map<Long, StepResult> m = new HashMap<>();
        for (StepResult r : results.findByExecutionAndAttempt(e, e.getAttempt())) m.put(r.getStep().getId(), r);
        return m;
    }

    public ScenarioStep step(Execution e, Long stepId) {
        return e.getScenario().getSteps().stream().filter(s -> s.getId().equals(stepId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public StepResult record(Execution e, ScenarioStep step, AppUser by, StepStatus status, String actual,
                             List<MultipartFile> files) {
        if (!canRecord(e, by)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the assigned person can record results");
        if (status == null) throw new IllegalArgumentException("Choose Pass, Fail, Blocked or N/A");
        if ((status == StepStatus.FAIL || status == StepStatus.BLOCKED) && (actual == null || actual.isBlank())) {
            throw new IllegalArgumentException("Describe what actually happened for a " + status.getLabel().toLowerCase() + " step");
        }
        StepResult r = results(e).get(step.getId());
        if (r == null) {
            r = new StepResult();
            r.setExecution(e);
            r.setStep(step);
            r.setAttempt(e.getAttempt());
        }
        r.setResult(status);
        r.setActual(cut(blankToNull(actual), 2000));
        r.setRecordedBy(by);
        r.setRecordedAt(Instant.now());
        r = results.save(r);
        storage.saveAll(r, files);
        if (e.getStartedAt() == null) e.setStartedAt(Instant.now());
        refreshStatus(e);
        return r;
    }

    /** Status follows the step results: any fail → Failed, else any blocked → Blocked, all done → Passed. */
    private void refreshStatus(Execution e) {
        Collection<StepResult> rs = results(e).values();
        int steps = e.getScenario().getSteps().size();
        ExecStatus s;
        if (rs.stream().anyMatch(r -> r.getResult() == StepStatus.FAIL)) s = ExecStatus.FAILED;
        else if (rs.stream().anyMatch(r -> r.getResult() == StepStatus.BLOCKED)) s = ExecStatus.BLOCKED;
        else if (rs.size() >= steps && steps > 0) s = ExecStatus.PASSED;
        else if (e.isRetest()) s = ExecStatus.RETEST;
        else s = rs.isEmpty() ? ExecStatus.NOT_STARTED : ExecStatus.IN_PROGRESS;
        e.setStatus(s);
        executions.save(e);
    }

    /**
     * Finishing a re-test settles the issues being verified: if the step where an issue was found now
     * passes, the issue is closed as verified; if it fails again, the issue is reopened.
     */
    @Transactional
    public String finish(Execution e, AppUser by) {
        if (!canRecord(e, by)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Map<Long, StepResult> rs = results(e);
        boolean stopped = rs.values().stream().anyMatch(r -> r.getResult() == StepStatus.FAIL || r.getResult() == StepStatus.BLOCKED);
        if (!stopped && rs.size() < e.getScenario().getSteps().size()) {
            throw new IllegalArgumentException("Record a result for every step before finishing");
        }
        e.setFinishedAt(Instant.now());
        refreshStatus(e);

        int verified = 0, reopened = 0;
        if (e.isRetest()) {
            for (IssueLink l : links.findByExecution(e)) {
                Feedback f = l.getFeedback();
                if (f.getFixedInBuild() == null || f.getStage() == Stage.CLOSED) continue;
                StepStatus at = stepResultAt(e, rs, l.getStepNo());
                if (at == StepStatus.PASS) {
                    f.setStage(Stage.CLOSED);
                    feedbackRepo.save(f);
                    feedbackService.log(f, by, "Verified fixed by re-test",
                            e.getScenario().getCode() + " in " + f.getFixedInBuild());
                    verified++;
                } else if (at == StepStatus.FAIL || at == StepStatus.BLOCKED) {
                    String build = f.getFixedInBuild();
                    f.setFixedInBuild(null);
                    feedbackRepo.save(f);
                    feedbackService.log(f, by, "Re-test failed, issue reopened", e.getScenario().getCode() + " in " + build);
                    reopened++;
                }
            }
        }
        StringBuilder msg = new StringBuilder(e.getScenario().getCode() + " finished: " + e.getStatus().getLabel() + ".");
        if (verified > 0) msg.append(" ").append(verified).append(" issue(s) verified and closed.");
        if (reopened > 0) msg.append(" ").append(reopened).append(" issue(s) reopened.");
        return msg.toString();
    }

    private StepStatus stepResultAt(Execution e, Map<Long, StepResult> rs, Integer stepNo) {
        if (stepNo == null) return null;
        for (ScenarioStep s : e.getScenario().getSteps()) {
            if (s.getStepNo() == stepNo) {
                StepResult r = rs.get(s.getId());
                return r == null ? null : r.getResult();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- issues from steps

    /** Creates a feedback item pre-filled from the scenario and failed step, and links them. */
    @Transactional
    public Feedback raise(Execution e, ScenarioStep step, AppUser by, String title, Severity severity,
                          List<MultipartFile> files) {
        if (!canRecord(e, by)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        StepResult r = results(e).get(step.getId());
        if (r == null || (r.getResult() != StepStatus.FAIL && r.getResult() != StepStatus.BLOCKED)) {
            throw new IllegalArgumentException("Mark the step as Fail or Blocked before raising an issue");
        }
        Scenario s = e.getScenario();
        String desc = "Found while running " + s.getCode() + " \"" + s.getTitle() + "\", step " + step.getStepNo()
                + ": " + step.getAction()
                + (s.getPreconditions() == null ? "" : "\n\nPreconditions: " + s.getPreconditions())
                + (s.getTestData() == null ? "" : "\nTest data: " + s.getTestData());
        FeedbackForm form = new FeedbackForm(
                blankToNull(title) == null ? s.getTitle() + ": step " + step.getStepNo() + " failed" : title.trim(),
                cut(desc, 4000), step.getExpected(), r.getActual(), s.getModule(), null,
                s.getLob(), s.getDivision(), FeedbackType.BUG, severity == null ? Severity.MEDIUM : severity);
        Feedback f = feedbackService.create(e.getRun().getProject(), e.getRun().getUatCycle(), by, form, files);
        storage.copyToFeedback(storage.list(r), f);
        f.setFoundInBuild(e.getRun().getBuild());
        feedbackRepo.save(f);
        links.save(new IssueLink(f, e, step.getStepNo()));
        feedbackService.log(f, by, "Raised from test run",
                s.getCode() + " step " + step.getStepNo() + (e.getRun().getBuild() == null ? "" : ", " + e.getRun().getBuild()));
        return f;
    }

    /** Links an issue that is already logged instead of raising a duplicate. */
    @Transactional
    public Feedback linkExisting(Execution e, ScenarioStep step, AppUser by, String code) {
        if (!canRecord(e, by)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Long id = FeedbackService.parseCode(code);
        Feedback f = id == null ? null : feedbackRepo.findById(id).orElse(null);
        if (f == null || !f.getProject().getId().equals(e.getRun().getProject().getId())) {
            throw new IllegalArgumentException("No item " + (code == null ? "" : code.trim()) + " in this project");
        }
        if (!links.existsByFeedbackAndExecution(f, e)) {
            links.save(new IssueLink(f, e, step.getStepNo()));
            feedbackService.log(f, by, "Also seen in test run", e.getScenario().getCode() + " step " + step.getStepNo());
        }
        return f;
    }

    public List<IssueLink> links(Execution e) {
        return links.findByExecution(e);
    }

    public List<IssueLink> links(Feedback f) {
        return links.findByFeedback(f);
    }

    // ---------------------------------------------------------------- builds and re-test

    /** Open issues found in this run that are not yet marked fixed: candidates for a new build. */
    public List<Feedback> fixCandidates(TestRun run) {
        return links.findByExecutionRun(run).stream()
                .map(IssueLink::getFeedback)
                .filter(f -> f.getStage() != Stage.CLOSED && f.getFixedInBuild() == null)
                .distinct()
                .sorted(Comparator.comparing(Feedback::getId))
                .toList();
    }

    /** Records a new build on the run; scenarios linked to the issues it fixes go back for re-test. */
    @Transactional
    public int deployBuild(TestRun run, AppUser by, String build, List<Long> fixedIds) {
        if (build == null || build.isBlank()) throw new IllegalArgumentException("Enter the build or version that was deployed");
        String b = build.trim();
        run.setBuild(b);
        runs.save(run);
        Set<Long> chosen = fixedIds == null ? Set.of() : new HashSet<>(fixedIds);
        Set<Execution> retest = new LinkedHashSet<>();
        for (IssueLink l : links.findByExecutionRun(run)) {
            Feedback f = l.getFeedback();
            if (!chosen.contains(f.getId()) || f.getStage() == Stage.CLOSED) continue;
            if (!b.equals(f.getFixedInBuild())) {
                f.setFixedInBuild(b);
                feedbackRepo.save(f);
                feedbackService.log(f, by, "Marked fixed in build", b);
            }
            retest.add(l.getExecution());
        }
        for (Execution e : retest) {
            e.setAttempt(e.getAttempt() + 1);
            e.setStatus(ExecStatus.RETEST);
            e.setRetestBuild(b);
            e.setFinishedAt(null);
            executions.save(e);
        }
        return retest.size();
    }

    // ---------------------------------------------------------------- board and sign-off

    public Totals totals(Collection<Execution> list) {
        long p = 0, f = 0, b = 0, r = 0, n = 0;
        for (Execution e : list) {
            switch (e.getStatus()) {
                case PASSED -> p++;
                case FAILED -> f++;
                case BLOCKED -> b++;
                case RETEST -> r++;
                default -> n++;
            }
        }
        return new Totals(p, f, b, r, n, list.size());
    }

    /**
     * The latest result of each scenario across all runs of the cycle: the newest run in which it was
     * started, otherwise the newest run it is in.
     */
    public List<Execution> latestPerScenario(UatCycle cycle) {
        Map<Long, Execution> best = new LinkedHashMap<>();
        List<Execution> all = new ArrayList<>(executions.findByRunUatCycle(cycle));
        all.sort(Comparator.comparing((Execution e) -> -e.getRun().getId()));
        for (Execution e : all) {
            Execution cur = best.get(e.getScenario().getId());
            if (cur == null || (cur.getStatus() == ExecStatus.NOT_STARTED && e.getStatus() != ExecStatus.NOT_STARTED)) {
                best.put(e.getScenario().getId(), e);
            }
        }
        return new ArrayList<>(best.values());
    }

    public List<LobRow> lobRows(UatCycle cycle) {
        List<Execution> latest = latestPerScenario(cycle);
        Map<String, List<Execution>> byLob = latest.stream().collect(Collectors.groupingBy(
                e -> lobOf(e), LinkedHashMap::new, Collectors.toList()));
        Map<String, CycleSignOff> signed = new HashMap<>();
        for (CycleSignOff s : signOffs.findByUatCycle(cycle)) signed.put(s.getLob(), s);
        List<String> order = new ArrayList<>(props.lobs());
        for (String l : byLob.keySet()) if (!order.contains(l)) order.add(l);
        List<LobRow> rows = new ArrayList<>();
        for (String lob : order) {
            List<Execution> list = byLob.get(lob);
            if (list == null) continue;
            rows.add(new LobRow(lob, totals(list), signed.get(lob), openIssues(cycle, lob).size()));
        }
        return rows;
    }

    public List<TesterRow> testerRows(TestRun run) {
        Map<AppUser, List<Execution>> by = new LinkedHashMap<>();
        for (Execution e : executions.findByRun(run)) {
            if (e.getAssignee() != null) by.computeIfAbsent(e.getAssignee(), k -> new ArrayList<>()).add(e);
        }
        return by.entrySet().stream()
                .map(en -> new TesterRow(en.getKey(), en.getValue().size(),
                        en.getValue().stream().filter(x -> x.getStatus().isDone()).count()))
                .sorted(Comparator.comparing(t -> t.person().getName()))
                .toList();
    }

    public List<IssueRow> issueRows(TestRun run) {
        return issueRows(links.findByExecutionRun(run));
    }

    public List<IssueRow> issueRows(UatCycle cycle) {
        return issueRows(links.findByExecutionRunUatCycle(cycle));
    }

    private List<IssueRow> issueRows(List<IssueLink> source) {
        Map<Feedback, List<IssueLink>> byIssue = new LinkedHashMap<>();
        for (IssueLink l : source) byIssue.computeIfAbsent(l.getFeedback(), k -> new ArrayList<>()).add(l);
        return byIssue.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Feedback, List<IssueLink>> en) -> en.getKey().getStage() == Stage.CLOSED)
                        .thenComparing(en -> -en.getKey().getId()))
                .map(en -> new IssueRow(en.getKey(), en.getValue().stream()
                        .map(l -> l.getExecution().getScenario().getCode() + (l.getStepNo() == null ? "" : " step " + l.getStepNo())
                                + " · " + l.getExecution().getRun().getName())
                        .distinct().collect(Collectors.joining(", "))))
                .toList();
    }

    /** Open issues raised from any run of the cycle against scenarios of this LOB. */
    private List<Feedback> openIssues(UatCycle cycle, String lob) {
        List<Feedback> out = new ArrayList<>();
        for (IssueLink l : links.findByExecutionRunUatCycle(cycle)) {
            if (!lob.equals(lobOf(l.getExecution()))) continue;
            if (l.getFeedback().getStage() != Stage.CLOSED && !out.contains(l.getFeedback())) out.add(l.getFeedback());
        }
        return out;
    }

    private static String lobOf(Execution e) {
        return e.getScenario().getLob() == null ? "No LOB" : e.getScenario().getLob();
    }

    /** Business sign-off of one LOB for the whole cycle. Open issues are recorded as accepted exceptions. */
    @Transactional
    public CycleSignOff signOff(UatCycle cycle, AppUser by, String lob, String note) {
        if (!cycle.isOpen()) throw new IllegalArgumentException(cycle.getName() + " is closed");
        if (lob == null || lob.isBlank()) throw new IllegalArgumentException("Choose a line of business");
        List<Execution> list = latestPerScenario(cycle).stream().filter(e -> lob.equals(lobOf(e))).toList();
        if (list.isEmpty()) throw new IllegalArgumentException("No scenarios for " + lob + " in this cycle");
        Totals t = totals(list);
        List<Feedback> open = openIssues(cycle, lob);
        boolean noteMissing = note == null || note.isBlank();
        if (t.notRun() + t.retest() > 0 && noteMissing) {
            throw new IllegalArgumentException("Some " + lob + " scenarios are not finished. Add a note explaining the sign-off.");
        }
        if (!open.isEmpty() && noteMissing) {
            throw new IllegalArgumentException("There are open issues for " + lob + ". Add a note to accept them as known exceptions.");
        }
        CycleSignOff s = signOffs.findByUatCycleAndLob(cycle, lob).orElseGet(CycleSignOff::new);
        s.setUatCycle(cycle);
        s.setLob(lob);
        s.setSignedBy(by);
        s.setNote(blankToNull(note));
        s.setAcceptedIssues(open.isEmpty() ? null : open.stream().map(Feedback::getCode).collect(Collectors.joining(", ")));
        return signOffs.save(s);
    }

    // ---------------------------------------------------------------- export

    public byte[] export(TestRun run) throws IOException {
        return export(executions.findByRun(run), "Run");
    }

    /** One row per scenario with its latest result in the cycle. */
    public byte[] export(UatCycle cycle) throws IOException {
        return export(latestPerScenario(cycle), "Cycle");
    }

    private byte[] export(List<Execution> source, String sheetName) throws IOException {
        String[] head = {"Scenario", "Title", "LOB", "Division", "Priority", "Run", "Build", "Assigned to", "Status", "Attempt",
                "Steps passed", "Steps failed", "Steps blocked", "Steps", "Issues", "Finished"};
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            Row h = sheet.createRow(0);
            for (int i = 0; i < head.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(head[i]);
                c.setCellStyle(bold);
            }
            List<Execution> list = new ArrayList<>(source);
            list.sort(Comparator.comparing(e -> e.getScenario().getCode()));
            int r = 1;
            for (Execution e : list) {
                Collection<StepResult> rs = results(e).values();
                Scenario s = e.getScenario();
                String issues = links.findByExecution(e).stream().map(l -> l.getFeedback().getCode()).distinct()
                        .collect(Collectors.joining(", "));
                Object[] v = {s.getCode(), s.getTitle(), s.getLob(), s.getDivision(), s.getPriority().getLabel(),
                        e.getRun().getName(), e.getRun().getBuild(),
                        e.getAssignee() == null ? "" : e.getAssignee().getName(), e.getStatus().getLabel(), e.getAttempt(),
                        rs.stream().filter(x -> x.getResult() == StepStatus.PASS).count(),
                        rs.stream().filter(x -> x.getResult() == StepStatus.FAIL).count(),
                        rs.stream().filter(x -> x.getResult() == StepStatus.BLOCKED).count(),
                        s.getSteps().size(), issues, e.getFinishedAt() == null ? "" : e.getFinishedAt().toString()};
                Row row = sheet.createRow(r++);
                for (int i = 0; i < v.length; i++) {
                    Cell c = row.createCell(i);
                    if (v[i] instanceof Number n) c.setCellValue(n.doubleValue());
                    else c.setCellValue(v[i] == null ? "" : String.valueOf(v[i]));
                }
            }
            sheet.createFreezePane(0, 1);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
