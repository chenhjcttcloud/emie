package com.emie.designpm.controller;

import com.emie.designpm.service.FileArchiveService;
import com.emie.designpm.service.PermanentFileLinkService;
import com.emie.designpm.util.SecurityUtil;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/files/permanent")
public class PublicFileShareController {
    private final FileArchiveService files;
    private final PermanentFileLinkService links;
    public PublicFileShareController(FileArchiveService files, PermanentFileLinkService links) {
        this.files = files; this.links = links;
    }

    @GetMapping("/{signature}/{fileName}")
    public ResponseEntity<?> download(@PathVariable String signature, @PathVariable String fileName) {
        if (!SecurityUtil.isValidFileName(fileName) || !links.valid(fileName, signature))
            return ResponseEntity.status(403).body("链接无效");
        try {
            Path path = files.resolveFile(fileName);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(fileName, StandardCharsets.UTF_8).build().toString())
                    .contentLength(Files.size(path)).body(new InputStreamResource(Files.newInputStream(path)));
        } catch (Exception e) { return ResponseEntity.notFound().build(); }
    }
}
