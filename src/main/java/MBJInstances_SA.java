// This file provides sensitivity analysis for the MBJ algorithm.

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;
import java.util.stream.Stream;

public class MBJInstances_SA {
    private static final int SAMPLES = 20;
    private static final int[] DELTAS = {1, 2, 4};
    private static final String[] ALGORITHMS = {"MBJ", "SPT", "NEH", "CDS", "IG"};
    private static final String[] COMPARATORS = {"SPT", "NEH", "CDS", "IG", "Algo"};
    private static final Pattern FILE_NAME = Pattern.compile("(\\d+)-(\\d+)-(\\d+)-(\\d+)\\.txt");
    private record CaseFile(Path path, int categories, int machines, int index) {}

    private static final class Stats {
        int count;
        double sum, min = Double.POSITIVE_INFINITY;
        void add(double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite result");
            count++; sum += value; min = Math.min(min, value);
        }
        double mean() {
            if (count == 0) throw new IllegalStateException("No observations");
            return sum / count;
        }
    }

    private static final class Combination {
        final Stats[] makespans = new Stats[ALGORITHMS.length];
        final Stats[] gaps = new Stats[COMPARATORS.length];
        Combination() {
            Arrays.setAll(makespans, i -> new Stats());
            Arrays.setAll(gaps, i -> new Stats());
        }
        double add(List<ComparisonAlgorithms.Result> results) {
            if (results.size() != ALGORITHMS.length) throw new IllegalStateException("Missing algorithms");
            double[] times = new double[ALGORITHMS.length];
            for (int i = 0; i < times.length; i++) {
                ComparisonAlgorithms.Result result = results.get(i);
                if (!result.algorithm.equals(ALGORITHMS[i]) || !Double.isFinite(result.makespan)
                        || result.makespan <= 0) throw new IllegalStateException("Invalid algorithm result");
                times[i] = result.makespan;
                makespans[i].add(times[i]);
            }
            double bestOther = Double.POSITIVE_INFINITY;
            for (int i = 1; i < times.length; i++) {
                gaps[i - 1].add(percentDifference(times[i], times[0]));
                bestOther = Math.min(bestOther, times[i]);
            }
            gaps[gaps.length - 1].add(percentDifference(bestOther, times[0]));
            return times[0];
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = BJInstances_SA.OUTPUT;
        for (String arg : args) {
            if (arg.startsWith("--output=")) output = Paths.get(arg.substring(9));
            else if (arg.startsWith("--samples=")) {
                if (Integer.parseInt(arg.substring(10)) != SAMPLES)
                    throw new IllegalArgumentException("Use all 20 instances in each group");
            } else throw new IllegalArgumentException("Unknown argument: " + arg);
        }
        Map<Integer, List<CaseFile>> byJobs = discoverInstances();
        long expected = byJobs.values().stream().mapToLong(List::size).sum()
                * BJInstances_SA.A_LEVELS.length * BJInstances_SA.L_LEVELS.length * DELTAS.length;
        long started = System.nanoTime();
        AtomicLong completed = new AtomicLong();
        List<Object[]> rows = Collections.synchronizedList(new ArrayList<>());
        rows.add(header());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, Runtime.getRuntime().availableProcessors()));
        List<Future<?>> tasks = new ArrayList<>();
        System.out.printf(Locale.ROOT, "Starting %d instance-parameter comparisons%n", expected);
        for (Map.Entry<Integer, List<CaseFile>> entry : byJobs.entrySet()) {
            int jobs = entry.getKey();
            List<CaseFile> files = entry.getValue();
            String categories = levels(files, true), machines = levels(files, false);
            for (double a : BJInstances_SA.A_LEVELS) {
                for (double l : BJInstances_SA.L_LEVELS) {
                    tasks.add(pool.submit(() -> {
                        try {
                            Combination[] combinations = new Combination[DELTAS.length];
                            Arrays.setAll(combinations, i -> new Combination());
                            for (CaseFile file : files) {
                                ComparisonAlgorithms.Instance instance = MBJInstance_comparison.readInstance(
                                        file.path(), "Sensitivity", l, a, BJInstances_SA.B);
                                double mbj = Double.NaN;
                                for (int d = 0; d < DELTAS.length; d++) {
                                    ComparisonAlgorithms.IgParameters ig =
                                            new ComparisonAlgorithms.IgParameters(100, 4, 0.4, 42L);
                                    double value = combinations[d].add(ComparisonAlgorithms.compare(instance, DELTAS[d], ig));
                                    if (d == 0) mbj = value;
                                    else if (Double.compare(mbj, value) != 0)
                                        throw new IllegalStateException("MBJ must be independent of delta");
                                    completed.incrementAndGet();
                                }
                            }
                            for (int d = 0; d < DELTAS.length; d++) {
                                if (combinations[d].makespans[0].count != files.size())
                                    throw new IllegalStateException("Incomplete parameter setting");
                                rows.add(row(jobs, categories, machines, a, l, DELTAS[d], combinations[d]));
                            }
                            System.out.printf(Locale.ROOT, "n=%d a=%.1f L=%.1f: %d/%d completed, elapsed %.1f s%n",
                                    jobs, a, l, completed.get(), expected, (System.nanoTime() - started) / 1e9);
                            System.out.flush();
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    }));
                }
            }
        }
        pool.shutdown();
        for (Future<?> task : tasks) task.get();
        if (completed.get() != expected) throw new IllegalStateException("Incomplete analysis");
        rows.subList(1, rows.size()).sort(Comparator.comparingInt((Object[] r) -> ((Number) r[0]).intValue())
                .thenComparingDouble(r -> ((Number) r[3]).doubleValue())
                .thenComparingDouble(r -> ((Number) r[4]).doubleValue())
                .thenComparingInt(r -> ((Number) r[6]).intValue()));
        Map<Integer, Stats> scores = new TreeMap<>();
        for (Object[] r : rows.subList(1, rows.size()))
            scores.computeIfAbsent(((Number) r[0]).intValue(), ignored -> new Stats())
                    .add(((Number) r[r.length - 2]).doubleValue());
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestJobs = -1;
        for (Map.Entry<Integer, Stats> entry : scores.entrySet()) {
            if (entry.getValue().count != BJInstances_SA.A_LEVELS.length * BJInstances_SA.L_LEVELS.length * DELTAS.length)
                throw new IllegalStateException("Incomplete scale");
            double score = entry.getValue().mean();
            System.out.printf(Locale.ROOT, "n=%d overall average gap_Algo=%.12f%%%n", entry.getKey(), score);
            if (score > bestScore) { bestScore = score; bestJobs = entry.getKey(); }
        }
        Path file = output.resolve("MBJInstances_SA.xlsx");
        BJInstances_SA.writeWorkbook(file, rows);
        System.out.printf(Locale.ROOT, "Selected n=%d; average gap_Algo=%.12f%%%n", bestJobs, bestScore);
        System.out.printf(Locale.ROOT, "Saved %d settings after %.1f s: %s%n",
                rows.size() - 1, (System.nanoTime() - started) / 1e9, file);
    }

