package com.uathub.service;

import com.uathub.domain.Priority;
import com.uathub.domain.Project;
import com.uathub.domain.Scenario;
import com.uathub.domain.ScenarioStep;
import com.uathub.repo.ScenarioRepository;
import com.uathub.repo.StepResultRepository;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
