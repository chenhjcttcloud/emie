package com.emie.designpm.service;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.SystemConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.attribute.FileTime;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class FileArchiveService {

    private static final Logger log = LoggerFactory.getLogger(FileArchiveService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileRecordRepository fileRecordRepository;
    private final SystemConfigRepository configRepository;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    /** 热数据保留天数 */
    private static final int HOT_RETENTION_DAYS = 90;

    /** 恢复缓存上限（字节） */
    private static final long RESTORE_CACHE_MAX_BYTES = 2L * 1024 * 1024 * 1024; // 2GB

    private Path uploadPath;
    private Path restoreCachePath;

    public FileArchiveService(FileRecordRepository fileRecordRepository,
                              SystemConfigRepository configRepository) {
        this.fileRecordRepository = fileRecordRepository;
        this.configRepository = configRepository;
    }

    @PostConstruct
    public void init() {
        uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        restoreCachePath = uploadPath.resolve("restore-cache");
        try {
            Files.createDirectories(uploadPath);
            Files.createDirectories(restoreCachePath);
        } catch (IOException e) {
            log.error("无法创建恢复缓存目录", e);
        }
    }

    // ==================== 上传时记录 ====================

    /** 上传成功后记录文件信息 */
    @Transactional
    public FileRecord recordUpload(String storedName, String originalName,
                                   long fileSize, String mimeType,
                                   String targetType, Long targetId, String ownerUserId) {
        FileRecord record = FileRecord.builder()
                .storedName(storedName)
                .originalName(originalName)
                .fileSize(fileSize)
                .mimeType(mimeType)
                .targetType(targetType)
                .targetId(targetId)
                .ownerUserId(ownerUserId)
                .storageTier("local")
                .createdAt(LocalDateTime.now())
                .build();
        return fileRecordRepository.save(record);
    }

    /** 将文件记录绑定到业务对象 */
    @Transactional
    public void bindFilesFromJson(String json, String targetType, Long targetId) {
        if (targetId == null || json == null || json.isBlank()) {
            return;
        }
        try {
            List<Map<String, Object>> files = MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> file : files) {
                String storedName = file.get("storedName") instanceof String s ? s : extractStoredName(file.get("url"));
                if (storedName == null || storedName.isBlank()) {
                    continue;
                }
                fileRecordRepository.findByStoredName(storedName).ifPresent(record -> {
                    record.setTargetType(targetType);
                    record.setTargetId(targetId);
                    fileRecordRepository.save(record);
                });
            }
        } catch (Exception e) {
            log.warn("绑定文件记录失败 targetType={} targetId={}", targetType, targetId, e);
        }
    }

    // ==================== 定时归档 ====================

    /** 每日凌晨 3:00 执行归档 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledArchive() {
        log.info("开始定时归档...");
        try {
            if (!isNasEnabled()) {
                log.info("NAS 未配置，跳过归档");
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusDays(HOT_RETENTION_DAYS);
            List<FileRecord> toArchive = fileRecordRepository
                    .findByStorageTierAndCreatedAtBefore("local", cutoff);

            log.info("找到 {} 个文件需要归档", toArchive.size());
            for (FileRecord record : toArchive) {
                try {
                    archiveSingleFile(record);
                } catch (Exception e) {
                    log.error("归档失败 storedName={}", record.getStoredName(), e);
                }
            }
            log.info("定时归档完成");
        } catch (Exception e) {
            log.error("定时归档异常", e);
        }
    }

    /** 归档单个文件 */
    @Transactional
    public void archiveSingleFile(FileRecord record) throws Exception {
        Path sourceFile = findExistingLocalFile(record.getStoredName());
        if (sourceFile == null) {
            if (record.getArchivePath() != null && !record.getArchivePath().isBlank()) {
                record.setStorageTier("archived");
                record.setArchivedAt(LocalDateTime.now());
                fileRecordRepository.save(record);
                log.warn("本地源文件缺失，但已有归档副本，直接回写归档状态: {}", record.getStoredName());
                return;
            }
            throw new FileNotFoundException("归档失败，源文件不存在: " + record.getStoredName());
        }

        if (sourceFile.startsWith(restoreCachePath) && record.getArchivePath() != null && !record.getArchivePath().isBlank()) {
            Files.deleteIfExists(sourceFile);
            record.setStorageTier("archived");
            record.setArchivedAt(LocalDateTime.now());
            fileRecordRepository.save(record);
            log.info("恢复缓存文件已收回归档状态: {}", record.getStoredName());
            return;
        }

        // 1. 压缩到临时文件
        Path gzFile = Files.createTempFile("archive_", ".gz");
        try {
            compressFile(sourceFile, gzFile);

            // 2. 构建 NAS 路径: YYYY/MM/storedName.gz
            String monthPath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
            String nasRemotePath = monthPath + "/" + record.getStoredName() + ".gz";

            // 3. 推送到 NAS
            String nasTarget = getNasPath() + "/" + nasRemotePath;
            sftpUpload(gzFile, nasTarget);

            // 4. 更新 DB
            record.setStorageTier("archived");
            record.setArchivePath(nasTarget);
            record.setArchiveSize(Files.size(gzFile));
            record.setArchivedAt(LocalDateTime.now());
            fileRecordRepository.save(record);

            // 5. 删除本地源文件
            Files.deleteIfExists(sourceFile);

            log.info("归档成功: {} -> {} ({} bytes -> {} bytes)",
                    record.getStoredName(), nasTarget, record.getFileSize(), record.getArchiveSize());

        } finally {
            Files.deleteIfExists(gzFile);
        }
    }

    // ==================== 文件恢复 ====================

    /** 根据 storedName 获取文件（自动判断层级） */
    public Path resolveFile(String storedName) throws Exception {
        Optional<FileRecord> opt = fileRecordRepository.findByStoredName(storedName);

        // 数据库从其他环境同步后，storageTier 可能仍是 archived，但文件已经随上传目录
        // 一起落到本机。只要本地文件真实存在，就应优先使用，避免无意义地依赖 NAS。
        Path existingLocalFile = findExistingLocalFile(storedName);
        if (existingLocalFile != null) {
            return touchIfRestored(existingLocalFile);
        }

        if (opt.isEmpty()) {
            // 无记录则直接从磁盘返回（兼容旧数据）
            throw new FileNotFoundException("文件不存在: " + storedName);
        }

        FileRecord record = opt.get();

        if ("local".equals(record.getStorageTier())) {
            // 本地文件丢失但标记为 local → 尝试从 NAS 恢复
            log.warn("本地文件丢失但标记为 local，尝试从归档恢复: {}", storedName);
        }

        if ("archived".equals(record.getStorageTier()) || "local".equals(record.getStorageTier())) {
            return restoreFromNas(record);
        }

        if ("restoring".equals(record.getStorageTier())) {
            throw new Exception("文件正在恢复中，请稍后重试");
        }

        throw new FileNotFoundException("文件不可用: " + storedName);
    }

    /** 从 NAS 恢复到本地缓存 */
    private synchronized Path restoreFromNas(FileRecord record) throws Exception {
        // 设置恢复中状态
        if (!"restoring".equals(record.getStorageTier())) {
            record.setStorageTier("restoring");
            fileRecordRepository.save(record);
        }

        try {
            String archivePath = record.getArchivePath();
            if (archivePath == null || archivePath.isBlank()) {
                throw new Exception("归档路径为空，无法恢复");
            }

            // 1. 从 NAS scp 拉回压缩文件到临时目录
            Path tempGz = restoreCachePath.resolve(record.getStoredName() + ".restoring.gz").normalize();
            sftpDownload(archivePath, tempGz);

            // 2. 解压到缓存目录
            Path restoredPath = restoreCachePath.resolve(record.getStoredName()).normalize();
            try (GZIPInputStream gzis = new GZIPInputStream(new FileInputStream(tempGz.toFile()));
                 FileOutputStream fos = new FileOutputStream(restoredPath.toFile())) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = gzis.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
            }

            // 3. 更新状态
            record.setStorageTier("local");
            record.setArchivedAt(null);
            fileRecordRepository.save(record);

            // 4. 清理临时压缩文件
            Files.deleteIfExists(tempGz);

            // 5. LRU 清理缓存目录
            evictRestoreCacheIfNeeded();

            log.info("文件恢复成功: {} -> {}", record.getStoredName(), restoredPath);
            return restoredPath;

        } catch (Exception e) {
            // 恢复失败，恢复状态为 archived
            record.setStorageTier("archived");
            fileRecordRepository.save(record);
            throw e;
        }
    }

    // ==================== 缓存淘汰 ====================

    /** LRU 淘汰：缓存超限时删除最久未访问的文件 */
    private void evictRestoreCacheIfNeeded() throws IOException {
        long totalSize = 0;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(restoreCachePath)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                if (p.toString().endsWith(".gz")) continue; // 跳过临时 gz
                totalSize += Files.size(p);
                files.add(p);
            }
        }
        if (totalSize <= RESTORE_CACHE_MAX_BYTES) return;

        // 按最后修改时间排序，删除最旧的
        files.sort(Comparator.comparingLong(f -> f.toFile().lastModified()));
        for (Path f : files) {
            if (totalSize <= RESTORE_CACHE_MAX_BYTES) break;
            long sz = Files.size(f);
            Files.deleteIfExists(f);
            totalSize -= sz;
            log.info("缓存淘汰: {} ({} bytes)", f.getFileName(), sz);
        }
    }

    // ==================== 工具方法 ====================

    /** GZIP 压缩文件 */
    private void compressFile(Path source, Path target) throws IOException {
        try (FileInputStream fis = new FileInputStream(source.toFile());
             GZIPOutputStream gzos = new GZIPOutputStream(new FileOutputStream(target.toFile()))) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                gzos.write(buf, 0, len);
            }
        }
    }

    /** SFTP 上传文件到 NAS */
    private void sftpUpload(Path localPath, String remotePath) throws Exception {
        try (SftpContext context = openSftpContext()) {
            ensureRemoteDirectories(context.channel(), remotePath);
            context.channel().put(localPath.toString(), remotePath);
        } catch (SftpException e) {
            throw new Exception("上传 NAS 失败: " + e.getMessage(), e);
        }
    }

    /** SFTP 从 NAS 下载文件 */
    private void sftpDownload(String remotePath, Path localPath) throws Exception {
        Files.createDirectories(localPath.getParent());
        try (SftpContext context = openSftpContext()) {
            context.channel().get(remotePath, localPath.toString());
        } catch (SftpException e) {
            throw new Exception("从 NAS 下载失败: " + e.getMessage(), e);
        }
    }

    private SftpContext openSftpContext() throws Exception {
        String nasHost = getNasConfig("nas.host");
        String nasUser = getNasConfig("nas.user");
        String nasPassword = getNasConfig("nas.password");
        if (nasHost.isBlank() || nasUser.isBlank() || nasPassword.isBlank() || getNasPath().isBlank()) {
            throw new Exception("NAS 配置不完整");
        }

        JSch jsch = new JSch();
        Session session = jsch.getSession(nasUser, nasHost, getNasPort());
        session.setPassword(nasPassword);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.connect(10_000);

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(10_000);
        return new SftpContext(session, channel);
    }

    private void ensureRemoteDirectories(ChannelSftp channel, String remotePath) throws SftpException {
        String normalized = remotePath.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }
        String dir = normalized.substring(0, lastSlash);
        String current = normalized.startsWith("/") ? "/" : "";
        for (String part : dir.split("/")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            current = current.endsWith("/") || current.isEmpty() ? current + part : current + "/" + part;
            try {
                channel.stat(current);
            } catch (SftpException e) {
                channel.mkdir(current);
            }
        }
    }

    // ==================== 配置管理 ====================

    private boolean isNasEnabled() {
        return "true".equals(getNasConfig("nas.enabled"));
    }

    private String getNasConfig(String key) {
        return configRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue).orElse("");
    }

    private String getNasPath() {
        return getNasConfig("nas.path");
    }

    private int getNasPort() {
        try {
            return Integer.parseInt(getNasConfig("nas.port"));
        } catch (Exception e) {
            return 22;
        }
    }

    private Path findExistingLocalFile(String storedName) {
        Path localPath = uploadPath.resolve(storedName).normalize();
        if (localPath.startsWith(uploadPath) && Files.exists(localPath)) {
            return localPath;
        }
        Path restoredPath = restoreCachePath.resolve(storedName).normalize();
        if (restoredPath.startsWith(restoreCachePath) && Files.exists(restoredPath)) {
            return restoredPath;
        }
        return null;
    }

    private Path touchIfRestored(Path path) {
        if (path != null && path.startsWith(restoreCachePath)) {
            try {
                Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException e) {
                log.debug("刷新恢复缓存访问时间失败: {}", path, e);
            }
        }
        return path;
    }

    private String extractStoredName(Object urlRaw) {
        if (!(urlRaw instanceof String url) || url.isBlank()) {
            return null;
        }
        String normalized = url.split("\\?")[0];
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private record SftpContext(Session session, ChannelSftp channel) implements AutoCloseable {
        @Override
        public void close() {
            try {
                if (channel != null && channel.isConnected()) {
                    channel.disconnect();
                }
            } finally {
                if (session != null && session.isConnected()) {
                    session.disconnect();
                }
            }
        }
    }

    // ==================== 管理端接口 ====================

    /** 获取存储统计 */
    public Map<String, Object> getStorageStats() throws IOException {
        long localSize = 0;
        long localCount = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(uploadPath)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && !p.startsWith(restoreCachePath)) {
                    localSize += Files.size(p);
                    localCount++;
                }
            }
        }

        long cacheSize = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(restoreCachePath)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) cacheSize += Files.size(p);
            }
        }

        long archivedCount = fileRecordRepository.countByStorageTier("archived");
        long totalDisk = new File("/").getTotalSpace();
        long freeDisk = new File("/").getFreeSpace();
        long usedDisk = totalDisk - freeDisk;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("localFileCount", localCount);
        stats.put("localSizeBytes", localSize);
        stats.put("cacheSizeBytes", cacheSize);
        stats.put("archivedCount", archivedCount);
        stats.put("diskTotalBytes", totalDisk);
        stats.put("diskUsedBytes", usedDisk);
        stats.put("diskFreeBytes", freeDisk);
        stats.put("nasEnabled", isNasEnabled());
        stats.put("nasHost", getNasConfig("nas.host"));
        return stats;
    }

    /** 获取已归档文件列表 */
    public List<Map<String, Object>> getArchivedFiles() {
        return fileRecordRepository.findByStorageTierOrderByCreatedAtDesc("archived")
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("storedName", r.getStoredName());
                    m.put("originalName", r.getOriginalName());
                    m.put("fileSize", r.getFileSize());
                    m.put("archiveSize", r.getArchiveSize());
                    m.put("archivePath", r.getArchivePath());
                    m.put("archivedAt", r.getArchivedAt() != null ? r.getArchivedAt().toString() : "");
                    m.put("createdAt", r.getCreatedAt().toString());
                    return m;
                }).collect(Collectors.toList());
    }

    /** 手动归档所有符合条件文件 */
    @Transactional
    public Map<String, Object> manualArchive() {
        Map<String, Object> result = new LinkedHashMap<>();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(HOT_RETENTION_DAYS);
        List<FileRecord> toArchive = fileRecordRepository
                .findByStorageTierAndCreatedAtBefore("local", cutoff);
        int success = 0, fail = 0;
        for (FileRecord record : toArchive) {
            try {
                archiveSingleFile(record);
                success++;
            } catch (Exception e) {
                log.error("手动归档失败: {}", record.getStoredName(), e);
                fail++;
            }
        }
        result.put("success", success);
        result.put("fail", fail);
        result.put("total", toArchive.size());
        return result;
    }

    /** 恢复指定文件到本地 */
    @Transactional
    public void restoreFile(Long fileId) throws Exception {
        FileRecord record = fileRecordRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件记录不存在"));
        if (!"archived".equals(record.getStorageTier())) {
            throw new IllegalArgumentException("该文件不在归档状态");
        }
        restoreFromNas(record);
    }
}
