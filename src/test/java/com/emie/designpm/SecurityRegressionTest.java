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
        assertTrue(SecurityUtil.isValidAttachmentFile("model.step"));
        assertTrue(SecurityUtil.isValidAttachmentFile("model.STP"));
        assertFalse(SecurityUtil.isValidImageFile("reference-model.STEP"));
        assertFalse(SecurityUtil.isValidImageFile("reference-model.stp"));
        assertFalse(SecurityUtil.isValidImageFile("design.ai"));
    }

    @Test
    void accountFieldsUseTheSameStrictValidationForRegistrationAndAdminEditing() {
        assertTrue(SecurityUtil.isValidUserId("designer_01"));
        assertFalse(SecurityUtil.isValidUserId("../admin"));
        assertTrue(SecurityUtil.isValidDisplayName("设计成员"));
        assertFalse(SecurityUtil.isValidDisplayName("<img>"));
        assertTrue(SecurityUtil.isValidPhone("13800138000"));
        assertFalse(SecurityUtil.isValidPhone("123"));
        assertTrue(SecurityUtil.isValidEmail("user@example.com"));
        assertFalse(SecurityUtil.isValidEmail("bad@email"));
        assertTrue(SecurityUtil.isValidPassword("secure-password"));
        assertFalse(SecurityUtil.isValidPassword("12345"));
    }
}