    private static Map<Integer, List<CaseFile>> discoverInstances() throws IOException {
        Map<Integer, List<CaseFile>> byJobs = new TreeMap<>();
        Map<String, Set<Integer>> groups = new TreeMap<>();
        for (String folder : new String[]{"M-Machine-Instances", "M-Machine-LargeInstances"}) {
            try (Stream<Path> stream = Files.list(Paths.get("src/main/Instances", folder))) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    Matcher match = FILE_NAME.matcher(path.getFileName().toString());
                    if (!match.matches()) continue;
                    int n = Integer.parseInt(match.group(1)), g = Integer.parseInt(match.group(2));
                    int m = Integer.parseInt(match.group(3)), index = Integer.parseInt(match.group(4));
                    if (m <= 2 || index > SAMPLES) continue;
                    if (index < 1) throw new IOException("Invalid instance index: " + path);
                    String key = n + ":" + g + ":" + m;
                    if (!groups.computeIfAbsent(key, ignored -> new TreeSet<>()).add(index))
                        throw new IOException("Duplicate instance: " + key + ":" + index);
                    byJobs.computeIfAbsent(n, ignored -> new ArrayList<>()).add(new CaseFile(path, g, m, index));
                }
            }
        }
        if (byJobs.isEmpty()) throw new IOException("No multi-machine instances found");
        for (Map.Entry<String, Set<Integer>> group : groups.entrySet())
            if (group.getValue().size() != SAMPLES)
                throw new IOException("Expected instances 1 to 20 for " + group.getKey());
        Comparator<CaseFile> order = Comparator.comparingInt(CaseFile::categories)
                .thenComparingInt(CaseFile::machines).thenComparingInt(CaseFile::index);
        for (List<CaseFile> files : byJobs.values()) files.sort(order);
        return byJobs;
    }

    private static String levels(List<CaseFile> files, boolean categories) {
        Set<Integer> values = new TreeSet<>();
        for (CaseFile file : files) values.add(categories ? file.categories() : file.machines());
        String text = values.toString(); return text.substring(1, text.length() - 1);
    }
    private static Object[] header() {
        List<Object> columns = new ArrayList<>(Arrays.asList("n", "G Values", "M Values", "a", "L", "b", "delta",
                "Instances", "MBJ Algorithm Makespan", "SPT Makespan", "NEH Makespan", "CDS Makespan", "IG Makespan"));
        for (String algorithm : COMPARATORS) {
            columns.add("gap_" + algorithm + " Mean (%)"); columns.add("gap_" + algorithm + " Min (%)");
        }
        return columns.toArray();
    }
    private static Object[] row(int jobs, String categories, String machines, double a, double l,
                                int delta, Combination result) {
        List<Object> values = new ArrayList<>(Arrays.asList(jobs, categories, machines, a, l, BJInstances_SA.B,
                delta, result.makespans[0].count));
        for (Stats stats : result.makespans) values.add(stats.mean());
        for (Stats stats : result.gaps) { values.add(stats.mean()); values.add(stats.min); }
        return values.toArray();
    }
    private static double percentDifference(double value, double reference) {
        double result = 100.0 * (value - reference) / reference;
        return Math.abs(result) < 1e-10 ? 0 : result;
    }
}