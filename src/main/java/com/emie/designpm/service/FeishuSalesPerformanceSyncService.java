package com.emie.designpm.service;

import com.emie.designpm.entity.MonthlyPerformanceConfig;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.MonthlyPerformanceConfigRepository;
import com.emie.designpm.repository.SystemConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class FeishuSalesPerformanceSyncService {
    private static final Logger log = LoggerFactory.getLogger(FeishuSalesPerformanceSyncService.class);
    private final FeishuBaseService feishu;
    private final MonthlyPerformanceConfigRepository months;
    private final SystemConfigRepository configs;

    public FeishuSalesPerformanceSyncService(FeishuBaseService feishu, MonthlyPerformanceConfigRepository months,
                                             SystemConfigRepository configs) {
        this.feishu = feishu; this.months = months; this.configs = configs;
    }

    @Scheduled(fixedDelayString = "${points.performance.sales-sync-ms:3600000}")
    public void scheduledSync() { if (enabled()) syncCurrentMonth(); }

    @Transactional
    public Map<String, Object> syncCurrentMonth() {
        if (!enabled()) return Map.of("enabled", false, "message", "销售额自动同步未启用");
        try {
            YearMonth month = YearMonth.now();
            List<Map<String, String>> rows = feishu.readSalesRecords();
            Set<String> seen = new HashSet<>();
            double total = 0d; int included = 0; int skipped = 0;
            int statusSkipped = 0, dateSkipped = 0, amountSkipped = 0;
            String statuses = cfg("feishu.sales.validStatuses", "已完成,已回款,完成");
            Set<String> validStatuses = new HashSet<>(Arrays.stream(statuses.split("[,，]")).map(String::trim).filter(s -> !s.isBlank()).toList());
            for (Map<String, String> row : rows) {
                String order = row.getOrDefault("orderId", "");
                if (!order.isBlank() && !seen.add(order)) { skipped++; continue; }
                String status = row.getOrDefault("status", "").trim();
                if (!validStatuses.isEmpty() && !validStatuses.contains(status)) { skipped++; statusSkipped++; continue; }
                if (!inMonth(row.get("date"), month)) { skipped++; dateSkipped++; continue; }
                Double amount = number(row.get("amount"));
                if (amount == null || amount < 0) { skipped++; amountSkipped++; continue; }
                total += amount - Optional.ofNullable(number(row.get("refund"))).orElse(0d);
                included++;
            }
            MonthlyPerformanceConfig config = months.findByMonthKey(month.toString()).orElseGet(MonthlyPerformanceConfig::new);
            config.setMonthKey(month.toString()); config.setSalesAmount(Math.max(0d, Math.round(total * 100d) / 100d));
            if (config.getTargetPoints() == null) config.setTargetPoints(0);
            if (config.getMultiplier() == null) config.setMultiplier(1d);
            config.setSupplyShortage(config.isSupplyShortage());
            months.save(config);
            return Map.of("enabled", true, "month", month.toString(), "salesAmount", config.getSalesAmount(), "included", included,
                    "skipped", skipped, "statusSkipped", statusSkipped, "dateSkipped", dateSkipped, "amountSkipped", amountSkipped);
        } catch (Exception e) {
            log.warn("飞书销售额自动同步失败，将保留上次成功结果: {}", e.getMessage());
            return Map.of("enabled", true, "success", false, "message", "同步失败，已保留上次成功结果");
        }
    }

    public Map<String, Object> updateSalesRecord(String orderId, Map<String, Object> changes) throws Exception {
        return feishu.updateSalesRecord(orderId, changes);
    }

    private boolean enabled() { return "true".equalsIgnoreCase(cfg("feishu.sales.enabled", "false")); }
    private String cfg(String key, String fallback) { return configs.findByConfigKey(key).map(SystemConfig::getConfigValue).filter(v -> !v.isBlank()).orElse(fallback); }
    private Double number(String value) { try { return value == null || value.isBlank() ? null : Double.parseDouble(value.replace(",", "").replace("￥", "").trim()); } catch (Exception e) { return null; } }
    private boolean inMonth(String raw, YearMonth month) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim();
        if (value.length() >= 7 && value.substring(0, 7).equals(month.toString())) return true;
        try {
            long epoch = Long.parseLong(value);
            if (value.length() < 11) epoch *= 1000L;
            LocalDate date = java.time.Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate();
            return YearMonth.from(date).equals(month);
        } catch (Exception ignored) { }
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ofPattern("yyyy.MM.dd"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))) {
            try {
                LocalDate date = formatter.toString().contains("H") ? LocalDateTime.parse(value, formatter).toLocalDate() : LocalDate.parse(value, formatter);
                return YearMonth.from(date).equals(month);
            } catch (DateTimeParseException ignored) { }
        }
        return false;
    }
}
