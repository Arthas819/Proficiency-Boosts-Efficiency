// This file evaluate the performance of the proposed MBJ algorithm
// Newly introduced benchmarks: NEH, IG, CDS

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MBJInstance_comparison {
    static final int DELTA = 1;
    static final List<String> METRICS = Arrays.asList(
            "gap_SPT(%)", // (C_SPT - C_MBJ) / C_MBJ * 100
            "gap_NEH(%)", // (C_NEH - C_MBJ) / C_MBJ * 100
            "gap_CDS(%)", // (C_CDS - C_MBJ) / C_MBJ * 100
            "gap_IG(%)",  // (C_IG - C_MBJ) / C_MBJ * 100
            "gap_opt(%)", // (C_MBJ - OPT) / OPT * 100
            "gap_OBW(%)", // (C_MBJ - OPT_BW) / OPT_BW * 100
            "MBJ_Time(ms)", "SPT_Time(ms)", "NEH_Time(ms)", "CDS_Time(ms)", "IG_Time(ms)");
    static final List<String> HEADERS = detailHeaders();
    static final List<String> SUMMARY_HEADERS = summaryHeaders(true);
    static final List<String> SUMMARY_2_HEADERS = summaryHeaders(false);
    private static final Pattern FILE_NAME = Pattern.compile("(\\d+)-(\\d+)-(\\d+)-(\\d+)\\.txt");

    public static void main(String[] args) throws IOException {
        long started = System.nanoTime();
        // Parameters
        double L = 0.7, t1 = 0.7, t2 = 0.2;
        int Ite = 20, igIterations = 100, igDestruction = 4, timingRepeats = 3;
        int exactMaxJobs = 10, batchExactMaxCategories = 10;  // Max number of job or category that can be exactly solved.
        double igTemperature = 0.4;
        long seed = 42L;
        String[] folders = {"M-Machine-Instances", "M-Machine-LargeInstances"};
        String[] algorithms = {"MBJ", "SPT", "NEH", "CDS", "IG"};
        String[] baselines = {"SPT", "NEH", "CDS", "IG"};
        Path root = projectRoot();
        Path output = root.resolve("src/main/Results/[2] Result_Gaps/FM-MBJ-Comparison.xlsx");
        // In the output, we also summarize the results of COPT.
        Map<CoptKey, CoptData> coptResults = readCoptResults(root.resolve("src/main/Results/[1] Result_COPT"));

        // Read instances
        List<ComparisonAlgorithms.Instance> instances = new ArrayList<>();
        for (String folder : folders) {
            Path directory = root.resolve("src/main/Instances").resolve(folder);
            try (Stream<Path> paths = Files.list(directory)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    if (!FILE_NAME.matcher(file.getFileName().toString()).matches()) continue;
                    String scale = folder.contains("Large") ? "Large" : "Small";
                    ComparisonAlgorithms.Instance x = readInstance(file, scale, L, t1, t2);
                    if (x.index <= Ite && x.machines > 2) instances.add(x);
                }
            }
        }
        instances.sort(Comparator.comparingInt((ComparisonAlgorithms.Instance x) -> x.jobs)
                .thenComparingInt(x -> x.categories).thenComparingInt(x -> x.machines).thenComparingInt(x -> x.index));
        if (instances.isEmpty()) throw new IOException("No FM instances found.");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ComparisonAlgorithms.Instance x : instances)
            counts.merge(x.jobs + ":" + x.categories + ":" + x.machines, 1, Integer::sum);
        for (Map.Entry<String, Integer> entry : counts.entrySet())
            if (entry.getValue() != Ite) throw new IOException("Missing instances: " + entry.getKey());

        // Warm up; excluded from per-algorithm timings
        Map<String, ComparisonAlgorithms.Instance> warmup = new LinkedHashMap<>();
        for (ComparisonAlgorithms.Instance x : instances) warmup.putIfAbsent(x.jobs + ":" + x.machines, x);
        long deadline = System.nanoTime() + 1_000_000_000L;
        do {
            for (ComparisonAlgorithms.Instance x : warmup.values()) {
                ComparisonAlgorithms.IgParameters parameters = new ComparisonAlgorithms.IgParameters(
                        igIterations, igDestruction, igTemperature, seed);
                ComparisonAlgorithms.warmUpTiming(x, DELTA, parameters, 1);
            }
        } while (System.nanoTime() < deadline);

        Files.createDirectories(output.getParent());
        try (Workbook workbook = openWorkbook(output)) {
            Sheet details = resetSheet(workbook, "Instances");
            Sheet summary = resetSheet(workbook, "summary_1");
            Sheet pooledSummary = resetSheet(workbook, "summary_2");
            writeRow(details, 0, HEADERS.toArray());
            writeRow(summary, 0, SUMMARY_HEADERS.toArray());
            writeRow(pooledSummary, 0, SUMMARY_2_HEADERS.toArray());
            Map<Group, GroupData> groups = new LinkedHashMap<>();
            Map<Group, GroupData> pooledGroups = new LinkedHashMap<>();
            int row = 1;

            // Solve each instance
            for (ComparisonAlgorithms.Instance x : instances) {
                ComparisonAlgorithms.IgParameters parameters = new ComparisonAlgorithms.IgParameters(
                        igIterations, igDestruction, igTemperature, seed);
                Map<String, ComparisonAlgorithms.Result> results = new LinkedHashMap<>();
                for (ComparisonAlgorithms.Result result : ComparisonAlgorithms.benchmarkCompare(x, DELTA, parameters, timingRepeats))
                    results.put(result.algorithm, result);
                double proposed = results.get("MBJ").makespan;
                ComparisonAlgorithms.ReferenceValues references = ComparisonAlgorithms.referenceValues(
                        x, DELTA, exactMaxJobs, batchExactMaxCategories);
                Object[] values = new Object[HEADERS.size()];
                Object[] metadata = {x.jobs, x.categories, x.machines, DELTA, x.index, proposed};
                System.arraycopy(metadata, 0, values, 0, metadata.length);
                int column = metadata.length;
                for (String algorithm : baselines) values[column++] = percentGap(results.get(algorithm).makespan, proposed);
                for (double gap : legacyMetrics(proposed, references)) values[column++] = gap;
                for (String algorithm : algorithms) values[column++] = results.get(algorithm).millis;
                CoptData copt = coptResults.get(new CoptKey(x.jobs, x.categories, x.machines, x.index));
                values[column++] = copt == null ? Double.NaN : copt.solved();
                values[column++] = copt == null ? Double.NaN : copt.seconds();
                values[column++] = copt == null ? Double.NaN : copt.makespan();
                writeRow(details, row++, values);

                groups.computeIfAbsent(new Group(x.jobs, x.machines), key -> new GroupData())
                        .add(values, metadata.length, copt, proposed);
                pooledGroups.computeIfAbsent(new Group(x.jobs, 0), key -> new GroupData())
                        .add(values, metadata.length, copt, proposed);
                if ((row - 1) % 200 == 0) System.out.println("FM: " + (row - 1) + "/" + instances.size());
            }

            writeSummary(summary, groups, true);
            writeSummary(pooledSummary, pooledGroups, false);
            CellStyle timeStyle = workbook.createCellStyle();
            timeStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            formatSummaryTimes(summary, timeStyle);
            formatSummaryTimes(pooledSummary, timeStyle);
            updateSectionTable(workbook, pooledSummary);
            try (OutputStream stream = Files.newOutputStream(output)) {
                workbook.write(stream);
            }
        }
        System.out.println("Saved: " + output);
        System.out.printf(Locale.ROOT, "FM: %d instances, %.2f s total%n",
                instances.size(), (System.nanoTime() - started) / 1_000_000_000.0);
    }

    private record CoptKey(int jobs, int categories, int machines, int index) { }
    private record CoptData(int solved, double seconds, double makespan) { }

    private static Map<CoptKey, CoptData> readCoptResults(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("Missing COPT directory: " + directory);
        List<String> required = List.of("Jobs", "Categories", "Machines", "Ite",
                "Solved-0/1", "COPT-time(s)", "Solved-opt", "Unsolved-solution");
        Map<CoptKey, CoptData> result = new LinkedHashMap<>();
        Map<CoptKey, String> origins = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> paths = Files.list(directory)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("~$"))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                    .sorted().toList();
        }
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (fileName.contains("-J40-") || fileName.contains("-J40.")) continue;
            try (InputStream input = Files.newInputStream(file); XSSFWorkbook book = new XSSFWorkbook(input)) {
                if (book.getNumberOfSheets() == 0) throw new IOException("Empty COPT workbook: " + file);
                Sheet sheet = book.getSheetAt(0);
                Row header = sheet.getRow(0);
                if (header == null) throw new IOException("Missing COPT header: " + file);
                Map<String, Integer> columns = new LinkedHashMap<>();
                for (Cell cell : header) {
                    String name = cell.toString().trim();
                    if (required.contains(name) && columns.putIfAbsent(name, cell.getColumnIndex()) != null)
                        throw new IOException("Duplicate COPT header '" + name + "': " + file);
                }
                for (String name : required) if (!columns.containsKey(name))
                    throw new IOException("Missing COPT header '" + name + "': " + file);
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null || row.getPhysicalNumberOfCells() == 0) continue;
                    CoptKey key = new CoptKey(coptInteger(row, columns, "Jobs", file),
                            coptInteger(row, columns, "Categories", file), coptInteger(row, columns, "Machines", file),
                            coptInteger(row, columns, "Ite", file));
                    if (key.jobs() == 40) continue;
                    double flag = coptNumber(row, columns, "Solved-0/1", file);
                    if (flag != 0 && flag != 1) throw invalidCopt(file, r, "Solved-0/1 must be 0 or 1");
                    double seconds = coptNumber(row, columns, "COPT-time(s)", file);
                    double makespan = coptNumber(row, columns, flag == 1 ? "Solved-opt" : "Unsolved-solution", file);
                    if (seconds < 0) throw invalidCopt(file, r, "Negative COPT-time(s)");
                    if (makespan <= 0) throw invalidCopt(file, r, "Nonpositive COPT makespan");
                    CoptData value = new CoptData((int) flag, seconds, makespan);
                    CoptData previous = result.putIfAbsent(key, value);
                    String origin = file + ":" + (r + 1);
                    if (previous != null && !previous.equals(value))
                        throw new IOException("Conflicting COPT key " + key + ": " + origins.get(key) + " and " + origin);
                    origins.putIfAbsent(key, origin);
                }
            }
        }
        return result;
    }

    private static int coptInteger(Row row, Map<String, Integer> columns, String name, Path file) throws IOException {
        double value = coptNumber(row, columns, name, file);
        if (!Double.isFinite(value) || value < 1 || value > Integer.MAX_VALUE || value != Math.rint(value))
            throw invalidCopt(file, row.getRowNum(), "Invalid positive integer '" + name + "'");
        return (int) value;
    }

    private static double coptNumber(Row row, Map<String, Integer> columns, String name, Path file) throws IOException {
        Cell cell = row.getCell(columns.get(name));
        if (cell == null || cell.getCellType() == CellType.BLANK) return Double.NaN;
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        double value;
        if (type == CellType.NUMERIC) value = cell.getNumericCellValue();
        else if (type == CellType.STRING) {
            String text = cell.getStringCellValue().trim();
            if (text.isEmpty() || text.equals("-") || text.equalsIgnoreCase("N/A")) return Double.NaN;
            try { value = Double.parseDouble(text); }
            catch (NumberFormatException e) { throw invalidCopt(file, row.getRowNum(), "Invalid number in '" + name + "': " + text); }
        } else throw invalidCopt(file, row.getRowNum(), "Nonnumeric cell in '" + name + "'");
        if (!Double.isFinite(value)) throw invalidCopt(file, row.getRowNum(), "Non-finite number in '" + name + "'");
        return value;
    }

    private static IOException invalidCopt(Path file, int row, String detail) {
        return new IOException(file + ":" + (row + 1) + ": " + detail);
    }

    private static Workbook openWorkbook(Path output) throws IOException {
        if (!Files.exists(output)) return new XSSFWorkbook();
        try (InputStream stream = Files.newInputStream(output)) {
            return new XSSFWorkbook(stream);
        }
    }

    private static Sheet resetSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) return workbook.createSheet(name);
        for (int r = sheet.getLastRowNum(); r >= 0; r--) {
            Row row = sheet.getRow(r);
            if (row != null) sheet.removeRow(row);
        }
        return sheet;
    }

    private static void updateSectionTable(Workbook workbook, Sheet source) {
        Sheet target = workbook.getSheet("Used in Section 6");
        if (target == null) return;
        String[] fields = {"Jobs", "gap_opt(%) mean(std)", "gap_OBW(%) mean(std)", "MBJ_Time(ms)", "COPT-Solved-0/1", "COPT_Time(s)", "gap_COPT(%) mean(std)", "gap_NEH(%) mean(std)", "NEH_Time(ms)", "gap_IG(%) mean(std)", "IG_Time(ms)", "gap_CDS(%) mean(std)", "CDS_Time(ms)"};
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell header : source.getRow(0)) columns.put(header.getStringCellValue(), header.getColumnIndex());
        Map<Integer, Row> rows = new LinkedHashMap<>();
        for (Row row : source) {
            if (row.getRowNum() > 0) rows.put((int) row.getCell(columns.get("Jobs")).getNumericCellValue(), row);
        }
        short format = workbook.createDataFormat().getFormat("0.00");
        Map<Short, CellStyle> timeStyles = new LinkedHashMap<>();
        for (Row row : target) {
            Cell jobs = row.getCell(0);
            if (row.getRowNum() < 3 || jobs == null || jobs.getCellType() != CellType.NUMERIC) continue;
            Row from = rows.get((int) jobs.getNumericCellValue());
            if (from == null) continue;
            for (int c = 0; c < fields.length; c++) {
                Cell value = from.getCell(columns.get(fields[c]));
                Cell cell = row.getCell(c);
                if (cell == null) cell = row.createCell(c);
                if (value.getCellType() == CellType.NUMERIC) cell.setCellValue(value.getNumericCellValue());
                else cell.setCellValue(value.getStringCellValue());
                if ((fields[c].endsWith("_Time(ms)") || fields[c].endsWith("_Time(s)"))
                        && cell.getCellStyle().getDataFormat() != format) {
                    CellStyle original = cell.getCellStyle();
                    CellStyle style = timeStyles.computeIfAbsent(original.getIndex(), key -> {
                        CellStyle copy = workbook.createCellStyle();
                        copy.cloneStyleFrom(original);
                        copy.setDataFormat(format);
                        return copy;
                    });
                    cell.setCellStyle(style);
                }
            }
        }
    }

    private static Path projectRoot() throws IOException {
        Path root = findProjectRoot(Paths.get("").toAbsolutePath());
        if (root != null) return root;
        try {
            var source = MBJInstance_comparison.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                root = findProjectRoot(Paths.get(source.getLocation().toURI()));
                if (root != null) return root;
            }
        } catch (URISyntaxException | RuntimeException e) {
            throw new IOException("Cannot locate project root containing src/main/Instances.", e);
        }
        throw new IOException("Cannot locate project root containing src/main/Instances.");
    }

    private static Path findProjectRoot(Path start) {
        for (Path directory = start.toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve("src/main/Instances"))) return directory;
        }
        return null;
    }

    private static void writeRow(Sheet sheet, int index, Object[] values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            Object value = values[i];
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) cell.setCellValue(number.doubleValue());
            else cell.setCellValue(value == null || value instanceof Number ? "-" : value.toString());
        }
    }

    private static List<String> detailHeaders() {
        List<String> headers = new ArrayList<>(Arrays.asList("Jobs", "Categories", "Machines", "delta", "Instance", "MBJ_Makespan"));
        headers.addAll(METRICS);
        headers.addAll(Arrays.asList("COPT-Solved-0/1", "COPT_Time(s)", "COPT-makespan"));
        return headers;
    }

    private static List<String> summaryHeaders(boolean byMachine) {
        List<String> headers = new ArrayList<>(byMachine
                ? Arrays.asList("Jobs", "Machines", "delta", "Instances")
                : Arrays.asList("Jobs", "delta", "Instances"));
        for (String metric : METRICS) {
            if (metric.endsWith("_Time(ms)")) {
                headers.add(metric);
            } else {
                headers.add(metric + " mean(std)");
                headers.add(metric + " mean");
                headers.add(metric + " std");
            }
        }
        headers.addAll(Arrays.asList("COPT-Solved-0/1", "COPT_Time(s)", "gap_COPT(%)",
                "gap_COPT(%) mean(std)", "gap_COPT(%) std"));
        return headers;
    }

    // Pool individual observations, including across machine counts in summary_2.
    private static void writeSummary(Sheet sheet, Map<Group, GroupData> groups, boolean byMachine) {
        List<Group> keys = new ArrayList<>(groups.keySet());
        keys.sort(Comparator.comparingInt(Group::jobs).thenComparingInt(Group::machines));
        int row = 1;
        for (Group key : keys) {
            GroupData data = groups.get(key);
            Object[] values = new Object[(byMachine ? SUMMARY_HEADERS : SUMMARY_2_HEADERS).size()];
            Object[] metadata = byMachine ? new Object[]{key.jobs, key.machines, DELTA, data.instances}
                    : new Object[]{key.jobs, DELTA, data.instances};
            System.arraycopy(metadata, 0, values, 0, metadata.length);
            int column = metadata.length;
            for (int i = 0; i < data.stats.length; i++) {
                Stats stat = data.stats[i];
                if (METRICS.get(i).endsWith("_Time(ms)")) {
                    values[column++] = stat.average();
                } else {
                    values[column++] = stat.meanSd();
                    values[column++] = stat.average();
                    values[column++] = stat.sd();
                }
            }
            if (data.coptFound == 0) {
                while (column < values.length) values[column++] = "-";
            } else {
                values[column++] = data.coptSolved + "/" + data.instances;
                values[column++] = data.coptTime.average();
                values[column++] = data.coptGap.average();
                values[column++] = data.coptGap.meanSd();
                values[column++] = data.coptGap.sd();
            }
            writeRow(sheet, row++, values);
        }
    }

    private static void formatSummaryTimes(Sheet sheet, CellStyle style) {
        for (Cell header : sheet.getRow(0)) {
            String name = header.getStringCellValue();
            if (!name.endsWith("_Time(ms)") && !name.endsWith("_Time(s)")) continue;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                Cell cell = row == null ? null : row.getCell(header.getColumnIndex());
                if (cell != null && cell.getCellType() == CellType.NUMERIC) cell.setCellStyle(style);
            }
        }
    }

    static double[] legacyMetrics(double proposed, ComparisonAlgorithms.ReferenceValues references) {
        return new double[]{
                percentDifference(proposed, references.clfeExact, references.clfeExact),
                percentDifference(proposed, references.batchExact, references.batchExact)
        };
    }

    private static double percentDifference(double first, double second, double denominator) {
        if (!Double.isFinite(first) || !Double.isFinite(second) || !Double.isFinite(denominator)
                || first < 0 || second < 0 || denominator <= 0) return Double.NaN;
        return 100 * (first - second) / denominator;
    }


    static double percentGap(double algorithm, double reference) {
        if (!Double.isFinite(algorithm) || !Double.isFinite(reference) || reference <= 0 || algorithm < 0)
            throw new IllegalArgumentException("Invalid makespan.");
        return 100 * (algorithm - reference) / reference;
    }

    static ComparisonAlgorithms.Instance readInstance(Path path, String scale) throws IOException {
        return readInstance(path, scale, 0.7, 0.7, 0.2);
    }

    static ComparisonAlgorithms.Instance readInstance(Path path, String scale, double L, double t1, double t2)
            throws IOException {
        Matcher match = FILE_NAME.matcher(path.getFileName().toString());
        if (!match.matches()) throw new IOException("Invalid filename: " + path);
        int jobs = Integer.parseInt(match.group(1));
        int categories = Integer.parseInt(match.group(2));
        int machines = Integer.parseInt(match.group(3));
        int index = Integer.parseInt(match.group(4));
        double[][] pt = new double[jobs][machines];
        List<Integer> counts = new ArrayList<>();
        int row = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (row == jobs) throw new IOException("Too many rows: " + path);
                String[] fields = line.trim().split("\\s+");
                if (fields.length != machines) throw new IOException("Wrong machine count at row " + (row + 1) + ": " + path);
                for (int m = 0; m < machines; m++) {
                    try { pt[row][m] = Double.parseDouble(fields[m]); }
                    catch (NumberFormatException e) { throw new IOException("Invalid processing time: " + path, e); }
                    if (!Double.isFinite(pt[row][m]) || pt[row][m] < 0 || pt[row][m] > 100)
                        throw new IOException("Processing time outside [0,100]: " + path);
                }
                if (row == 0 || !Arrays.equals(pt[row], pt[row - 1])) counts.add(1);
                else counts.set(counts.size() - 1, counts.get(counts.size() - 1) + 1);
                row++;
            }
        }
        if (row != jobs || counts.size() != categories)
            throw new IOException("Expected " + jobs + " rows / " + categories + " contiguous categories, found "
                    + row + " / " + counts.size() + ": " + path);
        return new ComparisonAlgorithms.Instance(path, scale, jobs, categories, machines, index, pt,
                counts.stream().mapToInt(Integer::intValue).toArray(), L, t1, t2);
    }


    static final class Stats {
        int count;
        double mean, m2;
        void add(double value) {
            if (!Double.isFinite(value)) return;
            count++;
            double difference = value - mean;
            mean += difference / count;
            m2 += difference * (value - mean);
        }
        double average() { return count == 0 ? Double.NaN : mean; }
        double sd() { return count == 0 ? Double.NaN : Math.sqrt(Math.max(0, m2 / count)); }
        String meanSd() {
            return count == 0 ? "-" : String.format(Locale.ROOT, "%.3f(%.3f)", average(), sd()).replace("-0.000", "0.000");
        }
    }



    private record Group(int jobs, int machines) { }

    private static final class GroupData {
        int instances, coptFound, coptSolved;
        final Stats[] stats = new Stats[METRICS.size()];
        final Stats coptTime = new Stats(), coptGap = new Stats();
        GroupData() {
            for (int i = 0; i < stats.length; i++) stats[i] = new Stats();
        }
        void add(Object[] values, int offset, CoptData copt, double proposed) {
            instances++;
            for (int i = 0; i < stats.length; i++) stats[i].add(((Number) values[offset + i]).doubleValue());
            if (copt == null) return;
            coptFound++;
            if (copt.solved() == 1) {
                coptSolved++;
                coptTime.add(copt.seconds());
            } else if (copt.solved() == 0 && Double.isFinite(copt.makespan()) && copt.makespan() > 0) {
                coptGap.add(percentGap(copt.makespan(), proposed));
            }
        }
    }
}
