package com.emie.designpm;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.util.SecurityUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecurityRegressionTest {

    @Test
    void newPasswordsUseBcryptAndCanBeVerified() {
        String hash = AuthController.hashPassword("test-password");

        assertTrue(hash.startsWith("$2"));
        assertNotEquals(hash, AuthController.hashPassword("test-password"));
    }

    @Test
    void legacyHashIsStableAcrossLocales() {
        assertEquals(AuthController.sha256("测试密码"), AuthController.sha256("测试密码"));
        assertNotEquals(AuthController.sha256("测试密码"), AuthController.sha256("测试密码2"));
    }

    @Test
    void filenamesRejectPathTraversal() {
        assertFalse(SecurityUtil.isValidFileName("../secret.txt"));
        assertFalse(SecurityUtil.isValidFileName("folder/secret.txt"));
        assertFalse(SecurityUtil.isValidFileName("secret.exe"));
        assertTrue(SecurityUtil.isValidFileName("preview.png"));
    }

    @Test
    void attachmentsOnlyAllowSupportedBusinessTypes() {
        assertFalse(SecurityUtil.isValidAttachmentFile("page.html"));
        assertFalse(SecurityUtil.isValidAttachmentFile("script.js"));
        assertFalse(SecurityUtil.isValidAttachmentFile("vector.svg"));
        assertTrue(SecurityUtil.isValidAttachmentFile("brief.docx"));
        assertTrue(SecurityUtil.isValidAttachmentFile("reference.pdf"));
    }
}
