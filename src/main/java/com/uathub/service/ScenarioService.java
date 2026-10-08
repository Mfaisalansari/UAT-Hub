package com.uathub.service;

import com.uathub.domain.Priority;
import com.uathub.domain.Project;
import com.uathub.domain.Scenario;
import com.uathub.domain.ScenarioStep;
import com.uathub.repo.ScenarioRepository;
import com.uathub.repo.StepResultRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Service
public class ScenarioService {

    /** Everything the scenario editor posts. */
    public record ScenarioInput(String code, String title, String lob, String division, String module,
                                Priority priority, String preconditions, String testData,
                                List<String[]> steps) {}

    public record ImportResult(int created, int updated) {}

    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        put("code", "scenario id", "scenario", "id", "test id", "tc id", "scenario no");
        put("title", "title", "scenario title", "scenario name", "summary", "name");
        put("lob", "lob", "line of business");
        put("division", "division", "region");
        put("module", "module", "case type", "case type / stage", "area", "feature");
        put("priority", "priority", "severity");
        put("preconditions", "preconditions", "pre-conditions", "precondition", "pre-requisites", "prerequisites");
        put("testData", "test data", "data");
        put("stepNo", "step", "step no", "step #", "step number");
        put("action", "action", "step description", "test step", "steps", "description");
        put("expected", "expected", "expected result", "expected outcome");
    }

    private static void put(String field, String... names) {
        for (String n : names) ALIASES.put(n, field);
    }

    private final ScenarioRepository repo;
    private final StepResultRepository stepResults;

    public ScenarioService(ScenarioRepository repo, StepResultRepository stepResults) {
        this.repo = repo;
        this.stepResults = stepResults;
    }

    private boolean removable(ScenarioStep step) {
        return step.getId() == null || !stepResults.existsByStep(step);
    }

    public Scenario load(Long id, Project project) {
        Scenario s = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!s.getProject().getId().equals(project.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return s;
    }

    @Transactional
    public Scenario save(Project project, Long id, ScenarioInput in) {
        if (in.title() == null || in.title().isBlank()) throw new IllegalArgumentException("Add a scenario title");
        if (in.steps() == null || in.steps().isEmpty()) throw new IllegalArgumentException("Add at least one step");
        Scenario s = id == null ? new Scenario() : load(id, project);
        s.setProject(project);
        String code = blank(in.code()) ? (s.getCode() != null ? s.getCode() : nextCode(project)) : in.code().trim().toUpperCase();
        repo.findByProjectAndCodeIgnoreCase(project, code)
                .filter(other -> !other.getId().equals(s.getId()))
                .ifPresent(other -> { throw new IllegalArgumentException(code + " is already used by another scenario"); });
        s.setCode(code);
        apply(s, in);
        s.replaceSteps(in.steps(), this::removable);
        return repo.save(s);
    }

    @Transactional
    public void setActive(Scenario s, boolean active) {
        s.setActive(active);
        repo.save(s);
    }

    /**
     * Reads the first sheet. One row per step; rows with the same Scenario ID (or the same title when
     * there is no ID column) make one scenario. Scenario-level columns are taken from its first row.
     * An existing scenario with the same ID is updated and its steps replaced.
     */
    @Transactional
    public ImportResult importSheet(Project project, InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw new IllegalArgumentException("The sheet is empty");
            Map<String, Integer> cols = new HashMap<>();
            for (Cell c : header) {
                String field = ALIASES.get(fmt.formatCellValue(c).trim().toLowerCase());
                if (field != null) cols.putIfAbsent(field, c.getColumnIndex());
            }
            if (!cols.containsKey("title") && !cols.containsKey("code")) {
                throw new IllegalArgumentException("No Scenario ID or Title column found.");
            }
            if (!cols.containsKey("action")) {
                throw new IllegalArgumentException("No Action column found. Name the step column Action or Test step.");
            }

            LinkedHashMap<String, List<Row>> groups = new LinkedHashMap<>();
            String lastKey = null;
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String key = get(row, cols, "code", fmt);
                if (key == null) key = get(row, cols, "title", fmt);
                if (key == null) key = lastKey; // merged cells: continuation rows leave the ID blank
                if (key == null || get(row, cols, "action", fmt) == null) continue;
                groups.computeIfAbsent(key.trim(), k -> new ArrayList<>()).add(row);
                lastKey = key;
            }

            int created = 0, updated = 0;
            for (Map.Entry<String, List<Row>> g : groups.entrySet()) {
                Row first = g.getValue().get(0);
                String code = get(first, cols, "code", fmt);
                String title = get(first, cols, "title", fmt);
                if (title == null) title = code;
                List<Row> rows = new ArrayList<>(g.getValue());
                if (cols.containsKey("stepNo")) {
                    rows.sort(Comparator.comparingDouble(r -> number(get(r, cols, "stepNo", fmt))));
                }
                List<String[]> steps = new ArrayList<>();
                for (Row r : rows) steps.add(new String[]{cut(get(r, cols, "action", fmt), 1000), cut(get(r, cols, "expected", fmt), 1000)});

                Optional<Scenario> existing = code == null ? Optional.empty()
                        : repo.findByProjectAndCodeIgnoreCase(project, code.trim());
                Scenario s = existing.orElseGet(Scenario::new);
                s.setProject(project);
                s.setCode(code == null ? nextCode(project) : code.trim().toUpperCase());
                apply(s, new ScenarioInput(null, cut(title, 300), get(first, cols, "lob", fmt), get(first, cols, "division", fmt),
                        get(first, cols, "module", fmt), Priority.parse(get(first, cols, "priority", fmt), Priority.MEDIUM),
                        cut(get(first, cols, "preconditions", fmt), 2000), cut(get(first, cols, "testData", fmt), 2000), steps));
                s.replaceSteps(steps, this::removable);
                repo.saveAndFlush(s);
                if (existing.isPresent()) updated++; else created++;
            }
            return new ImportResult(created, updated);
        }
    }

    /** Header order of the sample template; the importer also accepts the aliases listed in ALIASES. */
    public static final String[] TEMPLATE_HEADERS = {"Scenario ID", "Title", "LOB", "Division", "Module", "Priority",
            "Preconditions", "Test data", "Step", "Action", "Expected"};

    private static final String[][] TEMPLATE_ROWS = {
            {"SC-001", "Mid-term adjustment: add a vessel to a live hull policy", "Marine", "UK", "Quote › Endorsement", "High",
                    "Bound hull policy in force; user has underwriter role", "Policy [policy ref], vessel IMO [number]",
                    "1", "Open the policy and start a mid-term adjustment", "Endorsement case opens with current risk details"},
            {"SC-001", "", "", "", "", "", "", "", "2", "Set the effective date to today", "Date accepted; pro-rata period shown"},
            {"SC-001", "", "", "", "", "", "", "", "3", "Add the new vessel with its hull value", "Vessel appears in the schedule"},
            {"SC-001", "", "", "", "", "", "", "", "4", "Open Pricing summary", "Premium includes the new vessel, pro-rated from the effective date"},
            {"SC-002", "Broker search on a large portfolio", "Casualty", "Europe", "Intake › Broker lookup", "Medium",
                    "Broker with 500+ policies exists", "Broker [broker code]",
                    "1", "Open a new submission and search for the broker by name", "Matching brokers listed within 5 seconds"},
            {"SC-002", "", "", "", "", "", "", "", "2", "Select the broker", "Broker details and portfolio summary are shown"},
            {"SC-002", "", "", "", "", "", "", "", "3", "Continue to risk details", "Submission moves to the risk details stage"},
            {"SC-003", "Referral to senior underwriter above authority", "Property", "IM", "Quote › Referral", "High",
                    "User's authority limit is below the quoted sum insured", "",
                    "1", "Quote a risk above the user's authority limit", "Referral reason shown on the quote summary"},
            {"SC-003", "", "", "", "", "", "", "", "2", "Submit for referral", "Case appears in the senior underwriter's queue"},
            {"SC-003", "", "", "", "", "", "", "", "3", "Approve as senior underwriter", "Quote returns to the underwriter as approved"},
    };

    private static final String[][] TEMPLATE_HELP = {
            {"Scenario ID", "Yes*", "Groups the rows of one scenario. Repeat it on every step row. Re-importing an existing ID updates that scenario.", "SC-001"},
            {"Title", "Yes*", "What the user is trying to do. Needed on the first row of each scenario.", "Mid-term adjustment: add a vessel"},
            {"LOB", "No", "Line of business. Pick from the list.", "Marine"},
            {"Division", "No", "Pick from the list.", "UK"},
            {"Module", "No", "Pega case type and stage, or screen.", "Quote › Endorsement"},
            {"Priority", "No", "High, Medium or Low (P1 to P4 also accepted). Default Medium.", "High"},
            {"Preconditions", "No", "What must be true before starting.", "Bound policy in force"},
            {"Test data", "No", "Policy, quote or broker references to use.", "Policy [ref]"},
            {"Step", "No", "Step number. Rows are sorted by it; without it, sheet order is used.", "1"},
            {"Action", "Yes", "What the tester does in this step. One row per step.", "Open Pricing summary"},
            {"Expected", "No", "What should happen.", "Premium includes the new vessel"},
    };

    /** Sample sheet people can copy: three scenarios across LOBs, dropdowns, and a How to fill tab. */
    public byte[] template(List<String> lobs, List<String> divisions) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font base = wb.createFont();
            base.setFontName("Arial");
            base.setFontHeightInPoints((short) 10);
            Font boldFont = wb.createFont();
            boldFont.setFontName("Arial");
            boldFont.setFontHeightInPoints((short) 10);
            boldFont.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(boldFont);
            head.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            CellStyle body = wb.createCellStyle();
            body.setFont(base);
            body.setWrapText(true);
            body.setVerticalAlignment(VerticalAlignment.TOP);

            Sheet sheet = wb.createSheet("Scenarios");
            Row h = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEADERS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(TEMPLATE_HEADERS[i]);
                c.setCellStyle(head);
            }
            for (int r = 0; r < TEMPLATE_ROWS.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int i = 0; i < TEMPLATE_ROWS[r].length; i++) {
                    Cell c = row.createCell(i);
                    String v = TEMPLATE_ROWS[r][i];
                    if (i == 8 && !v.isEmpty()) c.setCellValue(Integer.parseInt(v));
                    else c.setCellValue(v);
                    c.setCellStyle(body);
                }
            }
            int[] widths = {12, 40, 14, 11, 22, 10, 32, 28, 6, 44, 44};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            sheet.createFreezePane(0, 1);
            DataValidationHelper dv = sheet.getDataValidationHelper();
            addList(sheet, dv, 2, lobs);
            addList(sheet, dv, 3, divisions);
            addList(sheet, dv, 5, List.of("High", "Medium", "Low"));

            Sheet help = wb.createSheet("How to fill");
            String[] hh = {"Column", "Required", "What to enter", "Example"};
            Row hr = help.createRow(0);
            for (int i = 0; i < hh.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(hh[i]);
                c.setCellStyle(head);
            }
            for (int r = 0; r < TEMPLATE_HELP.length; r++) {
                Row row = help.createRow(r + 1);
                for (int i = 0; i < 4; i++) {
                    Cell c = row.createCell(i);
                    c.setCellValue(TEMPLATE_HELP[r][i]);
                    c.setCellStyle(body);
                }
            }
            String[] notes = {
                    "* Each scenario needs a Scenario ID or a Title. Use Scenario ID if you will re-import updates.",
                    "One row per step. Scenario details (Title, LOB, Division, Module, Priority, Preconditions, Test data) are read from the scenario's first row; later rows can leave them blank.",
                    "Column order doesn't matter and extra columns are ignored. The sheet must be the first tab of the workbook.",
                    "Delete the three sample scenarios (SC-001 to SC-003) before importing your own, or they will be imported too.",
                    "Import from Scenario library → Import Excel. Steps that already have test results can be reworded but not removed."};
            int r = TEMPLATE_HELP.length + 2;
            for (String n : notes) {
                Cell c = help.createRow(r++).createCell(0);
                c.setCellValue(n);
                c.setCellStyle(body);
            }
            help.setColumnWidth(0, 16 * 256);
            help.setColumnWidth(1, 10 * 256);
            help.setColumnWidth(2, 80 * 256);
            help.setColumnWidth(3, 34 * 256);
            wb.setSheetOrder("Scenarios", 0);
            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void addList(Sheet sheet, DataValidationHelper dv, int col, List<String> values) {
        if (values == null || values.isEmpty()) return;
        String joined = String.join(",", values);
        if (joined.length() > 250) return; // Excel's limit for an inline list
        DataValidation v = dv.createValidation(dv.createExplicitListConstraint(values.toArray(new String[0])),
                new CellRangeAddressList(1, 2000, col, col));
        v.setShowErrorBox(false); // suggest, don't block: the importer accepts any text
        sheet.addValidationData(v);
    }

    private void apply(Scenario s, ScenarioInput in) {
        s.setTitle(cut(in.title().trim(), 300));
        s.setLob(blankToNull(in.lob()));
        s.setDivision(blankToNull(in.division()));
        s.setModule(cut(blankToNull(in.module()), 200));
        s.setPriority(in.priority() == null ? Priority.MEDIUM : in.priority());
        s.setPreconditions(cut(blankToNull(in.preconditions()), 2000));
        s.setTestData(cut(blankToNull(in.testData()), 2000));
    }

    private String nextCode(Project project) {
        int max = 0;
        for (Scenario s : repo.findByProjectOrderByCodeAsc(project)) {
            String digits = s.getCode().replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && digits.length() < 9) max = Math.max(max, Integer.parseInt(digits));
        }
        return String.format("SC-%03d", max + 1);
    }

    private static String get(Row row, Map<String, Integer> cols, String field, DataFormatter fmt) {
        Integer i = cols.get(field);
        if (i == null) return null;
        Cell c = row.getCell(i);
        if (c == null) return null;
        String v = fmt.formatCellValue(c).trim();
        return v.isEmpty() ? null : v;
    }

    private static double number(String s) {
        try {
            return s == null ? Double.MAX_VALUE : Double.parseDouble(s.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
