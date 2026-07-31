package com.emie.designpm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionReleaseScriptTest {

    private final Path localScript = Path.of("scripts/release-production.sh");
    private final Path remoteScript = Path.of("scripts/release-production-remote.sh");

    @Test
    void releaseScriptsHaveValidBashSyntax() throws Exception {
        assertBashSyntax(localScript);
        assertBashSyntax(remoteScript);
    }

    @Test
    void candidateIsCreatedBeforeTheRunningContainerIsStopped() throws Exception {
        String script = Files.readString(remoteScript);
        int createCandidate = script.indexOf("docker create \"${create_args[@]}\"");
        int stopCurrent = script.indexOf("docker stop -t 30 \"$APP_CONTAINER\"");

        assertTrue(createCandidate >= 0, "必须先创建候选容器");
        assertTrue(stopCurrent > createCandidate, "旧容器只能在候选容器创建成功后停止");
    }

    @Test
    void deploymentUsesVersionedJarInsteadOfBuildingAnImagePerRelease() throws Exception {
        String script = Files.readString(remoteScript);

        assertTrue(script.contains("dst=/app/app.jar,readonly"));
        assertTrue(script.contains("$DEPLOY_DIR/releases/$target_sha"));
        assertFalse(script.contains("docker commit"));
        assertFalse(script.contains("docker compose build"));
    }

    @Test
    void rollbackStopsAfterRestoringTheOldContainer() throws Exception {
        String script = Files.readString(remoteScript);

        assertTrue(script.contains("trap - ERR"));
        assertTrue(script.contains("exit \"$failure_status\""));
        assertTrue(script.contains("rollback=success"));
        assertTrue(script.contains("sed \"/^$/d\""));
    }

    @Test
    void repeatedReleaseExitsBeforeBuildingOrUploading() throws Exception {
        String script = Files.readString(localScript);
        int alreadyRunningCheck = script.indexOf("[[ \"$target_sha\" == \"$remote_current_sha\" ]]");
        int build = script.indexOf("scripts/mvnw-java21.sh package");

        assertTrue(alreadyRunningCheck >= 0);
        assertTrue(build > alreadyRunningCheck, "已运行的提交不应重复构建和上传");
    }

    @Test
    void remoteScriptOnlyAcceptsTheTargetSpecificIncomingJar() throws Exception {
        String script = Files.readString(remoteScript);

        assertTrue(script.contains(
                "[[ \"$incoming_jar\" == \"$DEPLOY_DIR/incoming/app-$target_sha.jar\" ]]"));
    }

    private void assertBashSyntax(Path script) throws Exception {
        Process process = new ProcessBuilder("bash", "-n", script.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }
}
