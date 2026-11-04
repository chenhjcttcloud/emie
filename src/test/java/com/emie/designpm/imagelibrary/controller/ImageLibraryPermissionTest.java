package com.emie.designpm.imagelibrary.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.file.service.FileArchiveService;
import com.emie.designpm.imagelibrary.repository.ImageLibraryItemRepository;
import com.emie.designpm.reference.repository.IpOptionRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ImageLibraryPermissionTest {
    private final ImageLibraryItemRepository items = mock(ImageLibraryItemRepository.class);
    private final FileArchiveService archive = mock(FileArchiveService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final ImageLibraryController controller = new ImageLibraryController(
            items, mock(FileRecordRepository.class), mock(IpOptionRepository.class), archive, permissions);

    @Test
    void administratorWithoutViewPermissionCannotReadOrChangeLibrary() {
        MockHttpServletRequest request = request("admin");
        assertEquals(403, controller.list(request).getStatusCode().value());
        assertEquals(403, controller.downloadAll(1L, request).getStatusCode().value());
        assertEquals(403, controller.create(Map.of(), request).getStatusCode().value());
        assertEquals(
                403, controller.update(1L, Map.of(), request).getStatusCode().value());
        assertEquals(403, controller.delete(1L, request).getStatusCode().value());
        verifyNoInteractions(items, archive);
    }

    @Test
    void authorizedDesignerCanReadButCannotModifyLibrary() {
        MockHttpServletRequest request = request("designer");
        when(permissions.canViewImageLibrary("designer")).thenReturn(true);
        assertEquals(200, controller.list(request).getStatusCode().value());
        assertEquals(404, controller.downloadAll(1L, request).getStatusCode().value());
        assertEquals(
                403, controller.update(1L, Map.of(), request).getStatusCode().value());
    }

    private MockHttpServletRequest request(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("test-user", role, "测试账号"));
        return request;
    }
}
