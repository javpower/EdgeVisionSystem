package com.edge.vision.repository;

import com.edge.vision.model.InspectionEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
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

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, (JsonSerializer<LocalDateTime>) (src, type, context) ->
                    new com.google.gson.JsonPrimitive(src.format(ISO_FORMATTER)))
            .registerTypeAdapter(LocalDateTime.class, (JsonDeserializer<LocalDateTime>) (json, type, context) ->
                    LocalDateTime.parse(json.getAsString(), ISO_FORMATTER))
            .create();

    @Value("${edge-vision.system.save-local:true}")
    private boolean saveLocal;

    private Path dataDir;
    private Path recordsDir;  // 按日期分文件存储: records/2024-01-15.jsonl

    @PostConstruct
    public void init() {
        if (saveLocal) {
            dataDir = Paths.get("data");
            recordsDir = dataDir.resolve("records");

            try {
                Files.createDirectories(dataDir);
                Files.createDirectories(recordsDir);
                // 验证目录确实创建成功
                if (!Files.exists(recordsDir)) {
                    throw new RuntimeException("Failed to create records directory: " + recordsDir.toAbsolutePath());
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize data store at: " + recordsDir.toAbsolutePath(), e);
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
     * 插入记录（追加到对应日期的文件）
     */
    public void insert(InspectionEntity entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
        }

        if (saveLocal) {
            appendToFile(entity);
        }
    }

    private void appendToFile(InspectionEntity entity) {
        try {
            LocalDate date = entity.getTimestamp() != null
                    ? entity.getTimestamp().toLocalDate()
                    : LocalDate.now();
            Path targetFile = getRecordsFileForDate(date);

            // 确保目录存在（防止运行时目录被删除）
            Files.createDirectories(recordsDir);

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
     * 根据 ID 查找（遍历最近的日期文件）
     */
    public Optional<InspectionEntity> findById(String id) {
        if (!saveLocal) {
            return Optional.empty();
        }

        // 搜索最近 30 天的文件
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 30; i++) {
            LocalDate date = today.minusDays(i);
            Path targetFile = getRecordsFileForDate(date);
            if (!Files.exists(targetFile)) {
                continue;
            }

            try (Stream<String> lines = Files.lines(targetFile)) {
                Optional<InspectionEntity> result = lines
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
                if (result.isPresent()) {
                    return result;
                }
            } catch (IOException e) {
                // 继续搜索下一个文件
            }
        }
        return Optional.empty();
    }

    /**
     * 查找所有记录（按日期倒序，带限制）
     */
    public List<InspectionEntity> findAll() {
        return findAll(1000);
    }

    public List<InspectionEntity> findAll(int limit) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> files = Files.list(recordsDir)) {
            List<Path> dateFiles = files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted(Comparator.reverseOrder())
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
     * 根据日期查找
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
     * 根据批次 ID 查找（遍历最近的日期文件）
     */
    public List<InspectionEntity> findByBatchId(String batchId) {
        if (!saveLocal) {
            return Collections.emptyList();
        }

        List<InspectionEntity> results = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // 搜索最近 30 天的文件
        for (int i = 0; i < 30; i++) {
            LocalDate date = today.minusDays(i);
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
                        .filter(Objects::nonNull)
                        .filter(entity -> batchId.equals(entity.getBatchId()))
                        .collect(Collectors.toList());
                results.addAll(batch);
            } catch (IOException e) {
                // 继续搜索
            }
        }
        return results;
    }

    /**
     * 根据工件类型查找（遍历最近的日期文件）
     */
    public List<InspectionEntity> findByPartType(String partType) {
        if (!saveLocal) {
            return Collections.emptyList();
        }

        List<InspectionEntity> results = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // 搜索最近 30 天的文件
        for (int i = 0; i < 30; i++) {
            LocalDate date = today.minusDays(i);
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
                        .filter(Objects::nonNull)
                        .filter(entity -> partType.equals(entity.getPartName()))
                        .collect(Collectors.toList());
                results.addAll(batch);
            } catch (IOException e) {
                // 继续搜索
            }
        }
        return results;
    }

    /**
     * 更新记录（重写对应日期的文件）
     */
    public void update(InspectionEntity entity) {
        if (entity.getId() == null) {
            throw new IllegalArgumentException("Entity ID cannot be null");
        }

        if (!saveLocal) {
            return;
        }

        try {
            LocalDate date = entity.getTimestamp() != null
                    ? entity.getTimestamp().toLocalDate()
                    : LocalDate.now();
            Path targetFile = getRecordsFileForDate(date);

            if (!Files.exists(targetFile)) {
                appendToFile(entity);
                return;
            }

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
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to rewrite records file", e);
        }
    }

    /**
     * 删除记录
     */
    public void delete(String id) {
        if (!saveLocal) {
            return;
        }

        // 先找到记录
        Optional<InspectionEntity> entityOpt = findById(id);
        if (entityOpt.isEmpty()) {
            return;
        }

        try {
            InspectionEntity entity = entityOpt.get();
            LocalDate date = entity.getTimestamp() != null
                    ? entity.getTimestamp().toLocalDate()
                    : LocalDate.now();
            Path targetFile = getRecordsFileForDate(date);

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
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to rewrite records file", e);
        }
    }

    /**
     * 统计记录数
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
     * 按时间范围查询
     */
    public List<InspectionEntity> findByTimeRange(LocalDateTime start, LocalDateTime end) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        LocalDate startDate = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();

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

    /**
     * 按日期范围查询
     */
    public List<InspectionEntity> findByDateBetween(LocalDate startDate, LocalDate endDate) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        List<InspectionEntity> results = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            Path targetFile = getRecordsFileForDate(current);
            if (Files.exists(targetFile)) {
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
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    results.addAll(batch);
                } catch (IOException e) {
                    // 跳过读取失败的文件
                }
            }
            current = current.plusDays(1);
        }
        return results;
    }

    /**
     * 查询指定日期之后的记录
     */
    public List<InspectionEntity> findByDateAfter(LocalDate date) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        List<InspectionEntity> results = new ArrayList<>();

        // 查询从指定日期到今天（最多365天）
        LocalDate today = LocalDate.now();
        LocalDate current = date;
        int daysSearched = 0;
        int maxDays = 365;

        while (!current.isAfter(today) && daysSearched < maxDays) {
            Path targetFile = getRecordsFileForDate(current);
            if (Files.exists(targetFile)) {
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
                            .filter(Objects::nonNull)
                            .filter(entity -> entity.getTimestamp() != null)
                            .filter(entity -> !entity.getTimestamp().toLocalDate().isBefore(date))
                            .collect(Collectors.toList());
                    results.addAll(batch);
                } catch (IOException e) {
                    // 跳过读取失败的文件
                }
            }
            current = current.plusDays(1);
            daysSearched++;
        }
        return results;
    }

    /**
     * 查询指定日期之前的记录
     */
    public List<InspectionEntity> findByDateBefore(LocalDate date) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        List<InspectionEntity> results = new ArrayList<>();

        // 查询从最早记录到指定日期（最多365天）
        LocalDate current = date;
        int daysSearched = 0;
        int maxDays = 365;

        while (daysSearched < maxDays) {
            Path targetFile = getRecordsFileForDate(current);
            if (Files.exists(targetFile)) {
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
                            .filter(Objects::nonNull)
                            .filter(entity -> entity.getTimestamp() != null)
                            .filter(entity -> entity.getTimestamp().toLocalDate().isBefore(date))
                            .collect(Collectors.toList());
                    results.addAll(batch);
                } catch (IOException e) {
                    // 跳过读取失败的文件
                }
            }
            current = current.minusDays(1);
            daysSearched++;
        }
        return results;
    }

    /**
     * 查询指定时间范围内的记录（精确到时分秒）
     */
    public List<InspectionEntity> findByTimestampBetween(LocalDateTime start, LocalDateTime end) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        List<InspectionEntity> results = new ArrayList<>();

        LocalDate startDate = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();

        // 计算需要查询的日期范围
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            Path targetFile = getRecordsFileForDate(current);
            if (Files.exists(targetFile)) {
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
                            .filter(Objects::nonNull)
                            .filter(entity -> entity.getTimestamp() != null)
                            .filter(entity -> {
                                LocalDateTime timestamp = entity.getTimestamp();
                                // 精确时间过滤：在 start 和 end 之间
                                return !timestamp.isBefore(start) && !timestamp.isAfter(end);
                            })
                            .collect(Collectors.toList());
                    results.addAll(batch);
                } catch (IOException e) {
                    // 跳过读取失败的文件
                }
            }
            current = current.plusDays(1);
        }
        return results;
    }

    /**
     * 查询指定时间之后的记录（精确到时分秒）
     */
    public List<InspectionEntity> findByTimestampAfter(LocalDateTime start) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        List<InspectionEntity> results = new ArrayList<>();

        LocalDate startDate = start.toLocalDate();
        LocalDate today = LocalDate.now();

        // 查询从 startDate 开始到今天（最多365天）
        LocalDate current = startDate;
        int daysSearched = 0;
        int maxDays = 365;

        while (!current.isAfter(today) && daysSearched < maxDays) {
            Path targetFile = getRecordsFileForDate(current);
            if (Files.exists(targetFile)) {
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
                            .filter(Objects::nonNull)
                            .filter(entity -> entity.getTimestamp() != null)
                            .filter(entity -> !entity.getTimestamp().isBefore(start))
                            .collect(Collectors.toList());
                    results.addAll(batch);
                } catch (IOException e) {
                    // 跳过读取失败的文件
                }
            }
            current = current.plusDays(1);
            daysSearched++;
        }
        return results;
    }

    /**
     * 查询指定时间之前的记录（精确到时分秒）
     */
    public List<InspectionEntity> findByTimestampBefore(LocalDateTime end) {
        if (!saveLocal || !Files.exists(recordsDir)) {
            return Collections.emptyList();
        }

        List<InspectionEntity> results = new ArrayList<>();

        LocalDate endDate = end.toLocalDate();

        // 查询从最早记录到 endDate（最多365天）
        LocalDate current = endDate;
        int daysSearched = 0;
        int maxDays = 365;

        while (daysSearched < maxDays) {
            Path targetFile = getRecordsFileForDate(current);
            if (Files.exists(targetFile)) {
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
                            .filter(Objects::nonNull)
                            .filter(entity -> entity.getTimestamp() != null)
                            .filter(entity -> !entity.getTimestamp().isAfter(end))
                            .collect(Collectors.toList());
                    results.addAll(batch);
                } catch (IOException e) {
                    // 跳过读取失败的文件
                }
            }
            current = current.minusDays(1);
            daysSearched++;
        }
        return results;
    }
}
