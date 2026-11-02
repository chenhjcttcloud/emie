package com.emie.designpm.recommendation.service;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.VisualAnalysisCache;
import com.emie.designpm.imagelibrary.event.ImageLibraryChangedEvent;
import com.emie.designpm.recommendation.analysis.AnalysisView;
import com.emie.designpm.recommendation.analysis.VisualProfile;
import com.emie.designpm.recommendation.repository.VisualAnalysisCacheRepository;
import com.emie.designpm.recommendation.repository.VisualSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** All entry points require the caller to have checked access to the source file. */
@Service
public class VisualAnalysisService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final VisualAiSettings settings;
    private final VisualAiClient client;
    private final VisualInputReader input;
    private final VisualAnalysisCacheRepository cache;
    private final VisualSourceRepository sources;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(48),
            runnable -> {
                Thread thread = new Thread(runnable, "visual-profile-analysis");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public VisualAnalysisService(
            VisualAiSettings settings,
            VisualAiClient client,
            VisualInputReader input,
            VisualAnalysisCacheRepository cache,
            VisualSourceRepository sources) {
        this.settings = settings;
        this.client = client;
        this.input = input;
        this.cache = cache;
        this.sources = sources;
    }

    public boolean configured() {
        try {
            return settings.current().configured();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public AnalysisView profile(String storedName) {
        return lookup(List.of(storedName), true).get(storedName);
    }

    public AnalysisView peek(String storedName) {
        return lookup(List.of(storedName), false).get(storedName);
    }

    public Map<String, AnalysisView> peekAll(Collection<String> storedNames) {
        return lookup(storedNames, false);
    }

    private Map<String, AnalysisView> lookup(Collection<String> storedNames, boolean enqueue) {
        List<String> names =
                storedNames.stream().filter(Objects::nonNull).distinct().toList();
        Map<String, AnalysisView> result = new LinkedHashMap<>();
        VisualAiSettings.RuntimeConfig config;
        try {
            config = settings.current();
        } catch (IllegalArgumentException e) {
            names.forEach(name -> result.put(name, new AnalysisView("UNCONFIGURED", null, "AI 密钥不可用，请管理员检查配置")));
            return result;
        }
        if (!config.configured()) {
            names.forEach(
                    name -> result.put(name, new AnalysisView("UNCONFIGURED", null, "AI 未启用或未配置密钥，当前使用图档名称和标签匹配")));
            return result;
        }
        Map<String, FileRecord> records = new HashMap<>();
        Map<String, VisualAnalysisCache> cached = new HashMap<>();
        for (int offset = 0; offset < names.size(); offset += 1000) {
            List<String> batch = names.subList(offset, Math.min(offset + 1000, names.size()));
            sources.findByStoredNameIn(batch).forEach(record -> records.putIfAbsent(record.getStoredName(), record));
            cache.findAllById(batch).forEach(row -> cached.put(row.getStoredName(), row));
        }
        for (String name : names) {
            FileRecord record = records.get(name);
            if (record == null || !input.supported(name)) {
                result.put(name, new AnalysisView("UNSUPPORTED", null, "该文件暂不支持图像分析"));
                continue;
            }
            try {
                String fingerprint = input.metadataFingerprint(record, config.fingerprint());
                VisualAnalysisCache row = cached.get(name);
                boolean fresh = row != null && fingerprint.equals(row.getFingerprint());
                if (fresh && "READY".equals(row.getStatus())) {
                    result.put(
                            name,
                            new AnalysisView(
                                    "READY", JSON.readValue(row.getProfileJson(), VisualProfile.class), "视觉特征已缓存"));
                    continue;
                }
                if (fresh
                        && "FAILED".equals(row.getStatus())
                        && (row.getAttempts() >= 3
                                || row.getRetryAfter() != null
                                        && row.getRetryAfter().isAfter(LocalDateTime.now()))) {
                    result.put(name, new AnalysisView("FAILED", null, row.getMessage()));
                    continue;
                }
                boolean accepted = !enqueue || enqueue(record, fingerprint, config);
                result.put(
                        name,
                        new AnalysisView("PENDING", null, accepted ? "视觉特征待分析，当前使用名称和标签匹配" : "分析队列已满，当前使用名称和标签匹配"));
            } catch (Exception e) {
                result.put(name, new AnalysisView("UNSUPPORTED", null, "源文件无法读取或超过分析限制，当前使用名称和标签匹配"));
            }
        }
        return result;
    }

    private boolean enqueue(FileRecord record, String fingerprint, VisualAiSettings.RuntimeConfig config) {
        String key = record.getStoredName() + ":" + fingerprint;
        if (!queued.add(key)) return true;
        try {
            executor.execute(() -> {
                try {
                    analyze(record, fingerprint, config);
                } finally {
                    queued.remove(key);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            queued.remove(key);
            return false;
        }
    }

    private void analyze(FileRecord record, String fingerprint, VisualAiSettings.RuntimeConfig config) {
        FileRecord currentSource = sources.findByStoredNameIn(List.of(record.getStoredName())).stream()
                .findFirst()
                .orElse(null);
        if (currentSource == null
                || !Objects.equals(record.getTargetType(), currentSource.getTargetType())
                || !Objects.equals(record.getTargetId(), currentSource.getTargetId())) return;
        record = currentSource;
        VisualAnalysisCache row = cache.findById(record.getStoredName()).orElseGet(VisualAnalysisCache::new);
        if (!fingerprint.equals(row.getFingerprint())) {
            row = new VisualAnalysisCache();
            row.setAttempts(0);
        }
        row.setStoredName(record.getStoredName());
        row.setFingerprint(fingerprint);
        try {
            VisualAiSettings.RuntimeConfig current = settings.current();
            if (!current.configured() || !current.fingerprint().equals(config.fingerprint())) return;
            if (!fingerprint.equals(input.metadataFingerprint(record, config.fingerprint()))) return;
            String sourceHash = input.fingerprint(record, config.fingerprint());
            VisualProfile profile = client.analyze(config, input.imageData(record));
            if (!sourceHash.equals(input.fingerprint(record, config.fingerprint()))) return;
            row.setFingerprint(input.metadataFingerprint(record, config.fingerprint()));
            row.setProfileJson(JSON.writeValueAsString(profile));
            row.setStatus("READY");
            row.setMessage("视觉特征已缓存");
            row.setRetryAfter(null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            row.setStatus("FAILED");
            row.setProfileJson(null);
            row.setAttempts(row.getAttempts() + 1);
            row.setMessage(
                    row.getAttempts() >= 3 ? "图像分析多次失败，当前使用名称和标签匹配；可更新文件或AI配置后重试" : "图像分析暂未完成，稍后打开推荐会重试；当前使用名称和标签匹配");
            row.setRetryAfter(LocalDateTime.now().plusMinutes(5L * row.getAttempts()));
        }
        row.setUpdatedAt(LocalDateTime.now());
        cache.save(row);
    }

    /** File changes are published only after the library transaction commits; no request waits for analysis. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLibraryChanged(ImageLibraryChangedEvent event) {
        if (!configured()) return;
        try {
            executor.execute(() -> event.storedNames().stream().limit(3).forEach(this::profile));
        } catch (RejectedExecutionException ignored) {
            /* Opening recommendations or indexing can retry. */
        }
    }

    public Map<String, Object> indexStats() {
        List<FileRecord> library = sources.findByTargetTypeOrderByCreatedAtDesc("image_library");
        Set<String> names = library.stream().map(FileRecord::getStoredName).collect(Collectors.toSet());
        Collection<AnalysisView> views = peekAll(names).values();
        long ready = views.stream().filter(row -> "READY".equals(row.status())).count();
        long failed =
                views.stream().filter(row -> "FAILED".equals(row.status())).count();
        long unsupported =
                views.stream().filter(row -> "UNSUPPORTED".equals(row.status())).count();
        return Map.of(
                "total",
                names.size(),
                "ready",
                ready,
                "failed",
                failed,
                "unsupported",
                unsupported,
                "pending",
                Math.max(0, names.size() - ready - failed - unsupported),
                "queued",
                queued.size());
    }

    public Map<String, Object> indexLibrary() {
        if (!configured()) throw new IllegalArgumentException("请先启用AI并配置密钥，再分析图档");
        List<FileRecord> library = sources.findByTargetTypeOrderByCreatedAtDesc("image_library");
        Map<String, AnalysisView> priorViews =
                peekAll(library.stream().map(FileRecord::getStoredName).toList());
        VisualAiSettings.RuntimeConfig config = settings.current();
        int submitted = 0, remaining = 0;
        for (FileRecord record : library) {
            if (!input.supported(record.getStoredName())) continue;
            AnalysisView prior = priorViews.get(record.getStoredName());
            if (prior == null) {
                remaining++;
                continue;
            }
            if ("READY".equals(prior.status())
                    || "FAILED".equals(prior.status())
                    || "UNSUPPORTED".equals(prior.status())) continue;
            if (submitted < 48 && executor.getQueue().remainingCapacity() > 0) {
                try {
                    if (enqueue(record, input.metadataFingerprint(record, config.fingerprint()), config)) submitted++;
                    else remaining++;
                } catch (Exception ignored) {
                    /* Unsupported/missing files are skipped without exposing paths. */
                }
            } else remaining++;
        }
        return Map.of(
                "queued",
                submitted,
                "remaining",
                remaining,
                "indexStats",
                indexStats(),
                "message",
                "本次已提交 " + submitted + " 个文件；其余可在本批完成后继续分析");
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
