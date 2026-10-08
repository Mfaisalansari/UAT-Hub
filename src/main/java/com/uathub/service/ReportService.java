package com.uathub.service;

import com.uathub.domain.*;
import com.uathub.repo.CycleSignOffRepository;
import com.uathub.repo.FeedbackRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** The UAT exit report of a cycle: one set of figures behind both the printable page and the Excel pack. */
@Service
public class ReportService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    public record Count(String label, long value) {}

    public record SevRow(String type, long critical, long high, long medium, long low, long total) {}

    public record LobLine(String lob, TestingService.Totals totals, Integer passRate, long openIssues, CycleSignOff signOff) {}

    public record CycleLine(UatCycle cycle, long scenarios, long executed, Integer passRate, long feedback, long bugs,
                            long openHighCritical, long signedLobs, long lobs) {}

    public record Report(Project project, UatCycle cycle, TestingService.Totals totals, Integer passRate,
                         List<LobLine> lobs, List<TestingService.RunCard> runs, List<SevRow> bySeverity,
                         List<Count> outcomes, long feedbackTotal, List<Feedback> openItems, List<CycleSignOff> exceptions,
                         long signedLobs, List<CycleLine> comparison, Instant generatedAt, String generatedBy) {

        public String verdict() {
            if (lobs.isEmpty()) return "No scenarios were run in this cycle yet.";
            if (signedLobs == lobs.size()) return "All " + lobs.size() + " lines of business are signed off.";
            return signedLobs + " of " + lobs.size() + " lines of business signed off.";
        }

        public boolean complete() {
            return !lobs.isEmpty() && signedLobs == lobs.size();
        }

        public String generated() {
            return WHEN.format(generatedAt);
        }
    }

    private static final List<Severity> SEVERITIES = List.of(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW);

    private final TestingService testing;
    private final FeedbackRepository feedback;
    private final CycleSignOffRepository signOffs;
    private final CycleService cycles;

    public ReportService(TestingService testing, FeedbackRepository feedback, CycleSignOffRepository signOffs, CycleService cycles) {
        this.testing = testing;
        this.feedback = feedback;
        this.signOffs = signOffs;
        this.cycles = cycles;
    }

    public static Integer passRate(TestingService.Totals t) {
        long executed = t.passed() + t.failed() + t.blocked();
        return executed == 0 ? null : (int) Math.round(t.passed() * 100.0 / executed);
    }

    public Report build(UatCycle cycle, AppUser by) {
        Project project = cycle.getProject();
        List<Execution> latest = testing.latestPerScenario(cycle);
        TestingService.Totals totals = testing.totals(latest);

        List<LobLine> lobs = new ArrayList<>();
        for (TestingService.LobRow r : testing.lobRows(cycle)) {
            lobs.add(new LobLine(r.lob(), r.totals(), passRate(r.totals()), r.openIssues(), r.signOff()));
        }
        long signed = lobs.stream().filter(l -> l.signOff() != null).count();

        List<Feedback> items = feedback.findByProjectAndUatCycleOrderByIdDesc(project, cycle);
        List<SevRow> bySeverity = new ArrayList<>();
        for (FeedbackType type : FeedbackType.values()) {
            long[] n = new long[4];
            for (Feedback f : items) if (f.getType() == type) n[SEVERITIES.indexOf(f.getSeverity())]++;
            long total = n[0] + n[1] + n[2] + n[3];
            if (total > 0) bySeverity.add(new SevRow(type.getLabel(), n[0], n[1], n[2], n[3], total));
        }

        List<Count> outcomes = new ArrayList<>();
        count(outcomes, "Waiting for triage", items.stream().filter(f -> f.getStage().isOpenForTriage()).count());
        count(outcomes, "Waiting for a business decision", items.stream().filter(f -> f.getStage() == Stage.BUSINESS_REVIEW).count());
        for (Decision d : Decision.values()) count(outcomes, "Decided: " + d.getLabel(), items.stream().filter(f -> f.getDecision() == d).count());
        count(outcomes, "Closed as duplicate", items.stream().filter(f -> f.getDuplicateOf() != null).count());
        count(outcomes, "In Jira", items.stream().filter(f -> f.getJiraKey() != null).count());

        List<Feedback> open = items.stream().filter(f -> f.getStage() != Stage.CLOSED)
                .sorted(Comparator.comparing((Feedback f) -> SEVERITIES.indexOf(f.getSeverity())).thenComparing(Feedback::getId))
                .toList();

        List<CycleSignOff> exceptions = signOffs.findByUatCycle(cycle).stream()
                .filter(s -> s.getAcceptedIssues() != null).toList();

        return new Report(project, cycle, totals, passRate(totals), lobs, testing.runCards(cycle), bySeverity, outcomes,
                items.size(), open, exceptions, signed, comparison(project), Instant.now(), by == null ? null : by.getName());
    }

    /** Every cycle of the project side by side, oldest first. */
    public List<CycleLine> comparison(Project project) {
        List<UatCycle> all = new ArrayList<>(cycles.cycles(project));
        Collections.reverse(all);
        List<CycleLine> out = new ArrayList<>();
        for (UatCycle c : all) {
            TestingService.Totals t = testing.totals(testing.latestPerScenario(c));
            List<Feedback> items = feedback.findByProjectAndUatCycleOrderByIdDesc(project, c);
            List<TestingService.LobRow> lobRows = testing.lobRows(c);
            out.add(new CycleLine(c, t.total(), t.passed() + t.failed() + t.blocked(), passRate(t), items.size(),
                    items.stream().filter(f -> f.getType() == FeedbackType.BUG).count(),
                    items.stream().filter(f -> f.getStage() != Stage.CLOSED
                            && (f.getSeverity() == Severity.CRITICAL || f.getSeverity() == Severity.HIGH)).count(),
                    lobRows.stream().filter(l -> l.signOff() != null).count(), lobRows.size()));
        }
        return out;
    }

    private static void count(List<Count> out, String label, long n) {
        if (n > 0) out.add(new Count(label, n));
    }

    // ------------------------------------------------------------------ Excel

    public byte[] excel(Report r) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            CellStyle title = wb.createCellStyle();
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title.setFont(big);

            Sheet s = wb.createSheet("Summary");
            int row = 0;
            cell(s, row++, 0, "UAT exit report: " + r.cycle().getName(), title);
            row++;
            String[][] facts = {
                    {"Project", r.project().getName()},
                    {"UAT cycle", r.cycle().getName()},
                    {"Release", nz(r.cycle().getReleaseName())},
                    {"Dates", nz(r.cycle().getDates())},
                    {"Status", r.cycle().isOpen() ? "Open" : "Closed"},
                    {"Sign-off", r.verdict()},
                    {"Generated", r.generated() + (r.generatedBy() == null ? "" : " by " + r.generatedBy())},
            };
            for (String[] f : facts) { cell(s, row, 0, f[0], head); cell(s, row++, 1, f[1], null); }
            row++;
            cell(s, row++, 0, "Scenarios (latest result across all runs)", head);
            Object[][] nums = {
                    {"In scope", r.totals().total()}, {"Passed", r.totals().passed()}, {"Failed", r.totals().failed()},
                    {"Blocked", r.totals().blocked()}, {"Waiting for re-test", r.totals().retest()}, {"Not run", r.totals().notRun()},
                    {"Pass rate of executed", r.passRate() == null ? "-" : r.passRate() + "%"},
                    {"Runs", r.runs().size()}, {"Feedback items", r.feedbackTotal()}, {"Still open", r.openItems().size()},
            };
            for (Object[] n : nums) { cell(s, row, 0, String.valueOf(n[0]), null); val(s, row++, 1, n[1]); }
            s.setColumnWidth(0, 40 * 256);
            s.setColumnWidth(1, 50 * 256);

            table(wb, head, "By LOB", new String[]{"Line of business", "Scenarios", "Passed", "Failed", "Blocked", "Re-test", "Not run",
                    "Pass rate %", "Open issues", "Signed off by", "Signed off", "Note", "Accepted open issues"},
                    r.lobs().stream().map(l -> new Object[]{l.lob(), l.totals().total(), l.totals().passed(), l.totals().failed(),
                            l.totals().blocked(), l.totals().retest(), l.totals().notRun(), l.passRate() == null ? "-" : l.passRate(),
                            l.openIssues(), l.signOff() == null || l.signOff().getSignedBy() == null ? "" : l.signOff().getSignedBy().getName(),
                            l.signOff() == null ? "" : WHEN.format(l.signOff().getSignedAt()),
                            l.signOff() == null ? "" : nz(l.signOff().getNote()),
                            l.signOff() == null ? "" : nz(l.signOff().getAcceptedIssues())}).toList());

            table(wb, head, "Feedback by severity", new String[]{"Type", "Critical", "High", "Medium", "Low", "Total"},
                    r.bySeverity().stream().map(x -> new Object[]{x.type(), x.critical(), x.high(), x.medium(), x.low(), x.total()}).toList());

            List<Object[]> outcomeRows = new ArrayList<>();
            for (Count c : r.outcomes()) outcomeRows.add(new Object[]{c.label(), c.value()});
            table(wb, head, "Outcomes", new String[]{"Outcome", "Items"}, outcomeRows);

            table(wb, head, "Open items", new String[]{"ID", "Title", "LOB", "Division", "Type", "Severity", "Stage", "Decision", "Jira", "Raised by"},
                    r.openItems().stream().map(f -> new Object[]{f.getCode(), f.getTitle(), nz(f.getLob()), nz(f.getDivision()),
                            f.getType().getLabel(), f.getSeverity().getLabel(), f.getStage().getLabel(),
                            f.getDecision() == null ? "" : f.getDecision().getLabel(), nz(f.getJiraKey()),
                            f.getRaisedBy() == null ? "" : f.getRaisedBy().getName()}).toList());

            table(wb, head, "Runs", new String[]{"Run", "Build", "Status", "Scenarios", "Passed", "Failed", "Blocked", "Not run", "% done"},
                    r.runs().stream().map(c -> new Object[]{c.run().getName(), nz(c.run().getBuild()), c.run().isActive() ? "Open" : "Closed",
                            c.totals().total(), c.totals().passed(), c.totals().failed(), c.totals().blocked(),
                            c.totals().notRun() + c.totals().retest(), c.totals().percentDone()}).toList());

            table(wb, head, "Compared with other cycles", new String[]{"UAT cycle", "Scenarios", "Executed", "Pass rate %",
                            "Feedback", "Bugs", "Open critical/high", "LOBs signed off"},
                    r.comparison().stream().map(c -> new Object[]{c.cycle().getName(), c.scenarios(), c.executed(),
                            c.passRate() == null ? "-" : c.passRate(), c.feedback(), c.bugs(), c.openHighCritical(),
                            c.signedLobs() + " of " + c.lobs()}).toList());

            testing.scenarioSheet(wb, testing.latestPerScenario(r.cycle()), "Scenarios");
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void table(Workbook wb, CellStyle head, String name, String[] headers, List<Object[]> rows) {
        Sheet s = wb.createSheet(name);
        for (int i = 0; i < headers.length; i++) cell(s, 0, i, headers[i], head);
        int r = 1;
        for (Object[] row : rows) {
            for (int i = 0; i < row.length; i++) val(s, r, i, row[i]);
            r++;
        }
        s.createFreezePane(0, 1);
        for (int i = 0; i < headers.length; i++) s.setColumnWidth(i, Math.min(60, Math.max(12, headers[i].length() + 4)) * 256);
        if (headers.length > 1 && (headers[1].equals("Title"))) s.setColumnWidth(1, 60 * 256);
    }

    private static void cell(Sheet s, int r, int c, String v, CellStyle style) {
        Row row = s.getRow(r) == null ? s.createRow(r) : s.getRow(r);
        Cell cell = row.createCell(c);
        cell.setCellValue(v);
        if (style != null) cell.setCellStyle(style);
    }

    private static void val(Sheet s, int r, int c, Object v) {
        Row row = s.getRow(r) == null ? s.createRow(r) : s.getRow(r);
        Cell cell = row.createCell(c);
        if (v instanceof Number n) cell.setCellValue(n.doubleValue());
        else cell.setCellValue(v == null ? "" : String.valueOf(v));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
