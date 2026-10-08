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

    public record LobRow(String lob, Totals totals, LobSignOff signOff, long openIssues) {}

    public record IssueRow(Feedback feedback, String impact) {}

    public record TesterRow(AppUser person, long total, long done) {}

    private static final List<ExecStatus> QUEUE_ORDER = List.of(ExecStatus.RETEST, ExecStatus.IN_PROGRESS,
            ExecStatus.NOT_STARTED, ExecStatus.BLOCKED, ExecStatus.FAILED, ExecStatus.PASSED);

    private final TestRunRepository runs;
    private final ExecutionRepository executions;
    private final StepResultRepository results;
    private final IssueLinkRepository links;
    private final LobSignOffRepository signOffs;
    private final ScenarioRepository scenarios;
    private final AppUserRepository users;
    private final FeedbackRepository feedbackRepo;
    private final FeedbackService feedbackService;
    private final AttachmentStorage storage;
    private final UatHubProperties props;

    public TestingService(TestRunRepository runs, ExecutionRepository executions, StepResultRepository results,
                          IssueLinkRepository links, LobSignOffRepository signOffs, ScenarioRepository scenarios,
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

    public Optional<TestRun> currentRun(Project project) {
        return runs.findFirstByProjectAndActiveTrueOrderByIdDesc(project);
    }

    public List<TestRun> runs(Project project) {
        return runs.findByProjectOrderByIdDesc(project);
    }

    public TestRun loadRun(Long id, Project project) {
        TestRun r = runs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!r.getProject().getId().equals(project.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return r;
    }

    /** Starts a new run; it becomes the current one and earlier runs are closed. */
    @Transactional
    public TestRun createRun(Project project, AppUser by, String name, String build) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name the test run, e.g. UAT cycle 3");
        for (TestRun old : runs.findByProjectOrderByIdDesc(project)) {
            if (old.isActive()) {
                old.setActive(false);
                runs.save(old);
            }
        }
        TestRun r = new TestRun();
        r.setProject(project);
        r.setName(name.trim());
        r.setBuild(blankToNull(build));
        r.setCreatedBy(by);
        return runs.save(r);
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

    public List<Execution> myQueue(TestRun run, AppUser me) {
        List<Execution> list = new ArrayList<>(executions.findByRunAndAssignee(run, me));
        list.sort(Comparator.comparingInt((Execution e) -> QUEUE_ORDER.indexOf(e.getStatus()))
                .thenComparing(e -> e.getScenario().getCode()));
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
        return e.getRun().isActive() && (mine || me.getRole() == Role.ADMIN);
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
        Feedback f = feedbackService.create(e.getRun().getProject(), by, form, files);
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

    public List<LobRow> lobRows(TestRun run) {
        List<Execution> all = executions.findByRun(run);
        Map<String, List<Execution>> byLob = all.stream().collect(Collectors.groupingBy(
                e -> e.getScenario().getLob() == null ? "No LOB" : e.getScenario().getLob(), LinkedHashMap::new, Collectors.toList()));
        Map<String, LobSignOff> signed = new HashMap<>();
        for (LobSignOff s : signOffs.findByRun(run)) signed.put(s.getLob(), s);

        List<String> order = new ArrayList<>(props.lobs());
        for (String l : byLob.keySet()) if (!order.contains(l)) order.add(l);
        List<LobRow> rows = new ArrayList<>();
        for (String lob : order) {
            List<Execution> list = byLob.get(lob);
            if (list == null) continue;
            rows.add(new LobRow(lob, totals(list), signed.get(lob), openIssues(list).size()));
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
        Map<Feedback, List<IssueLink>> byIssue = new LinkedHashMap<>();
        for (IssueLink l : links.findByExecutionRun(run)) byIssue.computeIfAbsent(l.getFeedback(), k -> new ArrayList<>()).add(l);
        return byIssue.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Feedback, List<IssueLink>> en) -> en.getKey().getStage() == Stage.CLOSED)
                        .thenComparing(en -> -en.getKey().getId()))
                .map(en -> new IssueRow(en.getKey(), en.getValue().stream()
                        .map(l -> l.getExecution().getScenario().getCode() + (l.getStepNo() == null ? "" : " step " + l.getStepNo()))
                        .distinct().collect(Collectors.joining(", "))))
                .toList();
    }

    private List<Feedback> openIssues(List<Execution> list) {
        List<Feedback> out = new ArrayList<>();
        for (Execution e : list) {
            for (IssueLink l : links.findByExecution(e)) {
                if (l.getFeedback().getStage() != Stage.CLOSED && !out.contains(l.getFeedback())) out.add(l.getFeedback());
            }
        }
        return out;
    }

    /** Business sign-off for one LOB. Open issues at that moment are recorded as accepted exceptions. */
    @Transactional
    public LobSignOff signOff(TestRun run, AppUser by, String lob, String note) {
        if (lob == null || lob.isBlank()) throw new IllegalArgumentException("Choose a line of business");
        List<Execution> list = executions.findByRun(run).stream()
                .filter(e -> lob.equals(e.getScenario().getLob() == null ? "No LOB" : e.getScenario().getLob()))
                .toList();
        if (list.isEmpty()) throw new IllegalArgumentException("No scenarios for " + lob + " in this run");
        Totals t = totals(list);
        List<Feedback> open = openIssues(list);
        if (t.notRun() + t.retest() > 0 && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("Some " + lob + " scenarios are not finished. Add a note explaining the sign-off.");
        }
        if (!open.isEmpty() && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("There are open issues for " + lob + ". Add a note to accept them as known exceptions.");
        }
        LobSignOff s = signOffs.findByRunAndLob(run, lob).orElseGet(LobSignOff::new);
        s.setRun(run);
        s.setLob(lob);
        s.setSignedBy(by);
        s.setNote(blankToNull(note));
        s.setAcceptedIssues(open.isEmpty() ? null : open.stream().map(Feedback::getCode).collect(Collectors.joining(", ")));
        return signOffs.save(s);
    }

    // ---------------------------------------------------------------- export

    public byte[] export(TestRun run) throws IOException {
        String[] head = {"Scenario", "Title", "LOB", "Division", "Priority", "Assigned to", "Status", "Attempt",
                "Steps passed", "Steps failed", "Steps blocked", "Steps", "Issues", "Finished"};
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Test run");
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
            List<Execution> list = new ArrayList<>(executions.findByRun(run));
            list.sort(Comparator.comparing(e -> e.getScenario().getCode()));
            int r = 1;
            for (Execution e : list) {
                Collection<StepResult> rs = results(e).values();
                Scenario s = e.getScenario();
                String issues = links.findByExecution(e).stream().map(l -> l.getFeedback().getCode()).distinct()
                        .collect(Collectors.joining(", "));
                Object[] v = {s.getCode(), s.getTitle(), s.getLob(), s.getDivision(), s.getPriority().getLabel(),
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
