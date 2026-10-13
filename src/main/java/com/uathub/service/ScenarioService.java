package com.uathub.service;

import com.uathub.domain.*;
import com.uathub.repo.ExecutionRepository;
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

    /**
     * Everything the scenario editor posts. dataSets rows are {name, channel, user/role, test data};
     * null means "leave the data sets as they are" (used by imports without a Test data sheet).
     */
    public record ScenarioInput(String code, String title, String lob, String division, String module,
                                Priority priority, String preconditions, String testData,
                                List<String[]> steps, List<String[]> dataSets) {}

    public record ImportResult(int created, int updated, int dataSets) {}

    public record CopyResult(int copied, int skipped) {}

    private static final Map<String, String> DATA_ALIASES = new HashMap<>();
    static {
        for (String n : List.of("scenario id", "scenario", "id", "test id", "tc id", "scenario no")) DATA_ALIASES.put(n, "code");
        for (String n : List.of("data set", "dataset", "data set name", "name", "variant")) DATA_ALIASES.put(n, "name");
        for (String n : List.of("channel")) DATA_ALIASES.put(n, "channel");
        for (String n : List.of("user / role", "user/role", "user", "role", "persona", "login as", "log in as")) DATA_ALIASES.put(n, "persona");
        for (String n : List.of("test data", "data", "test data details")) DATA_ALIASES.put(n, "testData");
    }

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
    private final ExecutionRepository executions;

    public ScenarioService(ScenarioRepository repo, StepResultRepository stepResults, ExecutionRepository executions) {
        this.repo = repo;
        this.stepResults = stepResults;
        this.executions = executions;
    }

    private boolean removable(ScenarioStep step) {
        return step.getId() == null || !stepResults.existsByStep(step);
    }

    private boolean removable(ScenarioDataSet d) {
        return d.getId() == null || !executions.existsByDataSet(d);
    }

    public List<Scenario> library(UatCycle cycle) {
        return cycle == null ? List.of() : repo.findByUatCycleOrderByCodeAsc(cycle);
    }

    public List<Scenario> assignable(UatCycle cycle) {
        return cycle == null ? List.of() : repo.findByUatCycleAndActiveTrueOrderByCodeAsc(cycle);
    }

    private static void requireOpen(UatCycle cycle) {
        if (cycle == null) throw new IllegalArgumentException("Create a UAT cycle first");
        if (!cycle.isOpen()) throw new IllegalArgumentException(cycle.getName() + " is closed, so its scenarios can't be changed");
    }

    public Scenario load(Long id, Project project) {
        Scenario s = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!s.getProject().getId().equals(project.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return s;
    }

    /** New scenarios go into the given cycle; an existing one stays in its own cycle. */
    @Transactional
    public Scenario save(Project project, UatCycle cycle, Long id, ScenarioInput in) {
        if (in.title() == null || in.title().isBlank()) throw new IllegalArgumentException("Add a scenario title");
        if (in.steps() == null || in.steps().isEmpty()) throw new IllegalArgumentException("Add at least one step");
        Scenario s = id == null ? new Scenario() : load(id, project);
        if (s.getUatCycle() == null) s.setUatCycle(cycle);
        requireOpen(s.getUatCycle());
        s.setProject(project);
        String code = blank(in.code()) ? (s.getCode() != null ? s.getCode() : nextCode(project)) : in.code().trim().toUpperCase();
        repo.findByUatCycleAndCodeIgnoreCase(s.getUatCycle(), code)
                .filter(other -> !other.getId().equals(s.getId()))
                .ifPresent(other -> { throw new IllegalArgumentException(code + " is already used by another scenario in this cycle"); });
        s.setCode(code);
        apply(s, in);
        s.replaceSteps(in.steps(), this::removable);
        if (in.dataSets() != null) s.replaceDataSets(checkDataSets(in.dataSets()), this::removable);
        return repo.save(s);
    }

    @Transactional
    public void setActive(Scenario s, boolean active) {
        requireOpen(s.getUatCycle());
        s.setActive(active);
        repo.save(s);
    }

    /**
     * Copies scenarios from another cycle of the same project into this one, with their steps and data sets.
     * Scenarios whose ID already exists here are skipped, so copying twice is harmless.
     */
    @Transactional
    public CopyResult copyFrom(UatCycle target, UatCycle source, String lob) {
        requireOpen(target);
        if (source.getId().equals(target.getId())) throw new IllegalArgumentException("Choose a different cycle to copy from");
        if (!source.getProject().getId().equals(target.getProject().getId())) throw new IllegalArgumentException("That cycle is in another project");
        int copied = 0, skipped = 0;
        for (Scenario from : repo.findByUatCycleAndActiveTrueOrderByCodeAsc(source)) {
            if (lob != null && !lob.isBlank() && !lob.equals(from.getLob())) continue;
            if (repo.findByUatCycleAndCodeIgnoreCase(target, from.getCode()).isPresent()) { skipped++; continue; }
            Scenario s = new Scenario();
            s.setProject(target.getProject());
            s.setUatCycle(target);
            s.setCode(from.getCode());
            s.setCopiedFrom(from.getCode() + " (" + source.getName() + ")");
            List<String[]> steps = from.getSteps().stream().map(st -> new String[]{st.getAction(), st.getExpected()}).toList();
            List<String[]> sets = from.getDataSets().stream()
                    .map(d -> new String[]{d.getName(), d.getChannel(), d.getPersona(), d.getTestData()}).toList();
            apply(s, new ScenarioInput(null, from.getTitle(), from.getLob(), from.getDivision(), from.getModule(),
                    from.getPriority(), from.getPreconditions(), from.getTestData(), steps, sets));
            s.replaceSteps(steps, this::removable);
            s.replaceDataSets(sets, this::removable);
            repo.save(s);
            copied++;
        }
        return new CopyResult(copied, skipped);
    }

    private static List<String[]> checkDataSets(List<String[]> rows) {
        Set<String> names = new HashSet<>();
        for (String[] r : rows) {
            if (r[0] == null || r[0].isBlank()) throw new IllegalArgumentException("Give every test data set a name");
            if (!names.add(r[0].trim().toLowerCase())) throw new IllegalArgumentException("Two data sets are called \"" + r[0].trim() + "\". Names must differ.");
        }
        return rows;
    }

    /**
     * Reads the first sheet. One row per step; rows with the same Scenario ID (or the same title when
     * there is no ID column) make one scenario. Scenario-level columns are taken from its first row.
     * An existing scenario with the same ID is updated and its steps replaced.
     */
    @Transactional
    public ImportResult importSheet(Project project, UatCycle cycle, InputStream in) throws IOException {
        requireOpen(cycle);
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Map<String, List<String[]>> dataSets = readDataSets(wb, fmt);
            int dataSetCount = 0;
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
                        : repo.findByUatCycleAndCodeIgnoreCase(cycle, code.trim());
                Scenario s = existing.orElseGet(Scenario::new);
                s.setProject(project);
                s.setUatCycle(cycle);
                s.setCode(code == null ? nextCode(project) : code.trim().toUpperCase());
                List<String[]> sets = code == null ? null : dataSets.get(code.trim().toUpperCase());
                apply(s, new ScenarioInput(null, cut(title, 300), get(first, cols, "lob", fmt), get(first, cols, "division", fmt),
                        get(first, cols, "module", fmt), Priority.parse(get(first, cols, "priority", fmt), Priority.MEDIUM),
                        cut(get(first, cols, "preconditions", fmt), 2000), cut(get(first, cols, "testData", fmt), 2000), steps, sets));
                s.replaceSteps(steps, this::removable);
                if (sets != null) {
                    s.replaceDataSets(checkDataSets(sets), this::removable);
                    dataSetCount += sets.size();
                }
                repo.saveAndFlush(s);
                if (existing.isPresent()) updated++; else created++;
            }
            return new ImportResult(created, updated, dataSetCount);
        }
    }

    /**
     * Optional sheet named "Test data" (or "Data sets"): one row per data set, with Scenario ID, Data set,
     * Channel, User / role and Test data. Returned by upper-case scenario ID.
     */
    private Map<String, List<String[]>> readDataSets(Workbook wb, DataFormatter fmt) {
        Map<String, List<String[]>> out = new HashMap<>();
        Sheet sheet = null;
        for (Sheet sh : wb) {
            String n = sh.getSheetName().trim().toLowerCase();
            if (n.equals("test data") || n.equals("data sets") || n.equals("datasets") || n.equals("test data sets")) sheet = sh;
        }
        if (sheet == null) return out;
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) return out;
        Map<String, Integer> cols = new HashMap<>();
        for (Cell c : header) {
            String field = DATA_ALIASES.get(fmt.formatCellValue(c).trim().toLowerCase());
            if (field != null) cols.putIfAbsent(field, c.getColumnIndex());
        }
        if (!cols.containsKey("code") || !cols.containsKey("name")) {
            throw new IllegalArgumentException("The Test data sheet needs a Scenario ID column and a Data set column.");
        }
        for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String code = get(row, cols, "code", fmt);
            String name = get(row, cols, "name", fmt);
            if (code == null || name == null) continue;
            out.computeIfAbsent(code.trim().toUpperCase(), k -> new ArrayList<>()).add(new String[]{
                    cut(name, 120), cut(get(row, cols, "channel", fmt), 255), cut(get(row, cols, "persona", fmt), 255),
                    cut(get(row, cols, "testData", fmt), 2000)});
        }
        return out;
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
            {"Test data tab (optional)", "", "One row per test data set: the same scenario tested through another channel, as another user, "
                    + "or with other data. Each data set becomes its own test case and can go to a different tester. "
                    + "Scenarios with no rows here are run once, with the Test data column above.", ""},
            {"  Scenario ID", "Yes", "Which scenario the data set belongs to (must match the Scenarios tab).", "SC-001"},
            {"  Data set", "Yes", "A short name, unique within the scenario.", "Broker portal – UK broker"},
            {"  Channel", "No", "Where the scenario is run. Pick from the list or type your own.", "Broker portal"},
            {"  User / role", "No", "Who to log in as. A user or role, never a password.", "Broker user – [broker firm]"},
            {"  Test data", "No", "References for this data set: policy, quote, broker, client.", "Policy [ref] via broker [code]"},
    };

    public static final String[] DATA_HEADERS = {"Scenario ID", "Data set", "Channel", "User / role", "Test data"};

    private static final String[][] DATA_ROWS = {
            {"SC-001", "Underwriter – UK", "Underwriter workbench", "Underwriter (UK Marine)", "Policy [hull policy ref], vessel IMO [number]"},
            {"SC-001", "Broker portal – UK broker", "Broker portal", "Broker user – [broker firm]", "Policy [hull policy ref] placed by broker [broker code]"},
            {"SC-003", "UK underwriter", "Underwriter workbench", "Underwriter (UK Property), authority [limit]", "Sum insured above authority: [amount]"},
            {"SC-003", "IM underwriter", "Underwriter workbench", "Underwriter (IM Property), authority [limit]", "Sum insured above authority: [amount]"},
    };

    /** Sample workbook people can copy: three scenarios, test data sets, dropdowns, and a How to fill tab. */
    public byte[] template(List<String> lobs, List<String> divisions, List<String> channels) throws IOException {
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

            Sheet data = wb.createSheet("Test data");
            Row dh = data.createRow(0);
            for (int i = 0; i < DATA_HEADERS.length; i++) {
                Cell c = dh.createCell(i);
                c.setCellValue(DATA_HEADERS[i]);
                c.setCellStyle(head);
            }
            for (int r = 0; r < DATA_ROWS.length; r++) {
                Row row = data.createRow(r + 1);
                for (int i = 0; i < DATA_ROWS[r].length; i++) {
                    Cell c = row.createCell(i);
                    c.setCellValue(DATA_ROWS[r][i]);
                    c.setCellStyle(body);
                }
            }
            int[] dw = {12, 30, 24, 36, 50};
            for (int i = 0; i < dw.length; i++) data.setColumnWidth(i, dw[i] * 256);
            data.createFreezePane(0, 1);
            addList(data, data.getDataValidationHelper(), 2, channels);

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
                    "Delete the three sample scenarios (SC-001 to SC-003) and their Test data rows before importing your own, or they will be imported too.",
                    "Scenarios are imported into the UAT cycle selected in UAT Hub. Re-importing a Scenario ID updates that cycle's copy; a Scenario ID with rows on the Test data tab gets exactly those data sets.",
                    "Import from Scenario library → Import Excel. Steps and data sets that are already in a test run can be reworded but not removed."};
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
        for (Scenario s : repo.findByProject(project)) {
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
