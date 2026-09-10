package com.emie.designpm.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 类体积上限：默认 600 行。god class 是改动风险和合并冲突的聚集点。
 *
 * 现存超标类冻结在 {@link #FROZEN_CEILINGS}，各自以"当前行数"为上限——只能变小、
 * 不能变大；拆到 600 行以下后从清单删除。新类一旦超 600 行直接失败。
 */
class ClassSizeCeilingTest {

    private static final int DEFAULT_CEILING = 600;
    private static final Path SRC = Path.of("src/main/java");

    /** file path (相对 src/main/java) -> 允许的最大行数（= 冻结时的实际行数）。 */
    private static final Map<String, Integer> FROZEN_CEILINGS = Map.of(
            "com/emie/designpm/feishu/service/FeishuBaseService.java", 1844,
            "com/emie/designpm/project/service/DefaultSubTaskCommandService.java", 1625,
            "com/emie/designpm/project/controller/ProjectController.java", 741,
            "com/emie/designpm/project/service/ProjectService.java", 1055,
            "com/emie/designpm/admin/service/AdminService.java", 950,
            "com/emie/designpm/sync/service/SyncWorker.java", 675);

    @Test
    void no_class_exceeds_its_line_ceiling() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String rel = SRC.relativize(file).toString().replace('\\', '/');
                long lines = Files.lines(file).count();
                int ceiling = FROZEN_CEILINGS.getOrDefault(rel, DEFAULT_CEILING);
                if (lines > ceiling) {
                    violations.add(rel + " = " + lines + " 行（上限 " + ceiling + "）");
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("以下类超过行数上限，请拆分：\n  " + String.join("\n  ", violations)
                    + "\n（如果这是冻结清单里的类变大了：改动方向反了，应该越拆越小）");
        }
    }
}
