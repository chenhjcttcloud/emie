package com.emie.designpm.config;

import com.emie.designpm.service.ProjectExcelImportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 受显式文件路径开关保护的一次性本地导入命令。
 * 用法：java -jar ... --spring.main.web-application-type=none --app.project-import.file=/absolute/file.xlsx
 */
@Component
@ConditionalOnProperty(name = "app.project-import.file")
public class ProjectImportCommand implements CommandLineRunner {
    private final ProjectExcelImportService importService;
    private final ConfigurableApplicationContext context;
    private final String file;
    private final String forceIpName;
    private final String forceIpSubOptions;
    private final boolean ensureIpOption;

    public ProjectImportCommand(ProjectExcelImportService importService, ConfigurableApplicationContext context,
                                @Value("${app.project-import.file}") String file,
                                @Value("${app.project-import.force-ip-name:}") String forceIpName,
                                @Value("${app.project-import.force-ip-sub-options:}") String forceIpSubOptions,
                                @Value("${app.project-import.ensure-ip-option:false}") boolean ensureIpOption) {
        this.importService = importService;
        this.context = context;
        this.file = file;
        this.forceIpName = forceIpName;
        this.forceIpSubOptions = forceIpSubOptions;
        this.ensureIpOption = ensureIpOption;
    }

    @Override
    public void run(String... args) throws Exception {
        ProjectExcelImportService.ImportResult result;
        try (var input = Files.newInputStream(Path.of(file))) {
            result = importService.importWorkbook(input, "system_excel_import", "系统批量导入",
                    new ProjectExcelImportService.ImportOptions(forceIpName,
                            Arrays.stream(forceIpSubOptions.split("、")).toList(), ensureIpOption));
        }
        if (result.errors().isEmpty()) {
            System.out.println("项目 Excel 导入完成：" + result.importedCount() + " 条");
        } else {
            System.err.println("项目 Excel 预校验未通过，未写入任何项目：");
            result.errors().forEach(System.err::println);
        }
        int code = result.errors().isEmpty() ? 0 : 2;
        Thread.ofVirtual().start(() -> System.exit(org.springframework.boot.SpringApplication.exit(context, () -> code)));
    }
}
