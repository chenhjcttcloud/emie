package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.controller.PerformanceController;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonthlyPointReconciliationTest {
    private JdbcTemplate jdbc;
    private MonthlyPointReportService service;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        service = new MonthlyPointReportService(source);
        jdbc.execute("CREATE TABLE users(user_id VARCHAR(100),name VARCHAR(100),role VARCHAR(40),status VARCHAR(40))");
        jdbc.execute("CREATE TABLE projects(id BIGINT,product_name VARCHAR(200))");
        jdbc.execute(
                "CREATE TABLE sub_tasks(id BIGINT,name VARCHAR(200),project_id BIGINT,designer_id VARCHAR(100),completed_at TIMESTAMP,point_rule_code VARCHAR(80),status VARCHAR(40))");
        jdbc.execute(
                "CREATE TABLE design_requirements(id BIGINT,name VARCHAR(200),designer_id VARCHAR(100),delivered_at TIMESTAMP,updated_at TIMESTAMP,point_rule_code VARCHAR(80),status VARCHAR(40))");
        jdbc.execute("CREATE TABLE point_rules(rule_code VARCHAR(80),category VARCHAR(100))");
        jdbc.execute("INSERT INTO point_rules VALUES('D30_9','包装设计'),('D30_6','产品设计'),('D30_14','电商视觉')");
        jdbc.execute("CREATE TABLE task_withdrawals(id BIGINT,user_id VARCHAR(100),sub_task_id BIGINT)");
        jdbc.execute("CREATE TABLE point_appeals(id BIGINT,applicant_user_id VARCHAR(100),point_ledger_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE point_ledgers(id BIGINT,user_id VARCHAR(100),sub_task_id BIGINT,rule_code VARCHAR(128),rule_description VARCHAR(255),points DECIMAL(12,2),accounting_month VARCHAR(7),count_in_performance BOOLEAN,reason VARCHAR(500),created_by VARCHAR(100),created_at TIMESTAMP,submitted_at TIMESTAMP,confirmed_at TIMESTAMP,delivery_version_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE point_adjustment_ledgers(id BIGINT,user_id VARCHAR(100),source_type VARCHAR(40),source_id BIGINT,rule_code VARCHAR(80),rule_description VARCHAR(255),points DECIMAL(12,2),accounting_month VARCHAR(7),reason VARCHAR(500),created_by VARCHAR(100),created_at TIMESTAMP,submitted_at TIMESTAMP)");
        jdbc.execute("INSERT INTO users VALUES('d1','谢梓文','designer','active'),('d2','其他设计师','designer','active')");
        jdbc.execute("INSERT INTO projects VALUES(1,'项目')");
        task(46, "送审包装修改", "2026-10-08");
        task(1167, "产品文件输出", "2026-10-12");
        task(1038, "盒内卡纸+包装修改输出", "2026-10-13");
        task(1034, "ui设计", "2026-10-13");
        task(1124, "包装输出", "2026-10-20");
        task(34, "设计第一次修改", "2026-10-26");
        task(1019, "p图修改", "2026-10-28");
        task(49, "ui设计（待完成）", null);
        task(1099, "大货文件", "2026-11-11");
        task(1166, "包装设计修改", "2026-11-11");
        ledger(1, 34, "3", "2026-10");
        ledger(2, 49, "2", "2026-10");
        ledger(3, 1019, "1", "2026-10");
        ledger(4, 1034, "3", "2026-10");
        ledger(5, 1038, "1", "2026-10");
        ledger(6, 1099, "3", "2026-10");
        ledger(7, 1124, "3", "2026-09");
        ledger(8, 1124, "3", "2026-10");
        ledger(9, 1166, "2", "2026-10");
        ledger(10, 1167, "2", "2026-10");
        requirement(34, "元气双栖风扇包装p图修改", "D30_9", "1");
        requirement(38, "T3牙刷", "D30_6", "2.5");
        requirement(40, "v39", "D30_14", "1.5");
    }

    @Test
    void screenshotDifferenceReconcilesByAccountingMonthAndPreservesOriginalRows() {
        ledger(98, 46, "100", "2026-10");
        jdbc.update("UPDATE point_ledgers SET count_in_performance=FALSE WHERE id=98");
        ledger(99, 46, "500", "2026-10");
        jdbc.update("UPDATE point_ledgers SET user_id='d2' WHERE id=99");
        var before = jdbc.queryForList("SELECT * FROM point_ledgers ORDER BY id");
        var items = service.entries("2026-10", List.of("d1"));

        assertEquals(12, items.size());
        assertPoints("25", MonthlyPointReportService.total(items));
        assertTrue(items.stream().allMatch(item -> "d1".equals(item.get("userId"))));
        assertTrue(items.stream().allMatch(item -> "2026-10".equals(item.get("accountingMonth"))));
        assertTrue(items.stream().anyMatch(item -> Long.valueOf(1099).equals(item.get("taskId"))));
        assertTrue(items.stream().anyMatch(item -> Long.valueOf(49).equals(item.get("taskId"))));
        assertEquals(before, jdbc.queryForList("SELECT * FROM point_ledgers ORDER BY id"));
        assertPoints("28", MonthlyPointReportService.total(service.entries("2026", List.of("d1"))));
        assertPoints("0", MonthlyPointReportService.total(service.entries("2026-11", List.of("d1"))));
    }

    @Test
    void enrichedCompletedItemsKeepPeriodAndLifetimePointsSeparateIncludingRequirements() {
        var report = octoberReport();
        service.enrich(report);
        var designer = designer(report);
        var tasks = tasks(designer);

        assertPoints("25", (Number) designer.get("score"));
        assertPoints("25", MonthlyPointReportService.total(items(designer)));
        assertEquals(10, tasks.size());
        assertEquals(
                3L,
                tasks.stream()
                        .filter(item -> "requirement".equals(item.get("kind")))
                        .count());
        var packaging = item(tasks, "task", 1124);
        assertPoints("6", (Number) packaging.get("score"));
        assertPoints("3", (Number) packaging.get("periodPoints"));
        assertPoints("3", (Number) packaging.get("otherPeriodPoints"));
        assertPoints("21", sum(tasks, "score"));
        assertPoints("18", sum(tasks, "periodPoints"));
        assertPoints("3", sum(tasks, "otherPeriodPoints"));
        assertPoints("1", (Number) item(tasks, "requirement", 34).get("score"));
        assertPoints("3", (Number) item(tasks, "task", 34).get("score"));
        var related = related(designer);
        assertEquals(13, designer.get("relatedCount"));
        assertEquals(13, related.size());
        assertPoints("25", sum(related, "periodPoints"));
        assertPoints("28", sum(related, "score"));
        assertPoints("3", sum(related, "otherPeriodPoints"));
        assertEquals("pending", item(related, "task", 49).get("periodStatus"));
        assertEquals("submitted_for_review", item(related, "task", 49).get("status"));
        assertEquals("completed_other_period", item(related, "task", 1099).get("periodStatus"));
        assertEquals("completed_other_period", item(related, "task", 1166).get("periodStatus"));
        assertEquals("completed_in_period", item(related, "task", 46).get("periodStatus"));
        assertPoints("0", (Number) item(related, "task", 46).get("score"));
    }

    @Test
    void novemberCompletionDoesNotMoveOctoberAwardsIntoNovember() {
        var report = report(
                "2026-11",
                "0",
                List.of(
                        completed("task", 1099, "大货文件", "legacy", "3", "2026-11-11"),
                        completed("task", 1166, "包装设计修改", "3.0", "2", "2026-11-11")));
        service.enrich(report);

        assertPoints("0", (Number) designer(report).get("score"));
        assertTrue(items(designer(report)).isEmpty());
        assertPoints("5", sum(tasks(designer(report)), "score"));
        assertPoints("0", sum(tasks(designer(report)), "periodPoints"));
        assertPoints("5", sum(tasks(designer(report)), "otherPeriodPoints"));
        assertEquals(2, related(designer(report)).size());
        assertPoints("0", sum(related(designer(report)), "periodPoints"));
        assertTrue(related(designer(report)).stream()
                .allMatch(item -> "completed_in_period".equals(item.get("periodStatus"))));
    }

    @Test
    void explicitAccountingMonthWinsOverBookingDateAndDecimalDebitsRemainExact() {
        jdbc.update("INSERT INTO point_appeals VALUES(51,'d1',7)");
        adjustment(51, "APPEAL", 51, "-0.25", "2026-10");
        adjustment(52, "MANUAL", 52, "0.1", "2026-10");
        adjustment(53, "MANUAL", 53, "0.2", "2026-10");
        jdbc.update("UPDATE point_adjustment_ledgers SET created_at='2026-11-05 10:00:00' WHERE id=51");
        var october = service.entries("2026-10", List.of("d1"));

        assertPoints("25.05", MonthlyPointReportService.total(october));
        assertPoints("0", MonthlyPointReportService.total(service.entries("2026-11", List.of("d1"))));
        var report = report("2026-10", "25.05", tasks(designer(octoberReport())));
        service.enrich(report);
        var packaging = item(tasks(designer(report)), "task", 1124);
        assertPoints("5.75", (Number) packaging.get("score"));
        assertPoints("2.75", (Number) packaging.get("periodPoints"));
        assertPoints("3", (Number) packaging.get("otherPeriodPoints"));
        assertEquals(15, related(designer(report)).size());
        assertPoints("25.05", sum(related(designer(report)), "periodPoints"));
        assertPoints("28.05", sum(related(designer(report)), "score"));
    }

    @Test
    void repeatedAwardsAndReassignmentKeepOneRelatedItemPerKindAndRetainHistoricalRecipient() {
        ledger(15, 1124, "0.5", "2026-10");
        jdbc.update("UPDATE point_ledgers SET rule_code='D30_9:CHANGE:extra' WHERE id=15");
        ledger(16, 1124, "500", "2026-10");
        jdbc.update("UPDATE point_ledgers SET user_id='d2' WHERE id=16");
        jdbc.update("UPDATE sub_tasks SET designer_id='d2' WHERE id=1124");
        var completed = tasks(designer(octoberReport())).stream()
                .filter(item -> !Long.valueOf(1124).equals(item.get("id")))
                .toList();
        var report = report("2026-10", "25.5", completed);
        service.enrich(report);

        var designer = designer(report);
        var related = related(designer);
        assertEquals(9, tasks(designer).size());
        assertEquals(13, related.size());
        assertEquals(
                13L,
                related.stream()
                        .map(item -> item.get("kind") + ":" + item.get("id"))
                        .distinct()
                        .count());
        assertPoints("25.5", sum(related, "periodPoints"));
        assertPoints("28.5", sum(related, "score"));
        var reassigned = item(related, "task", 1124);
        assertPoints("6.5", (Number) reassigned.get("score"));
        assertPoints("3.5", (Number) reassigned.get("periodPoints"));
        assertPoints("1", (Number) item(related, "requirement", 34).get("periodPoints"));
        assertPoints("3", (Number) item(related, "task", 34).get("periodPoints"));
    }

    @Test
    void reassignedAndLedgerOnlyItemsUseActualCompletionDatesForMonthlyAndAnnualStatus() {
        jdbc.update("UPDATE sub_tasks SET designer_id='d2' WHERE id=1124");
        var monthly = report("2026-10", "25", List.of());
        service.enrich(monthly);

        var monthlyItems = related(designer(monthly));
        assertTrue(tasks(designer(monthly)).isEmpty());
        assertEquals("completed_in_period", item(monthlyItems, "task", 1124).get("periodStatus"));
        assertEquals("pending", item(monthlyItems, "task", 49).get("periodStatus"));
        assertEquals("completed_other_period", item(monthlyItems, "task", 1099).get("periodStatus"));
        assertPoints("25", sum(monthlyItems, "periodPoints"));

        var annual = report("2026", "28", List.of());
        service.enrich(annual);
        var annualItems = related(designer(annual));
        assertTrue(tasks(designer(annual)).isEmpty());
        assertEquals("completed_in_period", item(annualItems, "task", 1099).get("periodStatus"));
        assertEquals("completed_in_period", item(annualItems, "task", 1166).get("periodStatus"));
        assertPoints("28", sum(annualItems, "periodPoints"));
        assertPoints("28", sum(annualItems, "score"));
        assertPoints("0", sum(annualItems, "otherPeriodPoints"));
    }

    @Test
    void standaloneManualAdjustmentsRemainSeparateRowsAndNeverGuessTaskOrRequirementFromSourceId() throws Exception {
        adjustment(51, "MANUAL", 1124, "-0.25", "2026-10");
        adjustment(52, "MANUAL", 40, "0.1", "2026-10");
        var report = report("2026-10", "24.85", tasks(designer(octoberReport())));
        service.enrich(report);

        var related = related(designer(report));
        assertEquals(15, designer(report).get("relatedCount"));
        assertEquals(15, related.size());
        var independent = related.stream()
                .filter(item -> "adjustment".equals(item.get("kind")))
                .toList();
        assertEquals(2, independent.size());
        assertTrue(independent.stream().allMatch(item -> "independent".equals(item.get("periodStatus"))));
        assertEquals(
                List.of("A-51", "A-52"),
                independent.stream().map(item -> item.get("id")).sorted().toList());
        assertPoints("-0.15", sum(independent, "periodPoints"));
        assertPoints("24.85", sum(related, "periodPoints"));
        assertPoints("27.85", sum(related, "score"));
        assertPoints("6", (Number) item(related, "task", 1124).get("score"));
        assertPoints("1.5", (Number) item(related, "requirement", 40).get("score"));
        try (var workbook =
                new XSSFWorkbook(new ByteArrayInputStream(DesignerMonthlyReportWorkbook.write(report, "3.0")))) {
            var sheet = workbook.getSheet("当月关联事项");
            assertEquals(16, sheet.getPhysicalNumberOfRows());
            assertPoints("24.85", columnTotal(sheet, "当月积分"));
            var independentIds = new ArrayList<String>();
            for (int row = 1; row <= sheet.getLastRowNum(); row++) {
                String id = sheet.getRow(row).getCell(column(sheet, "事项编号")).getStringCellValue();
                if (id.startsWith("A-")) independentIds.add(id);
            }
            assertEquals(List.of("A-51", "A-52"), independentIds);
        }
    }

    @Test
    void reopenedRequirementsAndDeletedTaskReferencesRemainVisibleWithoutInventingCompletionDates() {
        requirement(60, "已重新打开的需求", "D30_14", "1.25");
        jdbc.update("UPDATE design_requirements SET status='pending_acceptance',delivered_at=NULL WHERE id=60");
        ledger(17, 999, "0.75", "2026-10");
        var report = report("2026-10", "27", tasks(designer(octoberReport())));
        service.enrich(report);

        var related = related(designer(report));
        assertEquals(15, related.size());
        assertPoints("27", sum(related, "periodPoints"));
        var requirement = item(related, "requirement", 60);
        assertEquals("pending", requirement.get("periodStatus"));
        assertEquals("pending_acceptance", requirement.get("status"));
        assertNull(requirement.get("completedAt"));
        assertEquals("电商视觉", requirement.get("category"));
        assertEquals("legacy", requirement.get("ruleVersion"));
        var deleted = item(related, "task", 999);
        assertEquals("子任务 #999", deleted.get("name"));
        assertEquals("pending", deleted.get("periodStatus"));
        assertPoints("0.75", (Number) deleted.get("periodPoints"));
    }

    @Test
    void typedAdjustmentsFollowOwnedAssociationsWithoutConfusingTaskAndRequirementIds() {
        adjustment(51, "MANUAL", 51, "-0.25", "2026-10");
        jdbc.update("INSERT INTO point_appeals VALUES(5,'d1',7),(6,'d2',7)");
        jdbc.update("INSERT INTO task_withdrawals VALUES(5,'d1',1124),(6,'d2',1124)");
        adjustment(52, "APPEAL", 5, "0.5", "2026-10");
        adjustment(53, "TASK_WITHDRAWAL", 5, "-1", "2026-10");
        adjustment(54, "APPEAL", 6, "50", "2026-10");
        adjustment(55, "TASK_WITHDRAWAL", 6, "50", "2026-10");
        adjustment(56, "MANUAL", 1124, "50", "2026-10");

        var linked = service.itemEntries(List.of(1124L), List.of());
        assertEquals(4, linked.size());
        assertPoints("5.5", MonthlyPointReportService.total(linked));
        var manual = service.entries("2026-10", List.of("d1")).stream()
                .filter(row -> "MANUAL".equals(row.get("source")))
                .toList();
        assertEquals(2, manual.size());
        assertTrue(manual.stream().allMatch(row -> row.get("taskId") == null));
        assertPoints("49.75", MonthlyPointReportService.total(manual));
        var collidingIds = service.itemEntries(List.of(34L), List.of(34L));
        assertEquals(2, collidingIds.size());
        assertPoints("4", MonthlyPointReportService.total(collidingIds));
    }

    @Test
    void reconciliationRejectsMismatchAndInvalidPeriodsRatherThanExportingPartialTotals() {
        var incorrect = report("2026-10", "16", tasks(designer(octoberReport())));
        assertThrows(IllegalStateException.class, () -> service.enrich(incorrect));
        for (String period : List.of("", "0000", "2026-00", "2026-13", "2026-1", "2026-10-01", "invalid")) {
            assertThrows(IllegalArgumentException.class, () -> service.entries(period, List.of("d1")), period);
        }
        assertThrows(IllegalArgumentException.class, () -> service.entries(null, List.of("d1")));
        assertTrue(service.entries("2026-10", List.of()).isEmpty());
        assertTrue(service.entries("2026-10", List.of("d1' OR '1'='1")).isEmpty());
        assertTrue(service.itemEntries(List.of(), List.of()).isEmpty());
    }

    @Test
    void workbookIncludesEveryRelatedItemAndEveryPointRegardlessOfPageVersionFilter() throws Exception {
        var report = octoberReport();
        service.enrich(report);
        try (var workbook =
                new XSSFWorkbook(new ByteArrayInputStream(DesignerMonthlyReportWorkbook.write(report, "")))) {
            assertEquals(3, workbook.getNumberOfSheets());
            var summary = workbook.getSheet("月度积分汇总");
            var details = workbook.getSheet("当月积分明细");
            var related = workbook.getSheet("当月关联事项");
            assertEquals(2, summary.getPhysicalNumberOfRows());
            assertPoints(
                    "25", summary.getRow(1).getCell(column(summary, "当月积分")).getNumericCellValue());
            assertPoints(
                    "25", summary.getRow(1).getCell(column(summary, "明细合计")).getNumericCellValue());
            assertPoints(
                    "10", summary.getRow(1).getCell(column(summary, "完成事项数")).getNumericCellValue());
            assertPoints(
                    "13", summary.getRow(1).getCell(column(summary, "关联事项数")).getNumericCellValue());
            assertEquals(13, details.getPhysicalNumberOfRows());
            assertPoints("25", columnTotal(details, "积分"));
            assertEquals(14, related.getPhysicalNumberOfRows());
            assertEquals(12, related.getRow(0).getLastCellNum());
            assertEquals("当月积分", related.getRow(0).getCell(11).getStringCellValue());
            for (var header : related.getRow(0)) {
                assertFalse(header.getStringCellValue().contains("累计"));
                assertFalse(header.getStringCellValue().contains("其他月份"));
            }
            assertPoints("25", columnTotal(related, "当月积分"));
            boolean packagingFound = false;
            for (int row = 1; row <= related.getLastRowNum(); row++) {
                var item = related.getRow(row);
                if ("1124".equals(item.getCell(column(related, "事项编号")).getStringCellValue())) {
                    assertPoints("3", item.getCell(column(related, "当月积分")).getNumericCellValue());
                    assertEquals(12, item.getLastCellNum());
                    packagingFound = true;
                }
            }
            assertTrue(packagingFound);
        }
        for (String filter : List.of("legacy", "3.0")) {
            try (var workbook =
                    new XSSFWorkbook(new ByteArrayInputStream(DesignerMonthlyReportWorkbook.write(report, filter)))) {
                assertPoints("25", columnTotal(workbook.getSheet("月度积分汇总"), "当月积分"));
                assertPoints("25", columnTotal(workbook.getSheet("当月积分明细"), "积分"));
                assertEquals(14, workbook.getSheet("当月关联事项").getPhysicalNumberOfRows());
                assertEquals(12, workbook.getSheet("当月关联事项").getRow(0).getLastCellNum());
                assertPoints("25", columnTotal(workbook.getSheet("当月关联事项"), "当月积分"));
            }
        }
    }

    @Test
    void workbookRefusesMissingOrUnbalancedRelatedItemsEvenWhenMonthlyLedgerStillBalances() {
        var report = octoberReport();
        service.enrich(report);
        var designer = designer(report);
        var original = related(designer);
        designer.remove("relatedItems");
        assertThrows(IllegalStateException.class, () -> DesignerMonthlyReportWorkbook.write(report, ""));
        var incomplete = original.stream()
                .filter(item ->
                        !("task".equals(item.get("kind")) && Long.valueOf(49).equals(item.get("id"))))
                .toList();
        designer.put("relatedItems", incomplete);
        designer.put("relatedCount", incomplete.size());
        assertPoints("25", MonthlyPointReportService.total(items(designer)));
        assertThrows(IllegalStateException.class, () -> DesignerMonthlyReportWorkbook.write(report, ""));
    }

    @Test
    void workbookWritesFormulaLookingNamesAsStringsAndRefusesUnbalancedDetails() throws Exception {
        jdbc.update("UPDATE sub_tasks SET name='=SUM(1,2)' WHERE id=1124");
        jdbc.update("UPDATE projects SET product_name='=HYPERLINK(\"https://example.invalid\",\"open\")' WHERE id=1");
        var report =
                report("2026-10", "25", List.of(completed("task", 1124, "=SUM(1,2)", "legacy", "6", "2026-10-20")));
        service.enrich(report);
        try (var workbook =
                new XSSFWorkbook(new ByteArrayInputStream(DesignerMonthlyReportWorkbook.write(report, "")))) {
            var related = workbook.getSheet("当月关联事项");
            var name = related.getRow(1).getCell(column(related, "事项名称"));
            assertEquals(CellType.STRING, name.getCellType());
            assertEquals("=SUM(1,2)", name.getStringCellValue());
            var details = workbook.getSheet("当月积分明细");
            boolean found = false;
            for (int row = 1; row <= details.getLastRowNum(); row++) {
                var cell = details.getRow(row).getCell(column(details, "项目"));
                if (cell.getStringCellValue().startsWith("=HYPERLINK")) {
                    assertEquals(CellType.STRING, cell.getCellType());
                    found = true;
                }
            }
            assertTrue(found);
        }
        designer(report).put("score", new BigDecimal("16"));
        assertThrows(IllegalStateException.class, () -> DesignerMonthlyReportWorkbook.write(report, ""));
    }

    @Test
    void detailEndpointRequiresAdminAndValidatesPeriodWithoutExposingOtherData() throws Exception {
        var performance = mock(PerformanceService.class);
        when(performance.designerPointDetails(anyString(), anyString())).thenCallRealMethod();
        doReturn(Map.of(
                        "month",
                        "2026-10",
                        "userId",
                        "d1",
                        "designer",
                        "谢梓文",
                        "score",
                        25,
                        "items",
                        List.of(Map.of("id", "L-1", "points", 3))))
                .when(performance)
                .designerPointDetails("2026-10", "d1");
        var mvc = MockMvcBuilders.standaloneSetup(
                        new PerformanceController(performance, mock(DesignerMonthlyDeliveryService.class)))
                .build();
        String endpoint = "/api/performance/designer-monthly-report/d1/points";
        var admin = new AuthSession("admin", "admin", "管理员");
        mvc.perform(get(endpoint).param("month", "2026-10")).andExpect(status().isForbidden());
        mvc.perform(get(endpoint)
                        .param("month", "2026-10")
                        .requestAttr("authSession", new AuthSession("designer", "d1", "谢梓文")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(performance);
        mvc.perform(get(endpoint).param("month", "2026-10").requestAttr("authSession", admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.score").value(25))
                .andExpect(jsonPath("$.userId").value("d1"))
                .andExpect(jsonPath("$.items[0].points").value(3));
        mvc.perform(get(endpoint).param("month", "2026-13").requestAttr("authSession", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get(endpoint).requestAttr("authSession", admin)).andExpect(status().isBadRequest());
    }

    private void task(long id, String name, String completedDay) {
        jdbc.update(
                "INSERT INTO sub_tasks(id,name,project_id,designer_id,completed_at,status,point_rule_code) VALUES(?,?,1,'d1',?,?,'D30_9')",
                id,
                name,
                completedDay == null ? null : LocalDateTime.parse(completedDay + "T12:00:00"),
                completedDay == null ? "submitted_for_review" : "completed");
    }

    private void ledger(long id, long taskId, String points, String month) {
        jdbc.update(
                "INSERT INTO point_ledgers(id,user_id,sub_task_id,rule_code,points,accounting_month,count_in_performance,created_at) VALUES(?,'d1',?,'D30_9:BASE',?,?,TRUE,?)",
                id,
                taskId,
                new BigDecimal(points),
                month,
                YearMonth.parse(month).atDay(15).atStartOfDay());
    }

    private void requirement(long id, String name, String rule, String points) {
        jdbc.update(
                "INSERT INTO design_requirements(id,name,designer_id,delivered_at,updated_at,point_rule_code,status) VALUES(?,?,'d1','2026-10-15 12:00:00','2026-10-15 12:00:00',?,'completed')",
                id,
                name,
                rule);
        adjustment(id, "DESIGN_REQUIREMENT", id, points, "2026-10");
        jdbc.update("UPDATE point_adjustment_ledgers SET rule_code=? WHERE id=?", rule, id);
    }

    private void adjustment(long id, String source, long sourceId, String points, String month) {
        jdbc.update(
                "INSERT INTO point_adjustment_ledgers(id,user_id,source_type,source_id,points,accounting_month,created_at) VALUES(?,'d1',?,?,?,?,?)",
                id,
                source,
                sourceId,
                new BigDecimal(points),
                month,
                YearMonth.parse(month).atDay(15).atStartOfDay());
    }

    private Map<String, Object> octoberReport() {
        return report(
                "2026-10",
                "25",
                List.of(
                        completed("task", 46, "送审包装修改", "unbound", "0", "2026-10-08"),
                        completed("task", 1167, "产品文件输出", "legacy", "2", "2026-10-12"),
                        completed("task", 1038, "盒内卡纸+包装修改输出", "unbound", "1", "2026-10-13"),
                        completed("task", 1034, "ui设计", "unbound", "3", "2026-10-13"),
                        completed("task", 1124, "包装输出", "legacy", "6", "2026-10-20"),
                        completed("task", 34, "设计第一次修改", "unbound", "3", "2026-10-26"),
                        completed("task", 1019, "p图修改", "unbound", "1", "2026-10-28"),
                        completed("requirement", 34, "元气双栖风扇包装p图修改", "legacy", "1", "2026-10-15"),
                        completed("requirement", 38, "T3牙刷", "legacy", "2.5", "2026-10-15"),
                        completed("requirement", 40, "v39", "legacy", "1.5", "2026-10-15")));
    }

    private Map<String, Object> report(String month, String score, List<Map<String, Object>> completed) {
        var designer = new LinkedHashMap<String, Object>();
        designer.put("userId", "d1");
        designer.put("designer", "谢梓文");
        designer.put("month", month);
        designer.put("score", new BigDecimal(score));
        designer.put("completedCount", completed.size());
        designer.put("tasks", new ArrayList<>(completed));
        designer.put("categoryCounts", Map.of("未分类", completed.size()));
        return new LinkedHashMap<>(Map.of("month", month, "designers", List.of(designer)));
    }

    private Map<String, Object> completed(String kind, long id, String name, String version, String score, String day) {
        return Map.of(
                "id",
                id,
                "kind",
                kind,
                "name",
                name,
                "ruleVersion",
                version,
                "ruleCode",
                "legacy".equals(version) ? "D30_9" : "",
                "project",
                "项目",
                "category",
                "未分类",
                "completedAt",
                LocalDateTime.parse(day + "T12:00:00"),
                "score",
                new BigDecimal(score));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> designer(Map<String, Object> report) {
        return ((List<Map<String, Object>>) report.get("designers")).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tasks(Map<String, Object> designer) {
        return (List<Map<String, Object>>) designer.get("tasks");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> designer) {
        return (List<Map<String, Object>>) designer.get("pointItems");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> related(Map<String, Object> designer) {
        return (List<Map<String, Object>>) designer.get("relatedItems");
    }

    private static Map<String, Object> item(List<Map<String, Object>> items, String kind, long id) {
        return items.stream()
                .filter(item ->
                        kind.equals(item.get("kind")) && Long.valueOf(id).equals(item.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private static BigDecimal sum(List<Map<String, Object>> items, String key) {
        return items.stream()
                .map(item -> new BigDecimal(item.get(key).toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int column(Sheet sheet, String label) {
        for (var cell : sheet.getRow(0)) {
            if (label.equals(cell.getStringCellValue())) return cell.getColumnIndex();
        }
        throw new AssertionError("Missing column: " + label);
    }

    private static BigDecimal columnTotal(Sheet sheet, String label) {
        int column = column(sheet, label);
        var result = BigDecimal.ZERO;
        for (int row = 1; row <= sheet.getLastRowNum(); row++) {
            result = result.add(
                    BigDecimal.valueOf(sheet.getRow(row).getCell(column).getNumericCellValue()));
        }
        return result;
    }

    private static void assertPoints(String expected, Number actual) {
        assertNotNull(actual);
        assertEquals(
                0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())), expected + " vs " + actual);
    }
}
