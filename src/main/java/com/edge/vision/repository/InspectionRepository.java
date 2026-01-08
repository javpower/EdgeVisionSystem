package com.edge.vision.repository;

import com.edge.vision.model.InspectionEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Repository
public class InspectionRepository {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, (JsonSerializer<LocalDateTime>) (src, type, context) ->
                    new com.google.gson.JsonPrimitive(src.format(ISO_FORMATTER)))
            .registerTypeAdapter(LocalDateTime.class, (JsonDeserializer<LocalDateTime>) (json, type, context) ->
                    LocalDateTime.parse(json.getAsString(), ISO_FORMATTER))
            .create();

    @Value("${edge-vision.system.save-local:true}")
    private boolean saveLocal;

    private Path dataDir;
    private Path recordsDir;      // 按日期分文件存储: records/2024-01-15.jsonl
    private Path idIndexFile;     // ID -> 日期映射索引: id_index.txt (格式: id,日期)
    private Path batchIndexDir;   // 批次索引: batch_index/

    @PostConstruct
    public void init() {
        if (saveLocal) {
            dataDir = Paths.get("data");
            recordsDir = dataDir.resolve("records");
            batchIndexDir = dataDir.resolve("batch_index");
            idIndexFile = dataDir.resolve("id_index.txt");

            try {
                Files.createDirectories(dataDir);
                Files.createDirectories(recordsDir);
                Files.createDirectories(batchIndexDir);
                if (!Files.exists(idIndexFile)) {
                    Files.createFile(idIndexFile);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize data store", e);
            }
        }
    }

    /**
     * 获取指定日期的记录文件路径
     */
    private Path getRecordsFileForDate(LocalDate date) {
        return recordsDir.resolve(date.toString() + ".jsonl");
    }

    /**
     * 获取当前时间的记录文件路径
     */
    private Path getCurrentRecordsFile() {
        return getRecordsFileForDate(LocalDate.now());
    }

    /**
     * 插入记录（追加到对应日期的文件）
     */
    public void insert(InspectionEntity entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
        }

        if (saveLocal) {
            appendToFile(entity);
            updateIdIndex(entity);
            updateBatchIndex(entity);
        }
    }

    private void appendToFile(InspectionEntity entity) {
        try {
            LocalDate date = entity.getTimestamp() != null
                    ? entity.getTimestamp().toLocalDate()
                    : LocalDate.now();
            Path targetFile = getRecordsFileForDate(date);

            String json = gson.toJson(entity);
            try (BufferedWriter writer = Files.newBufferedWriter(targetFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(json);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write to records file", e);
        }
    }

    /**
     * 更新 ID 索引（id -> 日期映射）
     */
    private void updateIdIndex(InspectionEntity entity) {
        try {
            LocalDate date = entity.getTimestamp() != null
                    ? entity.getTimestamp().toLocalDate()
                    : LocalDate.now();
            // 格式: id,日期
            String indexEntry = entity.getId() + "," + date + "\n";
            Files.writeString(idIndexFile, indexEntry,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 索引更新失败不影响主流程
        }
    }

    /**
     * 更新批次索引
     */
    private void updateBatchIndex(InspectionEntity entity) {
        if (entity.getBatchId() == null) {
            return;
        }
        try {
            LocalDate date = entity.getTimestamp() != null
                    ? entity.getTimestamp().toLocalDate()
                    : LocalDate.now();
            // 格式: id,日期
            Path batchIndexFile = batchIndexDir.resolve(entity.getBatchId() + ".txt");
            String indexEntry = entity.getId() + "," + date + "\n";
            Files.writeString(batchIndexFile, indexEntry,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 索引更新失败不影响主流程
        }
    }

    /**
     * 根据 ID 查找（使用 id_index 快速定位日期文件）
     */
    public Optional<InspectionEntity> findById(String id) {
        if (!saveLocal) {
            return Optional.empty();
        }

        // 从 id_index 查找记录所在的日期
        Optional<LocalDate> dateOpt = findDateById(id);
        if (dateOpt.isEmpty()) {
            return Optional.empty();
        }

        Path targetFile = getRecordsFileForDate(dateOpt.get());
        if (!Files.exists(targetFile)) {
            return Optional.empty();
        }

        try (Stream<String> lines = Files.lines(targetFile)) {
            return lines
                    .filter(line -> !line.trim().isEmpty())
                    .map(line -> {
                        try {
                            return gson.fromJson(line, InspectionEntity.class);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(entity -> entity != null && id.equals(entity.getId()))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 从 id_index 中查找 ID 对应的日期
     */
    private Optional<LocalDate> findDateById(String id) {
        if (!Files.exists(idIndexFile)) {
            return Optional.empty();
        }

        try (Stream<String> lines = Files.lines(idIndexFile)) {
            return lines
                    .filter(line -> !line.trim().isEmpty())
                    .map(line -> line.split(","))
                    .filter(parts -> parts.length == 2 && id.equals(parts[0]))
                    .map(parts -> LocalDate.parse(parts[1]))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 查找所有记录（按日期倒序，带限制）
     */
    public List<InspectionEntity> findAll() {
        return findAll(1000);  // 默认最多返回1000条
    }

    public List<InspectionEntity> findAll(int limit) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        // 获取所有日期文件，按日期倒序
        try (Stream<Path> files = Files.list(recordsDir)) {
            List<Path> dateFiles = files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted(Comparator.reverseOrder())  // 文件名本身就是日期，倒序排列
                    .collect(Collectors.toList());

            List<InspectionEntity> results = new ArrayList<>();
            for (Path file : dateFiles) {
                if (results.size() >= limit) break;

                try (Stream<String> lines = Files.lines(file)) {
                    List<InspectionEntity> batch = lines
                            .filter(line -> !line.trim().isEmpty())
                            .map(line -> {
                                try {
                                    return gson.fromJson(line, InspectionEntity.class);
                                } catch (Exception e) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    int needed = limit - results.size();
                    if (batch.size() <= needed) {
                        results.addAll(batch);
                    } else {
                        // 取该文件中最新的 needed 条记录（文件末尾是最新）
                        results.addAll(batch.subList(Math.max(0, batch.size() - needed), batch.size()));
                    }
                }
            }
            return results;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * 根据日期查找（直接定位到对应日期文件）
     */
    public List<InspectionEntity> findByDate(LocalDate date) {
        if (!saveLocal) {
            return Collections.emptyList();
        }

        Path targetFile = getRecordsFileForDate(date);
        if (!Files.exists(targetFile)) {
            return Collections.emptyList();
        }

        try (Stream<String> lines = Files.lines(targetFile)) {
            return lines
                    .filter(line -> !line.trim().isEmpty())
                    .map(line -> {
                        try {
                            return gson.fromJson(line, InspectionEntity.class);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * 根据批次 ID 查找（使用批次索引）
     */
    public List<InspectionEntity> findByBatchId(String batchId) {
        if (!saveLocal) {
            return Collections.emptyList();
        }

        Path batchIndexFile = batchIndexDir.resolve(batchId + ".txt");
        if (!Files.exists(batchIndexFile)) {
            return Collections.emptyList();
        }

        // 读取批次索引，收集 (id, date) 对，按日期分组
        Map<LocalDate, Set<String>> idsByDate = new HashMap<>();
        try (Stream<String> lines = Files.lines(batchIndexFile)) {
            lines.filter(line -> !line.trim().isEmpty())
                    .map(line -> line.split(","))
                    .filter(parts -> parts.length == 2)
                    .forEach(parts -> {
                        LocalDate date = LocalDate.parse(parts[1]);
                        String id = parts[0];
                        idsByDate.computeIfAbsent(date, k -> new HashSet<>()).add(id);
                    });
        } catch (IOException e) {
            return Collections.emptyList();
        }

        // 按日期文件查询并合并结果
        List<InspectionEntity> results = new ArrayList<>();
        for (Map.Entry<LocalDate, Set<String>> entry : idsByDate.entrySet()) {
            Path targetFile = getRecordsFileForDate(entry.getKey());
            if (!Files.exists(targetFile)) {
                continue;
            }

            try (Stream<String> lines = Files.lines(targetFile)) {
                List<InspectionEntity> batch = lines
                        .filter(line -> !line.trim().isEmpty())
                        .map(line -> {
                            try {
                                return gson.fromJson(line, InspectionEntity.class);
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .filter(entity -> entity != null && entry.getValue().contains(entity.getId()))
                        .collect(Collectors.toList());
                results.addAll(batch);
            } catch (IOException e) {
                // 跳过读取失败的文件
            }
        }
        return results;
    }

    /**
     * 更新记录（只重写对应日期的文件）
     */
    public void update(InspectionEntity entity) {
        if (entity.getId() == null) {
            throw new IllegalArgumentException("Entity ID cannot be null");
        }

        if (!saveLocal) {
            return;
        }

        // 从索引找到记录所在日期
        LocalDate date = entity.getTimestamp() != null
                ? entity.getTimestamp().toLocalDate()
                : findDateById(entity.getId()).orElse(LocalDate.now());
        Path targetFile = getRecordsFileForDate(date);

        if (!Files.exists(targetFile)) {
            // 文件不存在，直接插入
            appendToFile(entity);
            return;
        }

        // 读取该日期的所有记录，替换目标记录
        List<String> lines;
        try {
            lines = Files.readAllLines(targetFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read records file", e);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(targetFile)) {
            boolean found = false;
            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                InspectionEntity existing = gson.fromJson(line, InspectionEntity.class);
                if (existing != null && entity.getId().equals(existing.getId())) {
                    writer.write(gson.toJson(entity));
                    writer.newLine();
                    found = true;
                } else {
                    writer.write(line);
                    writer.newLine();
                }
            }
            if (!found) {
                writer.write(gson.toJson(entity));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to rewrite records file", e);
        }
    }

    /**
     * 删除记录（只重写对应日期的文件）
     */
    public void delete(String id) {
        if (!saveLocal) {
            return;
        }

        // 从索引找到记录所在日期
        Optional<LocalDate> dateOpt = findDateById(id);
        if (dateOpt.isEmpty()) {
            return;  // 记录不存在
        }
        Path targetFile = getRecordsFileForDate(dateOpt.get());

        if (!Files.exists(targetFile)) {
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(targetFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read records file", e);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(targetFile)) {
            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                InspectionEntity existing = gson.fromJson(line, InspectionEntity.class);
                if (existing == null || !id.equals(existing.getId())) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to rewrite records file", e);
        }

        // 从 id_index 中删除该条目
        removeFromIdIndex(id);
    }

    /**
     * 从 id_index 中删除指定 ID
     */
    private void removeFromIdIndex(String id) {
        if (!Files.exists(idIndexFile)) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(idIndexFile);
            List<String> filtered = lines.stream()
                    .filter(line -> !line.trim().isEmpty())
                    .filter(line -> !line.startsWith(id + ","))
                    .collect(Collectors.toList());
            Files.write(idIndexFile, filtered);
        } catch (IOException e) {
            // 索引清理失败不影响主流程
        }
    }

    /**
     * 统计记录数（跨所有日期文件）
     */
    public long count() {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return 0;
        }

        try (Stream<Path> files = Files.list(recordsDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .mapToLong(file -> {
                        try (Stream<String> lines = Files.lines(file)) {
                            return lines.filter(line -> !line.trim().isEmpty()).count();
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 按时间范围查询（只查询涉及到的日期文件）
     */
    public List<InspectionEntity> findByTimeRange(LocalDateTime start, LocalDateTime end) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        LocalDate startDate = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();

        // 收集需要查询的日期文件
        List<LocalDate> datesToQuery = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            datesToQuery.add(current);
            current = current.plusDays(1);
        }

        List<InspectionEntity> results = new ArrayList<>();
        for (LocalDate date : datesToQuery) {
            Path targetFile = getRecordsFileForDate(date);
            if (!Files.exists(targetFile)) {
                continue;
            }

            try (Stream<String> lines = Files.lines(targetFile)) {
                List<InspectionEntity> batch = lines
                        .filter(line -> !line.trim().isEmpty())
                        .map(line -> {
                            try {
                                return gson.fromJson(line, InspectionEntity.class);
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .filter(entity -> entity != null && entity.getTimestamp() != null)
                        .filter(entity -> !entity.getTimestamp().isBefore(start) && !entity.getTimestamp().isAfter(end))
                        .collect(Collectors.toList());
                results.addAll(batch);
            } catch (IOException e) {
                // 跳过读取失败的文件
            }
        }
        return results;
    }
}