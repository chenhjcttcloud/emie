package com.emie.designpm.service;

import com.emie.designpm.entity.IpOption;
import com.emie.designpm.entity.PriceRange;
import com.emie.designpm.entity.ProductCategory;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.IpOptionRepository;
import com.emie.designpm.repository.PriceRangeRepository;
import com.emie.designpm.repository.ProductCategoryRepository;
import com.emie.designpm.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 项目 Excel 导入：先完整预校验，再统一创建，避免半份文件导入成功。
 * 模板约定第 5 行为表头、第 6 行开始为业务数据。
 */
@Service
public class ProjectExcelImportService {
    private static final int MAX_IMPORT_ROWS = 10_000;
    private static final int HEADER_ROW = 4;
    private static final int DATA_ROW = 5;
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.SIMPLIFIED_CHINESE);

    private final UserRepository userRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final PriceRangeRepository priceRangeRepository;
    private final IpOptionRepository ipOptionRepository;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectExcelImportService(UserRepository userRepository,
                                     ProductCategoryRepository productCategoryRepository,
                                     PriceRangeRepository priceRangeRepository,
                                     IpOptionRepository ipOptionRepository,
                                     ProjectService projectService) {
        this.userRepository = userRepository;
        this.productCategoryRepository = productCategoryRepository;
        this.priceRangeRepository = priceRangeRepository;
        this.ipOptionRepository = ipOptionRepository;
        this.projectService = projectService;
    }

    public ImportResult preview(InputStream input) {
        return parseAndValidate(input);
    }

    @Transactional
    public ImportResult importWorkbook(InputStream input, String actorId, String actorName) {
        return importWorkbook(input, actorId, actorName, new ImportOptions("", List.of(), false));
    }

    @Transactional
    public ImportResult importWorkbook(InputStream input, String actorId, String actorName, ImportOptions options) {
        ImportResult result = parseAndValidate(input, options);
        if (!result.errors().isEmpty()) return result;
        if (options.ensureIpOption() && !options.ipName().isBlank()) ensureIpOption(options);

        for (ImportRow row : result.rows()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", row.type());
            body.put("productName", row.productName());
            body.put("plannerId", row.plannerId());
            body.put("salesId", row.salesId());
            body.put("productCategory", row.productCategory());
            body.put("productCategoryNote", row.productCategoryNote());
            body.put("ipName", row.ipName());
            body.put("ipSubOptions", toJsonArray(row.ipSubOptions()));
            body.put("priceRange", row.priceRange());
            body.put("targetMarket", toJsonArray(row.targetMarket()));
            body.put("complianceItems", toJsonArray(row.complianceItems()));
            body.put("deadline", row.deadline());
            body.put("productRequirements", row.productRequirements());
            body.put("description", row.description());
            body.put("referenceImagesJson", "[]");
            body.put("attachmentsJson", "[]");
            body.put("currentRole", "admin");
            body.put("currentUserId", actorId);
            body.put("currentUser", actorName == null || actorName.isBlank() ? "系统批量导入" : actorName);
            projectService.createImportedProject(body);
        }
        return result.withImportedCount(result.rows().size());
    }

    private ImportResult parseAndValidate(InputStream input) {
        return parseAndValidate(input, new ImportOptions("", List.of(), false));
    }

    private ImportResult parseAndValidate(InputStream input, ImportOptions options) {
        List<ImportRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            readSheet(workbook.getSheet("渠道定制单"), "channel_custom", rows, errors, options);
            readSheet(workbook.getSheet("公司常规品"), "regular", rows, errors, options);
            if (workbook.getSheet("渠道定制单") == null && workbook.getSheet("公司常规品") == null) {
                errors.add("未找到“渠道定制单”或“公司常规品”工作表");
            }
        } catch (Exception e) {
            errors.add("无法读取 Excel：" + safeMessage(e));
        }
        return new ImportResult(rows, errors, 0);
    }

    private void readSheet(Sheet sheet, String type, List<ImportRow> rows, List<String> errors, ImportOptions options) {
        if (sheet == null) return;
        Map<String, Integer> headers = headers(sheet.getRow(HEADER_ROW));
        List<String> required = new ArrayList<>(List.of("产品名称", "产品企划姓名", "产品类目", "IP", "参考零售价", "目标市场", "要求完成时间", "产品要求"));
        if ("channel_custom".equals(type)) required.add("需求方（销售）姓名");
        for (String header : required) {
            if (!headers.containsKey(header)) errors.add(sheet.getSheetName() + "缺少表头：" + header);
        }
        if (!errors.isEmpty()) return;

        for (int index = DATA_ROW; index <= sheet.getLastRowNum(); index++) {
            if (rows.size() >= MAX_IMPORT_ROWS) {
                errors.add("Excel 导入最多支持 " + MAX_IMPORT_ROWS + " 条数据");
                break;
            }
            Row excelRow = sheet.getRow(index);
            if (isBlank(excelRow)) continue;
            int line = index + 1;
            String productName = value(excelRow, headers, "产品名称");
            if (productName.isBlank()) {
                errors.add(location(sheet, line) + "产品名称不能为空");
                continue;
            }
            String plannerName = value(excelRow, headers, "产品企划姓名");
            String salesName = value(excelRow, headers, "需求方（销售）姓名");
            String category = value(excelRow, headers, "产品类目");
            String categoryNote = value(excelRow, headers, "其他类目说明");
            String ipName = options.ipName().isBlank() ? value(excelRow, headers, "IP") : options.ipName();
            List<String> ipSubOptions = options.ipSubOptions().isEmpty()
                    ? split(value(excelRow, headers, "二级IP选项")) : options.ipSubOptions();
            String priceRange = resolvePriceRange(value(excelRow, headers, "参考零售价"));
            List<String> targetMarket = split(value(excelRow, headers, "目标市场"));
            List<String> complianceItems = split(value(excelRow, headers, "合规处罚"));
            String deadline = dateValue(excelRow, headers.get("要求完成时间"));
            String requirements = value(excelRow, headers, "产品要求");
            String description = value(excelRow, headers, "细节描述");

            validateRequired(sheet, line, errors, "产品企划姓名", plannerName);
            if ("channel_custom".equals(type)) validateRequired(sheet, line, errors, "需求方（销售）姓名", salesName);
            validateRequired(sheet, line, errors, "产品类目", category);
            validateRequired(sheet, line, errors, "IP", ipName);
            validateRequired(sheet, line, errors, "参考零售价", priceRange);
            if (targetMarket.isEmpty()) errors.add(location(sheet, line) + "目标市场不能为空");
            validateRequired(sheet, line, errors, "要求完成时间", deadline);
            validateRequired(sheet, line, errors, "产品要求", requirements);

            String plannerId = validateUser(sheet, line, errors, plannerName, "planner", "产品企划");
            String salesId = "channel_custom".equals(type)
                    ? validateUser(sheet, line, errors, salesName, "sales", "销售") : null;
            validateCategory(sheet, line, errors, category);
            validatePriceRange(sheet, line, errors, priceRange);
            validateIp(sheet, line, errors, ipName, ipSubOptions, options.ensureIpOption());
            validateMarkets(sheet, line, errors, targetMarket);
            validateDeadline(sheet, line, errors, deadline);

            rows.add(new ImportRow(type, sheet.getSheetName(), line, productName, plannerId, salesId,
                    category, categoryNote, ipName, ipSubOptions, priceRange, targetMarket, complianceItems,
                    deadline, requirements, description));
        }
    }

    private String validateUser(Sheet sheet, int line, List<String> errors, String name, String role, String label) {
        if (name.isBlank()) return "";
        List<User> users = userRepository.findByName(name);
        List<User> matching = users.stream().filter(user -> role.equals(user.getRole()) && !"disabled".equals(user.getStatus())).toList();
        if (matching.size() != 1) {
            errors.add(location(sheet, line) + label + "“" + name + "”未匹配到唯一有效账号");
            return "";
        }
        return matching.getFirst().getUserId();
    }

    private void validateCategory(Sheet sheet, int line, List<String> errors, String name) {
        if (!name.isBlank() && productCategoryRepository.findByName(name).filter(ProductCategory::getActive).isEmpty()) {
            errors.add(location(sheet, line) + "产品类目“" + name + "”不存在或已停用");
        }
    }

    private void validatePriceRange(Sheet sheet, int line, List<String> errors, String name) {
        if (!name.isBlank() && priceRangeRepository.findByName(name).filter(PriceRange::getActive).isEmpty()) {
            errors.add(location(sheet, line) + "参考零售价“" + name + "”不存在或已停用");
        }
    }

    private void validateIp(Sheet sheet, int line, List<String> errors, String name, List<String> subOptions, boolean allowCreate) {
        Optional<IpOption> ip = ipOptionRepository.findByName(name).filter(IpOption::getActive);
        if (!name.isBlank() && ip.isEmpty() && !allowCreate) {
            errors.add(location(sheet, line) + "IP“" + name + "”不存在或已停用");
        } else if (ip.isPresent() && ip.get().getSubOptionsJson() != null && !ip.get().getSubOptionsJson().isBlank() && subOptions.isEmpty()) {
            errors.add(location(sheet, line) + "IP“" + name + "”需要填写“二级IP选项”；当前可选项：" + ip.get().getSubOptionsJson());
        }
    }

    private void validateMarkets(Sheet sheet, int line, List<String> errors, List<String> markets) {
        if (markets.stream().anyMatch(value -> !Set.of("国内", "海外").contains(value))) {
            errors.add(location(sheet, line) + "目标市场只能填写“国内”“海外”，多选使用“、”分隔");
        }
    }

    private void validateDeadline(Sheet sheet, int line, List<String> errors, String deadline) {
        if (deadline.isBlank()) return;
        try {
            LocalDate.parse(deadline, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            errors.add(location(sheet, line) + "要求完成时间必须为 yyyy-MM-dd");
        }
    }

    /** 兼容历史 Excel 的数值零售价，自动映射到当前已启用的价格区间。 */
    private String resolvePriceRange(String source) {
        if (source == null || source.isBlank() || priceRangeRepository.findByName(source).filter(PriceRange::getActive).isPresent()) return source;
        try {
            double price = Double.parseDouble(source.replace("元", "").trim());
            return priceRangeRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                    .filter(range -> range.getName().matches("\\d+元以下"))
                    .filter(range -> price <= Double.parseDouble(range.getName().replace("元以下", "")))
                    .map(PriceRange::getName).findFirst()
                    .orElseGet(() -> priceRangeRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                            .map(PriceRange::getName).filter(name -> name.matches("\\d+元以上")).findFirst().orElse(source));
        } catch (NumberFormatException ignored) {
            return source;
        }
    }

    private void ensureIpOption(ImportOptions options) {
        IpOption ip = ipOptionRepository.findByName(options.ipName()).orElseGet(() -> {
            int nextOrder = ipOptionRepository.findTopByOrderBySortOrderDesc()
                    .map(i -> i.getSortOrder() == null ? 1 : i.getSortOrder() + 1).orElse(1);
            IpOption created = new IpOption(options.ipName(), nextOrder);
            created.setActive(true);
            return created;
        });
        List<String> current = new ArrayList<>();
        if (ip.getSubOptionsJson() != null && !ip.getSubOptionsJson().isBlank()) {
            try { current.addAll(objectMapper.readValue(ip.getSubOptionsJson(), new TypeReference<List<String>>() {})); }
            catch (Exception ignored) { /* 配置将在本次统一修复为有效 JSON */ }
        }
        for (String option : options.ipSubOptions()) if (!current.contains(option)) current.add(option);
        try { ip.setSubOptionsJson(objectMapper.writeValueAsString(current)); }
        catch (Exception e) { throw new IllegalStateException("二级 IP 配置保存失败", e); }
        ip.setSubOptionSelectionMode("multiple");
        ipOptionRepository.save(ip);
    }

    private Map<String, Integer> headers(Row row) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (row == null) return result;
        for (Cell cell : row) {
            String value = FORMATTER.formatCellValue(cell).trim();
            if (!value.isBlank()) result.put(value, cell.getColumnIndex());
        }
        return result;
    }

    private boolean isBlank(Row row) {
        if (row == null) return true;
        for (Cell cell : row) if (!FORMATTER.formatCellValue(cell).trim().isBlank()) return false;
        return true;
    }

    private String value(Row row, Map<String, Integer> headers, String header) {
        Integer index = headers.get(header);
        if (index == null || row == null) return "";
        Cell cell = row.getCell(index);
        return cell == null ? "" : FORMATTER.formatCellValue(cell).trim();
    }

    private String dateValue(Row row, Integer index) {
        if (index == null || row == null || row.getCell(index) == null) return "";
        Cell cell = row.getCell(index);
        if (DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        return FORMATTER.formatCellValue(cell).trim();
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("、")).map(String::trim).filter(part -> !part.isBlank()).distinct().toList();
    }

    private void validateRequired(Sheet sheet, int line, List<String> errors, String field, String value) {
        if (value == null || value.isBlank()) errors.add(location(sheet, line) + field + "不能为空");
    }

    private String location(Sheet sheet, int line) { return "【" + sheet.getSheetName() + "第" + line + "行】"; }
    private String toJsonArray(List<String> values) {
        if (values.isEmpty()) return "";
        try { return objectMapper.writeValueAsString(values); }
        catch (Exception e) { throw new IllegalStateException("导入字段 JSON 序列化失败", e); }
    }
    private String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    public record ImportRow(String type, String sheetName, int rowNumber, String productName, String plannerId,
                            String salesId, String productCategory, String productCategoryNote, String ipName,
                            List<String> ipSubOptions, String priceRange, List<String> targetMarket, List<String> complianceItems,
                            String deadline, String productRequirements, String description) { }

    public record ImportResult(List<ImportRow> rows, List<String> errors, int importedCount) {
        ImportResult withImportedCount(int count) { return new ImportResult(rows, errors, count); }
    }

    public record ImportOptions(String ipName, List<String> ipSubOptions, boolean ensureIpOption) {
        public ImportOptions {
            ipName = ipName == null ? "" : ipName.trim();
            ipSubOptions = ipSubOptions == null ? List.of() : ipSubOptions.stream().filter(Objects::nonNull)
                    .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        }
    }
}
