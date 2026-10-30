// This file provides sensitivity analysis for the BJ rule.

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BJInstances_SA {
    static final int[] JOB_LEVELS = {10, 20, 40, 60, 80};
    static final double[] A_LEVELS = {0.5, 0.6, 0.7, 0.8};
    static final double[] L_LEVELS = {0.5, 0.6, 0.7, 0.8, 0.9};
    static final int SAMPLES = 20, CATEGORIES = 4;
    static final double B = 0.2; // Keep the paper setting and require a + b <= 1.
    static final Path OUTPUT = Paths.get("src/main/Results/[4] Result_SensitivityAnalysis");

    public static void main(String[] args) throws IOException {
        int samples = SAMPLES;
        Path output = OUTPUT;
        for (String arg : args) {
            if (arg.startsWith("--samples=")) samples = Integer.parseInt(arg.substring(10));
            else if (arg.startsWith("--output=")) output = Paths.get(arg.substring(9));
            else throw new IllegalArgumentException("Unknown argument: " + arg);
        }
        if (samples < 1) throw new IllegalArgumentException("Samples must be positive");

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"n", "G", "M", "a", "L", "b", "delta", "Instances",
                "BJ Rule Makespan", "PJ Lower Bound", "Delta Mean (%)", "Delta Max (%)"});
        for (int jobs : JOB_LEVELS) {
            for (double a : A_LEVELS) {
                for (double l : L_LEVELS) {
                    Stats bj = new Stats(), pj = new Stats(), delta = new Stats();
                    for (int rep = 1; rep <= samples; rep++) {
                        ComparisonAlgorithms.Instance x = createInstance(jobs, 2, rep, a, l, B);
                        // The zero job limit disables global exact search.
                        ComparisonAlgorithms.ReferenceValues ref = ComparisonAlgorithms.referenceValues(
                                x, 1, 0, CATEGORIES);
                        if (ref.noForgettingExact <= 0) throw new IllegalStateException("Invalid PJ lower bound");
                        double value = 100.0 * (ref.batchExact - ref.noForgettingExact) / ref.noForgettingExact;
                        if (!Double.isFinite(value) || value < -1e-8) throw new IllegalStateException("Invalid BJ bound");
                        if (Math.abs(value) < 1e-10) value = 0;
                        bj.add(ref.batchExact);
                        pj.add(ref.noForgettingExact);
                        delta.add(value);
                    }
                    rows.add(new Object[]{jobs, CATEGORIES, 2, a, l, B, 1, samples,
                            bj.mean(), pj.mean(), delta.mean(), delta.max});
                }
            }
            System.out.printf(Locale.ROOT, "BJ n=%d: %d parameter settings completed%n",
                    jobs, A_LEVELS.length * L_LEVELS.length);
        }

        Path file = output.resolve("BJInstances_SA.xlsx");
        writeWorkbook(file, rows);
        System.out.println("Saved: " + file);
    }

    static ComparisonAlgorithms.Instance createInstance(int jobs, int machines, int rep,
                                                         double a, double l, double b) throws IOException {
        String folder = machines == 2 ? "Two-Machine" : "M-Machine";
        folder += jobs <= 20 ? "-Instances" : "-LargeInstances";
        Path file = Paths.get("src/main/Instances", folder,
                jobs + "-" + CATEGORIES + "-" + machines + "-" + rep + ".txt");
        // Keep the processing times and category sizes in the existing files.
        return machines == 2 ? BJInstance_comparison.readInstance(file, "Sensitivity", l, a, b)
                : MBJInstance_comparison.readInstance(file, "Sensitivity", l, a, b);
    }

    private static final class Stats {
        int count;
        double sum, max = Double.NEGATIVE_INFINITY;

        void add(double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite result");
            count++;
            sum += value;
            max = Math.max(max, value);
        }

        double mean() { return sum / count; }
    }

    // Write one plain worksheet without colors, filters, or extra settings.
    static void writeWorkbook(Path output, List<Object[]> rows) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Combinations");
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                Object[] record = rows.get(r);
                for (int c = 0; c < record.length; c++) {
                    Cell cell = row.createCell(c);
                    if (record[c] instanceof Number value) {
                        if (!Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("Non-finite cell");
                        cell.setCellValue(value.doubleValue());
                    } else cell.setCellValue(record[c] == null ? "" : record[c].toString());
                }
            }
            for (int c = 0; c < rows.get(0).length; c++) {
                int width = 8;
                for (Object[] record : rows) {
                    if (c < record.length && record[c] != null)
                        width = Math.max(width, record[c].toString().length() + 2);
                }
                sheet.setColumnWidth(c, Math.min(80, width) * 256);
            }
            try (OutputStream stream = Files.newOutputStream(output)) { workbook.write(stream); }
        }
    }
}
