package com.emie.designpm.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 架构护栏：把包结构重构（package-by-feature）的成果、以及分层约束焊进 CI。
 *
 * 有历史欠债的规则不会一刀切失败——用"冻结清单"记录当前已知违规，规则只保证
 * <b>不再新增</b>。清单里的类修好后请从清单删除；想加新条目 = 先过 review 讨论。
 */
class ArchitectureRulesTest {

    private static final String ROOT = "com.emie.designpm";
    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    // ---------------------------------------------------------------------
    // 1. package-by-feature：扁平的 controller / service / repository 包不许再出现
    // ---------------------------------------------------------------------

    @Test
    void controllers_services_repositories_live_in_feature_packages() {
        ArchRule rule = noClasses()
                .that()
                .resideInAnyPackage(
                        ROOT + ".controller", ROOT + ".controller..",
                        ROOT + ".service", ROOT + ".service..",
                        ROOT + ".repository", ROOT + ".repository..")
                .should()
                .beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .orShould()
                .beAnnotatedWith("org.springframework.stereotype.Service")
                .orShould()
                .beAnnotatedWith("org.springframework.stereotype.Component")
                .as("controller/service/repository 必须放在 " + ROOT + ".<domain>.{controller|service|repository}，"
                        + "不得放在扁平的顶层包")
                .allowEmptyShould(true);
        rule.check(production);
    }

    @Test
    void controllers_are_named_and_placed_consistently() {
        classes()
                .that()
                .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should()
                .resideInAPackage(ROOT + ".*.controller")
                .andShould()
                .haveSimpleNameEndingWith("Controller")
                .check(production);
    }

    @Test
    void spring_data_repositories_are_placed_consistently() {
        // ".*.repository" = 恰好一层域名段：com.emie.designpm.<domain>.repository。
        // 扁平的 com.emie.designpm.repository 不匹配 → 直接失败（不依赖 @Repository 注解，
        // Spring Data 的接口本身不带注解也会被这条抓到）。background.repository 合法保留。
        classes()
                .that()
                .areAssignableTo("org.springframework.data.repository.Repository")
                .and()
                .areInterfaces()
                .should()
                .resideInAPackage(ROOT + ".*.repository")
                .check(production);
    }

    // ---------------------------------------------------------------------
    // 2. 分层：不许向上依赖
    // ---------------------------------------------------------------------

    @Test
    void services_do_not_depend_on_controllers() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..service..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + "..controller..")
                .check(production);
    }

    @Test
    void repositories_do_not_depend_on_services_or_controllers() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..repository..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + "..service..", ROOT + "..controller..")
                .check(production);
    }

    @Test
    void entities_do_not_depend_on_controllers() {
        // service 层的例外：entity 通过 @EntityListeners(...SyncListener.class) 的类字面量
        // 引用 sync.service 的 3 个监听器，这是标准 JPA 用法，不算分层违规。
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".entity..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + "..controller..")
                .check(production);
    }

    // ---------------------------------------------------------------------
    // 3. controller 不得直连 repository —— 有 17 个历史违规，冻结，只禁新增
    // ---------------------------------------------------------------------

    /**
     * 现存直连 repository 的 controller。修一个删一个，清零后把规则收紧成硬禁止。
     *
     * 已知局限：这是<b>整类</b>豁免，不是"冻结既有的具体 (controller,repository) 边"。
     * 清单里的 controller 再多连一个 repository 也不会被拦。可接受，因为：
     * (a) 这 17 个都是 P3「controller→repo 解耦」的目标，会被整类移出；
     * (b) 其中的 god class（ProjectController 等）体积已被 ClassSizeCeilingTest 冻结，加不动。
     * 新 controller 直连 repository 仍然会失败——这条规则真正要守的是这个。
     *
     * 拆 god class 时若抽出需要 repository 的读取逻辑，放到 {@code <domain>.service}
     * 做正经 service（如 ProjectViewSupport），不要放在 controller 包里再加进这张清单。
     */
    private static final Set<String> CONTROLLERS_ALLOWED_TO_TOUCH_REPOSITORIES = Set.of(
            ROOT + ".admin.controller.UserController",
            ROOT + ".auth.controller.AuthController",
            ROOT + ".compliance.controller.ComplianceController",
            ROOT + ".dashboard.controller.DashboardController",
            ROOT + ".designrequirement.controller.DesignRequirementController",
            ROOT + ".feishu.controller.FeishuAuthController",
            ROOT + ".feishu.controller.FeishuSyncController",
            ROOT + ".file.controller.FileController",
            ROOT + ".imagelibrary.controller.ImageLibraryController",
            ROOT + ".points.controller.PointsController",
            ROOT + ".project.controller.ProjectController",
            ROOT + ".reference.controller.CategoryController",
            ROOT + ".reference.controller.DepartmentController",
            ROOT + ".reference.controller.IpOptionController",
            ROOT + ".reference.controller.PriceRangeController",
            ROOT + ".system.controller.FaviconController",
            ROOT + ".system.controller.SystemController");

    @Test
    void no_new_controller_depends_on_a_repository() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..controller..")
                .and(new com.tngtech.archunit.base.DescribedPredicate<>("not on the frozen allowlist") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass javaClass) {
                        return !CONTROLLERS_ALLOWED_TO_TOUCH_REPOSITORIES.contains(javaClass.getFullName());
                    }
                })
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + "..repository..")
                .as("controller 不得直连 repository；业务查询应经 service。" + "（"
                        + CONTROLLERS_ALLOWED_TO_TOUCH_REPOSITORIES.size() + " 个历史违规已冻结在清单里）")
                .check(production);
    }

    // ---------------------------------------------------------------------
    // 4. 循环依赖
    // ---------------------------------------------------------------------

    // 分层不成环由上面几条"不向上依赖"的规则保证（services 不依赖 controllers、
    // repositories 不依赖 services/controllers），这里不再单独断言。

    /**
     * 跨业务域循环依赖（project↔points、materialmarket→file/notification/points、
     * file→designrequirement 等）目前 100+ 处，是搬包之后剩下的主要架构债。
     * 需要真正的解耦（下沉共享能力、事件化跨域调用）才能消除。
     * 修完后删掉 @Disabled，让 CI 从此守住。
     */
    @org.junit.jupiter.api.Disabled("基线：跨域循环依赖是已知债，见 docs/adr。解耦后启用")
    @Test
    void feature_domains_are_free_of_cycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(production);
    }
}
