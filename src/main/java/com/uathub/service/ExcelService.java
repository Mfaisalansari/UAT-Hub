package com.uathub.service;

import com.uathub.domain.*;
import com.uathub.repo.FeedbackRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Excel in (migrate the existing UAT sheet) and Excel out (for anyone who still wants the sheet). */
@Service
public class ExcelService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final String[] HEADERS = {"ID", "UAT cycle", "Title", "LOB", "Division", "Case type / stage", "Screen", "Type",
            "Severity", "Stage", "Decision", "Rationale", "Target release", "Jira", "Raised by", "Decided by",
            "Created", "Description", "Expected", "Actual", "QA note"};

    /** Header aliases accepted on import (lower case). */
    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        put("title", "title", "summary", "feedback", "issue", "observation");
        put("description", "description", "details", "comments", "comment", "steps");
        put("lob", "lob", "line of business");
        put("division", "division", "region");
        put("module", "case type / stage", "module", "case type", "stage", "area");
        put("screen", "screen", "page", "section");
        put("type", "type", "category", "feedback type");
        put("severity", "severity", "priority");
        put("expected", "expected", "expected result");
        put("actual", "actual", "actual result");
    }

    private static void put(String field, String... names) {
        for (String n : names) ALIASES.put(n, field);
    }

    private final FeedbackRepository repo;
    private final FeedbackService feedbackService;

    public ExcelService(FeedbackRepository repo, FeedbackService feedbackService) {
        this.repo = repo;
        this.feedbackService = feedbackService;
    }

    /** All feedback of the project, or only one UAT cycle's when cycle is not null. */
    public byte[] export(Project project, UatCycle cycle) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("UAT feedback");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            Row h = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
            }
            int r = 1;
            List<Feedback> items = cycle == null ? repo.findByProjectOrderByIdDesc(project)
                    : repo.findByProjectAndUatCycleOrderByIdDesc(project, cycle);
            for (Feedback f : items) {
                Row row = sheet.createRow(r++);
                String[] v = {f.getCode(), f.getCycle(), f.getTitle(), f.getLob(), f.getDivision(), f.getModule(), f.getScreen(),
                        f.getType().getLabel(), f.getSeverity().getLabel(), f.getStage().getLabel(),
                        f.getDecision() == null ? null : f.getDecision().getLabel(), f.getDecisionRationale(),
                        f.getTargetRelease(), f.getJiraKey(),
                        f.getRaisedBy() == null ? null : f.getRaisedBy().getName(),
                        f.getDecidedBy() == null ? null : f.getDecidedBy().getName(),
                        DATE.format(f.getCreatedAt()), f.getDescription(), f.getExpected(), f.getActual(), f.getQaNote()};
                for (int i = 0; i < v.length; i++) row.createCell(i).setCellValue(v[i] == null ? "" : v[i]);
            }
            sheet.createFreezePane(0, 1);
            for (int i = 0; i < 10; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Reads the first sheet. Columns are matched by header name, so the column order doesn't matter. */
    public int importSheet(Project project, UatCycle cycle, AppUser by, InputStream in) throws IOException {
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
            if (!cols.containsKey("title")) {
                throw new IllegalArgumentException("No title column found. Name one column Title, Summary or Feedback.");
            }
            int count = 0;
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String title = get(row, cols, "title", fmt);
                if (title == null) continue;
                Feedback f = new Feedback();
                f.setTitle(title.length() > 300 ? title.substring(0, 300) : title);
                f.setDescription(cut(get(row, cols, "description", fmt), 4000));
                f.setLob(get(row, cols, "lob", fmt));
                f.setDivision(get(row, cols, "division", fmt));
                f.setModule(get(row, cols, "module", fmt));
                f.setScreen(get(row, cols, "screen", fmt));
                f.setType(FeedbackType.parse(get(row, cols, "type", fmt), FeedbackType.BUG));
                f.setSeverity(Severity.parse(get(row, cols, "severity", fmt), Severity.MEDIUM));
                f.setExpected(cut(get(row, cols, "expected", fmt), 2000));
                f.setActual(cut(get(row, cols, "actual", fmt), 2000));
                feedbackService.importRow(project, cycle, by, f);
                count++;
            }
            return count;
        }
    }

    private static String get(Row row, Map<String, Integer> cols, String field, DataFormatter fmt) {
        Integer i = cols.get(field);
        if (i == null) return null;
        Cell c = row.getCell(i);
        if (c == null) return null;
        String v = fmt.formatCellValue(c).trim();
        return v.isEmpty() ? null : v;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
