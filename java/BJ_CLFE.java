// This file evaluates the impact of the CLFEs on scheduling decisions.
// See Section 6.3 of our paper for details.

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class BJ_CLFE {
    public static void main(String[] args) throws IOException {
        double L = 0.5;
        double t1 = 0.7;
        double t2 = 0.2;
        int delta = 1;
        int M = 2;
        int p_min = 0;
        int p_max = 100;
        int Ite = 20;
        double[] L_instances = {0.5, 0.6, 0.7, 0.8, 0.9};
        int sensitivity_J = 10;

        // Small-scale instances and their available category counts
        int[] J_instances = {5, 10, 15, 20};
        int[][] C_instances = {
                {2, 3, 4}, {2, 3, 4}, {3, 4, 5}, {4, 5, 6}
        };

        String outPutName = "F2_CLFE_Comparison";
        Path outDir = Paths.get("src/main/Results/[3] Result_F2_CLFE");
        List<Object[]> summary_storage = new ArrayList<>();
        List<Object[]> category_storage = new ArrayList<>();
        List<Object[]> data_storage = new ArrayList<>();
        List<Object[]> learning_storage = new ArrayList<>();
        List<Object[]> learning_data_storage = new ArrayList<>();
        List<List<Double>> learning_gaps = new ArrayList<>();
        for (int l = 0; l < L_instances.length; l++) {
            learning_gaps.add(new ArrayList<>());
        }
        Map<Integer, List<Double>> category_gaps = new TreeMap<>();

        for (int ins = 0; ins < J_instances.length; ins++) {
            int J = J_instances[ins];
            List<Double> gaps = new ArrayList<>();
            String inDir = J <= 20 ? "Two-Machine-Instances" : "Two-Machine-LargeInstances";

            for (int C : C_instances[ins]) {
                for (int itt = 1; itt <= Ite; itt++) {
                    // Read processing times and category information
                    Path filePath = Paths.get("src/main/Instances", inDir,
                            J + "-" + C + "-" + M + "-" + itt + ".txt");
                    double[][] PT = new double[J][M];
                    int[] NumCate = new int[C];
                    readInstance(filePath, PT, NumCate, p_min, p_max);
                    double[] makespans = compareSchedules(PT, NumCate, L, t1, t2, delta, p_min, p_max);
                    double makespan1 = makespans[0], makespan2 = makespans[1];
                    double gap = (makespan1 - makespan2) / makespan2 * 100;
                    gaps.add(gap);
                    category_gaps.computeIfAbsent(C, key -> new ArrayList<>()).add(gap);
                    data_storage.add(new Object[]{data_storage.size() + 1, J, C, M, itt,
                            makespan1, makespan2, gap});

                    // Fix the sensitivity_J inputs and recompute the BJ rule for each L.
                    if (J == sensitivity_J) {
                        for (int l = 0; l < L_instances.length; l++) {
                            double[] result = L_instances[l] == L ? makespans
                                    : compareSchedules(PT, NumCate, L_instances[l], t1, t2, delta, p_min, p_max);
                            double learning_gap = (result[0] - result[1]) / result[1] * 100;
                            learning_gaps.get(l).add(learning_gap);
                            learning_data_storage.add(new Object[]{learning_data_storage.size() + 1,
                                    L_instances[l], J, C, M, itt, result[0], result[1], learning_gap});
                        }
                    }
                }
            }

            // Summarize all instances
            double[] stats = summarize(gaps); // Max, min, mean, std
            summary_storage.add(new Object[]{J, gaps.size(), stats[0], stats[1], stats[2], stats[3]});
        }

        // Pool all available job numbers for each category count.
        for (int C : category_gaps.keySet()) {
            List<Double> gaps = category_gaps.get(C);
            double[] stats = summarize(gaps);
            category_storage.add(new Object[]{C, gaps.size(), stats[0], stats[1], stats[2], stats[3]});
        }

        for (int l = 0; l < L_instances.length; l++) {
            List<Double> gaps = learning_gaps.get(l);
            double[] stats = summarize(gaps);
            learning_storage.add(new Object[]{L_instances[l], sensitivity_J, gaps.size(),
                    stats[0], stats[1], stats[2], stats[3]});
        }

        // Save all results
        Files.createDirectories(outDir);
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet1 = workbook.createSheet("Sheet 1");
            Sheet sheet2 = workbook.createSheet("Sheet 2");
            Sheet sheet3 = workbook.createSheet("Sheet 3");
            Sheet sheet4 = workbook.createSheet("Sheet 4");
            Sheet sheet5 = workbook.createSheet("Sheet 5");
            writeTable(sheet1, new String[]{"Jobs", "Instances", "gap_CLFE-max (%)", "gap_CLFE-min (%)",
                    "gap_CLFE-ave (%)", "gap_CLFE-std (%)"}, summary_storage);
            writeTable(sheet2, new String[]{"Index", "Jobs", "Categories", "Machines", "Instance",
                    "pi_1 Cmax (CLFE)", "pi_2 Cmax (CLFE)", "gap_CLFE (%)"}, data_storage);
            writeTable(sheet3, new String[]{"Categories", "Instances", "gap_CLFE-max (%)", "gap_CLFE-min (%)",
                    "gap_CLFE-ave (%)", "gap_CLFE-std (%)"}, category_storage);
            writeTable(sheet4, new String[]{"L", "Jobs", "Instances", "gap_CLFE-max (%)", "gap_CLFE-min (%)",
                    "gap_CLFE-ave (%)", "gap_CLFE-std (%)"}, learning_storage);
            writeTable(sheet5, new String[]{"Index", "L", "Jobs", "Categories", "Machines", "Instance",
                    "pi_1 Cmax (CLFE)", "pi_2 Cmax (CLFE)", "gap_CLFE (%)"}, learning_data_storage);
            Path tablePath = outDir.resolve(outPutName + ".xlsx");
            try (OutputStream output = Files.newOutputStream(tablePath)) {
                workbook.write(output);
            }
        }

        System.out.println("Excel file saved in " + outDir.toAbsolutePath());
    }

    // Build Johnson's and BJ schedules, then evaluate both with different L.
    private static double[] compareSchedules(double[][] PT, int[] NumCate, double L, double t1, double t2,
                                             int delta, int p_min, int p_max) throws IOException {
        int J = PT.length, C = NumCate.length, M = PT[0].length;

        // Separate jobs into Type1 and Type2
        List<Integer> Type1 = new ArrayList<>();
        List<Integer> Type2 = new ArrayList<>();
        for (int job = 1; job <= J; job++) {
            if (PT[job - 1][0] <= PT[job - 1][1]) {
                Type1.add(job);
            } else {
                Type2.add(job);
            }
        }
        // Stable sorting preserves the original order when keys are equal.
        Type1.sort(Comparator.comparingDouble(a -> PT[a - 1][0]));

        // Johnson's rule without CLFEs gives pi_1
        List<Integer> T1 = new ArrayList<>(Type2);
        T1.sort(Comparator.comparingDouble((Integer a) -> PT[a - 1][1]).reversed());
        List<Integer> pi_1 = new ArrayList<>(Type1);
        pi_1.addAll(T1);

        // BJ rule with CLFEs gives pi_2
        double[] score = new double[C];
        int firstJob = 0;
        for (int cate = 0; cate < C; cate++) {
            double p1 = PT[firstJob][0];
            double p2 = PT[firstJob][1];
            double alpha1 = Math.log(t1 + t2 * (p1 - p_min) / (p_max - p_min)) / Math.log(2);
            double alpha2 = Math.log(t1 + t2 * (p2 - p_min) / (p_max - p_min)) / Math.log(2);
            double end = p2 * (L + (1 - L) * Math.pow(NumCate[cate], alpha2));
            double sum = p1;
            for (int r = 1; r <= NumCate[cate]; r++) {
                sum += p2 * (L + (1 - L) * Math.pow(r, alpha2))
                        - p1 * (L + (1 - L) * Math.pow(r, alpha1));
            }
            score[cate] = Math.max(end, sum);
            firstJob += NumCate[cate];
        }
        List<Integer> T2 = new ArrayList<>(Type2);
        T2.sort((a, b) -> Double.compare(score[Calculation.getPartNumber(b, NumCate)],
                score[Calculation.getPartNumber(a, NumCate)]));
        List<Integer> pi_2 = new ArrayList<>(Type1);
        pi_2.addAll(T2);

        // Evaluate the schedules under the same CLFEs
        double makespan1 = Calculation.traverseSequence(pi_1, PT, NumCate, C, delta, M, p_max, p_min, L, t1, t2);
        double makespan2 = Calculation.traverseSequence(pi_2, PT, NumCate, C, delta, M, p_max, p_min, L, t1, t2);
        if (!Double.isFinite(makespan1) || !Double.isFinite(makespan2) || makespan2 <= 0) {
            throw new IOException("Invalid makespan for L=" + L);
        }
        return new double[]{makespan1, makespan2};
    }

    // Each category is one consecutive block in the input file
    private static void readInstance(Path filePath, double[][] PT, int[] NumCate, int p_min, int p_max) throws IOException {
        int row = 0;
        int cate = -1;
        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] values = line.trim().split("\\s+");
                if (values.length != 2 || row >= PT.length) {
                    throw new IOException("Invalid row count or columns: " + filePath);
                }
                for (int m = 0; m < 2; m++) {
                    PT[row][m] = Double.parseDouble(values[m]);
                    if (!Double.isFinite(PT[row][m]) || PT[row][m] <= 0 || PT[row][m] < p_min || PT[row][m] > p_max) {
                        throw new IOException("Invalid processing time: " + filePath);
                    }
                }
                if (row == 0 || PT[row][0] != PT[row - 1][0] || PT[row][1] != PT[row - 1][1]) {
                    cate++;
                }
                if (cate >= NumCate.length) {
                    throw new IOException("Too many categories: " + filePath);
                }
                NumCate[cate]++;
                row++;
            }
        } catch (NumberFormatException e) {
            throw new IOException("Invalid number: " + filePath, e);
        }
        if (row != PT.length || cate + 1 != NumCate.length) {
            throw new IOException("Job or category count does not match the filename: " + filePath);
        }
    }

    private static double[] summarize(List<Double> gaps) {
        double max = Collections.max(gaps);
        double min = Collections.min(gaps);
        double mean = gaps.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double variance = 0;
        for (double gap : gaps) {
            variance += Math.pow(gap - mean, 2);
        }
        double std = Math.sqrt(variance / gaps.size()); // Population std (ddof = 0)
        return new double[]{max, min, mean, std};
    }

    // Write numerical results
    private static void writeTable(Sheet sheet, String[] headers, List<Object[]> data_storage) {
        Row headerRow = sheet.createRow(0);
        for (int col = 0; col < headers.length; col++) {
            headerRow.createCell(col).setCellValue(headers[col]);
        }
        for (int row = 0; row < data_storage.size(); row++) {
            Row dataRow = sheet.createRow(row + 1);
            Object[] data = data_storage.get(row);
            for (int col = 0; col < data.length; col++) {
                Cell cell = dataRow.createCell(col);
                if (data[col] instanceof Number) {
                    cell.setCellValue(((Number) data[col]).doubleValue());
                } else {
                    cell.setCellValue((String) data[col]);
                }
            }
        }
    }

}
