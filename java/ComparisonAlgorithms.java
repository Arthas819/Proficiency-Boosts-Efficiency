// In this file, we solve the input instance by the proposed method or benchmarks
// We used generative AI to help us write this code, but all generated codes were carefully checked and revised by authors.

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

final class ComparisonAlgorithms {
    static final double L = 0.7, T1 = 0.7, T2 = 0.2;
    private static final double LOG_TWO = Math.log(2.0);

    private ComparisonAlgorithms() { }

    static final class Instance {
        final int jobs, categories, machines, index;
        final double l, t1, t2;
        final String scale;
        final Path path;
        final double[][] pt;
        final int[] counts, category;
        private final int[] first;
        // actual[category][rank][machine].
        private final double[][][] actual;
        private final double[][] learnedTotal;
        private final Map<String, Construction> nominalCache = new HashMap<>();
        private final Map<String, Result> batchCache = new HashMap<>();
        private final Map<Integer, Double> clfeReferenceCache = new HashMap<>();
        private double batchReference = Double.NaN;
        private double noForgettingReference = Double.NaN;

        Instance(Path path, String scale, int jobs, int categories, int machines,
                 int index, double[][] pt, int[] counts) {
            this(path, scale, jobs, categories, machines, index, pt, counts, L, T1, T2);
        }

        Instance(Path path, String scale, int jobs, int categories, int machines,
                 int index, double[][] pt, int[] counts, double l, double t1, double t2) {
            if (jobs < 1 || categories < 1 || machines < 2 || pt.length != jobs
                    || counts.length != categories) {
                throw new IllegalArgumentException("Invalid instance dimensions: " + path);
            }
            if (!Double.isFinite(l) || l < 0 || l > 1 || !Double.isFinite(t1) || t1 <= 0
                    || !Double.isFinite(t2) || t2 < 0 || t1 + t2 > 1) {
                throw new IllegalArgumentException("Require 0 <= L <= 1, t1 > 0, t2 >= 0, t1+t2 <= 1");
            }
            this.path = path;
            this.scale = scale;
            this.jobs = jobs;
            this.categories = categories;
            this.machines = machines;
            this.index = index;
            this.l = l;
            this.t1 = t1;
            this.t2 = t2;
            this.pt = new double[jobs][machines];
            this.counts = counts.clone();
            this.category = new int[jobs];
            this.first = new int[categories];
            this.actual = new double[categories][][];
            this.learnedTotal = new double[categories][machines];
            int job = 0;
            for (int c = 0; c < categories; c++) {
                if (counts[c] <= 0 || counts[c] > jobs - job) {
                    throw new IllegalArgumentException("Invalid category size: " + path);
                }
                first[c] = job;
                for (int r = 0; r < counts[c]; r++, job++) {
                    if (pt[job].length != machines) {
                        throw new IllegalArgumentException("Invalid machine count: " + path);
                    }
                    category[job] = c;
                    for (int m = 0; m < machines; m++) {
                        double p = pt[job][m];
                        if (!Double.isFinite(p) || p < 0 || p > 100) {
                            throw new IllegalArgumentException("Processing time outside [0,100]: " + path);
                        }
                        if (r > 0 && Double.compare(p, pt[first[c]][m]) != 0) {
                            throw new IllegalArgumentException("Category must have identical processing times: " + path);
                        }
                        this.pt[job][m] = p;
                    }
                }
                actual[c] = new double[counts[c] + 1][machines];
                for (int m = 0; m < machines; m++) {
                    double p = this.pt[first[c]][m];
                    double alpha = Math.log(t1 + t2 * p / 100.0) / LOG_TWO;
                    for (int r = 1; r <= counts[c]; r++) {
                        double value = p * (l + (1.0 - l) * Math.pow(r, alpha));
                        actual[c][r][m] = value;
                        learnedTotal[c][m] += value;
                    }
                }
            }
            if (job != jobs) {
                throw new IllegalArgumentException("Category sizes do not sum to jobs: " + path);
            }
        }

        List<Integer> jobs() {
            List<Integer> result = new ArrayList<>(jobs);
            for (int j = 1; j <= jobs; j++) result.add(j);
            return result;
        }
    }

    static final class Result {
        final String algorithm;
        final List<Integer> sequence;
        final double makespan, millis;

        Result(String algorithm, List<Integer> sequence, double makespan, double millis) {
            this.algorithm = algorithm;
            this.sequence = Collections.unmodifiableList(new ArrayList<>(sequence));
            this.makespan = makespan;
            this.millis = millis;
        }
    }

    // IG search parameters.
    static final class IgParameters {
        final int iterations, destruction;
        final double temperature;
        final long seed;

        IgParameters(int iterations, int destruction, double temperature, long seed) {
            if (iterations < 0 || destruction < 1 || !Double.isFinite(temperature) || temperature < 0) {
                throw new IllegalArgumentException(
                        "IG requires iterations >= 0, destruction >= 1, and finite temperature >= 0");
            }
            this.iterations = iterations;
            this.destruction = destruction;
            this.temperature = temperature;
            this.seed = seed;
        }
    }

    // Reference values for gap_opt, gap_OBW and Delta.
    static final class ReferenceValues {
        final double clfeExact, batchExact, noForgettingExact;

        ReferenceValues(double clfeExact, double batchExact, double noForgettingExact) {
            this.clfeExact = clfeExact;
            this.batchExact = batchExact;
            this.noForgettingExact = noForgettingExact;
        }
    }

    static ReferenceValues referenceValues(Instance x, int delta, int exactMaxJobs,
                                            int batchExactMaxCategories) {
        if (delta < 0) throw new IllegalArgumentException("delta must be nonnegative");
        Evaluator evaluator = new Evaluator(x, delta);
        String proposed = x.machines == 2 ? "BJ" : "MBJ";
        Result seed = batchResult(x, proposed, evaluator,
                () -> x.machines == 2 ? bj(x) : mbj(x, evaluator, false));
        if (x.machines == 2 && Double.isNaN(x.noForgettingReference))
            x.noForgettingReference = evaluate(x, noForgettingJohnson(x), 0);

        boolean exactAvailable = exactMaxJobs > 0 && x.jobs <= exactMaxJobs;
        if (exactAvailable && !x.clfeReferenceCache.containsKey(delta))
            x.clfeReferenceCache.put(delta, new ExactSearch(x, delta, seed).solve());

        boolean batchAvailable = batchExactMaxCategories > 0 && x.categories <= batchExactMaxCategories;
        if (batchAvailable && Double.isNaN(x.batchReference))
            x.batchReference = x.machines == 2 ? seed.makespan : new BatchReferenceSearch(x, seed).solve();

        return new ReferenceValues(
                exactAvailable ? x.clfeReferenceCache.get(delta) : Double.NaN,
                batchAvailable ? x.batchReference : Double.NaN,
                x.machines == 2 ? x.noForgettingReference : Double.NaN);
    }

    // PJ: batch G1 by normal p1; rank G2 jobs by learned p2.
    private static List<Integer> noForgettingJohnson(Instance x) {
        List<Integer> firstCategories = new ArrayList<>(), lastJobs = new ArrayList<>();
        for (int c = 0; c < x.categories; c++) {
            if (x.pt[x.first[c]][0] <= x.pt[x.first[c]][1]) firstCategories.add(c + 1);
            else {
                for (int r = 1; r <= x.counts[c]; r++) lastJobs.add(x.first[c] + r);
            }
        }
        firstCategories.sort(Comparator.comparingDouble(c -> x.pt[x.first[c - 1]][0]));
        lastJobs.sort(Comparator.comparingDouble((Integer job) -> {
            int c = x.category[job - 1];
            int rank = job - x.first[c];
            return x.actual[c][rank][1];
        }).reversed());
        List<Integer> sequence = expandCategories(x, firstCategories);
        sequence.addAll(lastJobs);
        return sequence;
    }

    // Current baselines, evaluated under the same CLFE model.
    static List<Result> compare(Instance x, int delta, IgParameters config) {
        if (delta < 0) throw new IllegalArgumentException("delta must be nonnegative");
        if (config == null) throw new IllegalArgumentException("IG parameters are required");
        Evaluator evaluator = new Evaluator(x, delta);
        List<Result> results = new ArrayList<>();
        String proposed = x.machines == 2 ? "BJ" : "MBJ";
        results.add(batchResult(x, proposed, evaluator,
                () -> x.machines == 2 ? bj(x) : mbj(x, evaluator, false)));
        results.add(nominalResult(x, "SPT", evaluator, () -> {
            List<Integer> order = x.jobs();
            order.sort(Comparator.comparingDouble(j -> total(x.pt[j - 1])));
            return order;
        }));
        Result neh = timed("NEH", evaluator, () -> neh(x, evaluator));
        results.add(neh);
        if (x.machines > 2) {
            results.add(timed("CDS", evaluator, () -> cds(x, evaluator)));
        }
        Result ig = iteratedGreedy(x, evaluator, neh.sequence, config);
        results.add(new Result("IG", ig.sequence, ig.makespan, neh.millis + ig.millis));
        for (Result result : results) validateResult(x, result);
        return results;
    }

    // Retain warm-up results.
    private static volatile double timingSink;

    // Fresh runs with fixed seeds; median time excludes setup and reference searches.
    static List<Result> benchmarkCompare(Instance x, int delta, IgParameters config, int repetitions) {
        validateTimingArguments(x, delta, config);
        if (repetitions < 1) throw new IllegalArgumentException("Timing repetitions must be positive");
        String[] algorithms = timingAlgorithms(x);
        Result[] objectives = new Result[algorithms.length];
        double[][] samples = new double[algorithms.length][repetitions];
        for (int repetition = 0; repetition < repetitions; repetition++) {
            int first = (int) Math.floorMod((long) x.index + repetition, algorithms.length);
            for (int position = 0; position < algorithms.length; position++) {
                int algorithm = (first + position) % algorithms.length;
                long start = System.nanoTime();
                Result result = uncachedTimingRun(x, delta, config, algorithms[algorithm]);
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                // Exclude validation from timing.
                validateResult(x, result);
                Result previous = objectives[algorithm];
                if (previous != null && (Double.doubleToLongBits(previous.makespan)
                        != Double.doubleToLongBits(result.makespan)
                        || !previous.sequence.equals(result.sequence))) {
                    throw new IllegalStateException("Repeated benchmark changed the deterministic result: "
                            + algorithms[algorithm]);
                }
                objectives[algorithm] = result;
                samples[algorithm][repetition] = elapsed;
            }
        }
        List<Result> results = new ArrayList<>(algorithms.length);
        for (int algorithm = 0; algorithm < algorithms.length; algorithm++) {
            Arrays.sort(samples[algorithm]);
            int middle = repetitions / 2;
            double median = repetitions % 2 == 0
                    ? (samples[algorithm][middle - 1] + samples[algorithm][middle]) / 2.0
                    : samples[algorithm][middle];
            Result result = objectives[algorithm];
            results.add(new Result(result.algorithm, result.sequence, result.makespan, median));
        }
        return results;
    }

    // Experiment-wide JVM warm-up without cached results.
    static void warmUpTiming(Instance x, int delta, IgParameters config, int repetitions) {
        validateTimingArguments(x, delta, config);
        if (repetitions < 0) throw new IllegalArgumentException("Warm-up repetitions must be nonnegative");
        String[] algorithms = timingAlgorithms(x);
        for (int repetition = 0; repetition < repetitions; repetition++) {
            int first = (int) Math.floorMod((long) x.index + repetition, algorithms.length);
            for (int position = 0; position < algorithms.length; position++) {
                Result result = uncachedTimingRun(x, delta, config,
                        algorithms[(first + position) % algorithms.length]);
                validateResult(x, result);
                timingSink = result.makespan + result.sequence.get(0);
            }
        }
    }

    private static void validateTimingArguments(Instance x, int delta, IgParameters config) {
        if (x == null) throw new IllegalArgumentException("A timing instance is required");
        if (delta < 0) throw new IllegalArgumentException("delta must be nonnegative");
        if (config == null) throw new IllegalArgumentException("IG parameters are required");
    }

    private static String[] timingAlgorithms(Instance x) {
        return x.machines == 2 ? new String[]{"BJ", "SPT", "NEH", "IG"}
                : new String[]{"MBJ", "SPT", "NEH", "CDS", "IG"};
    }

    // Time fresh construction; IG includes a fresh NEH initialization.
    private static Result uncachedTimingRun(Instance x, int delta, IgParameters config, String algorithm) {
        Evaluator evaluator = new Evaluator(x, delta);
        if (algorithm.equals("IG")) {
            List<Integer> initialization = neh(x, evaluator);
            return iteratedGreedy(x, evaluator, initialization, config);
        }
        List<Integer> sequence = switch (algorithm) {
            case "BJ" -> bj(x);
            case "MBJ" -> mbj(x, evaluator, false);
            case "SPT" -> {
                List<Integer> order = x.jobs();
                order.sort(Comparator.comparingDouble(job -> total(x.pt[job - 1])));
                yield order;
            }
            case "NEH" -> neh(x, evaluator);
            case "CDS" -> cds(x, evaluator);
            default -> throw new IllegalArgumentException("Unknown timing algorithm: " + algorithm);
        };
        double makespan = evaluator.clfe(toArray(sequence), sequence.size());
        return new Result(algorithm, sequence, makespan, 0);
    }

    // Basic IG
    private static Result iteratedGreedy(Instance x, Evaluator evaluator,
                                        List<Integer> nehSequence, IgParameters parameters) {
        long start = System.nanoTime();
        int[] current = toArray(nehSequence);
        int[] candidate = new int[x.jobs];
        int[] best = current.clone();
        double currentValue = evaluator.clfe(current, x.jobs);
        double bestValue = currentValue;
        int destruction = Math.min(parameters.destruction, x.jobs);
        int[] removed = new int[destruction];
        Random random = new Random(parameters.seed);
        double nominalTotal = 0;
        for (double[] processing : x.pt) nominalTotal += total(processing);
        double temperature = parameters.temperature * (nominalTotal / (10.0 * x.jobs * x.machines));
        InsertionSearch insertion = new InsertionSearch(x, evaluator.delta);
        for (int iteration = 0; iteration < parameters.iterations; iteration++) {
            System.arraycopy(current, 0, candidate, 0, x.jobs);
            int size = x.jobs;
            for (int r = 0; r < destruction; r++) {
                int position = random.nextInt(size);
                removed[r] = candidate[position];
                System.arraycopy(candidate, position + 1, candidate, position, size - position - 1);
                size--;
            }
            double candidateValue = Double.POSITIVE_INFINITY;
            for (int job : removed) {
                candidateValue = insertion.insert(candidate, size, job);
                size++;
            }
            if (candidateValue < bestValue) {
                bestValue = candidateValue;
                System.arraycopy(candidate, 0, best, 0, x.jobs);
            }
            boolean accept = candidateValue < currentValue
                    || (temperature > 0 && random.nextDouble()
                    <= Math.exp((currentValue - candidateValue) / temperature));
            if (accept) {
                int[] swap = current;
                current = candidate;
                candidate = swap;
                currentValue = candidateValue;
            }
        }
        List<Integer> sequence = toList(best);
        double makespan = evaluator.clfe(best, x.jobs);
        double millis = (System.nanoTime() - start) / 1_000_000.0;
        return new Result("IG", sequence, makespan, millis);
    }

    private static final class InsertionSearch {
        final Instance x;
        final int delta;
        final int[][] prefixLearning;
        final double[][] prefixComplete;
        final int[] learning;
        final double[] complete;

        InsertionSearch(Instance x, int delta) {
            this.x = x;
            this.delta = delta;
            prefixLearning = new int[x.jobs + 1][x.categories];
            prefixComplete = new double[x.jobs + 1][x.machines];
            learning = new int[x.categories];
            complete = new double[x.machines];
        }

        double insert(int[] sequence, int size, int job) {
            Arrays.fill(prefixLearning[0], 0);
            Arrays.fill(prefixComplete[0], 0);
            for (int position = 0; position < size; position++) {
                System.arraycopy(prefixLearning[position], 0,
                        prefixLearning[position + 1], 0, x.categories);
                System.arraycopy(prefixComplete[position], 0,
                        prefixComplete[position + 1], 0, x.machines);
                append(prefixLearning[position + 1], prefixComplete[position + 1],
                        x.category[sequence[position] - 1]);
            }
            int category = x.category[job - 1];
            int bestPosition = 0;
            double bestValue = Double.POSITIVE_INFINITY;
            for (int position = 0; position <= size; position++) {
                // Equal-category runs give equivalent insertions.
                if (position > 0 && x.category[sequence[position - 1] - 1] == category) continue;
                System.arraycopy(prefixLearning[position], 0, learning, 0, x.categories);
                System.arraycopy(prefixComplete[position], 0, complete, 0, x.machines);
                append(learning, complete, category);
                for (int next = position; next < size; next++) {
                    append(learning, complete, x.category[sequence[next] - 1]);
                }
                double value = complete[x.machines - 1];
                if (value < bestValue) {
                    bestValue = value;
                    bestPosition = position;
                }
            }
            System.arraycopy(sequence, bestPosition, sequence, bestPosition + 1, size - bestPosition);
            sequence[bestPosition] = job;
            return bestValue;
        }

        void append(int[] learning, double[] complete, int category) {
            for (int other = 0; other < x.categories; other++) {
                if (other == category) learning[other]++;
                else learning[other] = Math.max(0, learning[other] - delta);
            }
            double[] processing = x.actual[category][learning[category]];
            complete[0] += processing[0];
            for (int machine = 1; machine < x.machines; machine++) {
                complete[machine] = Math.max(complete[machine], complete[machine - 1]) + processing[machine];
            }
        }
    }

    static double evaluate(Instance x, List<Integer> seq, int delta) {
        if (delta < 0) throw new IllegalArgumentException("delta must be nonnegative");
        return new Evaluator(x, delta).clfe(toArray(seq), seq.size());
    }

    static double lowerBound(Instance x) {
        double best = 0;
        double[] load = new double[x.machines];
        double[] head = new double[x.machines];
        double[] tail = new double[x.machines];
        Arrays.fill(head, Double.POSITIVE_INFINITY);
        Arrays.fill(tail, Double.POSITIVE_INFINITY);
        for (int c = 0; c < x.categories; c++) {
            double[] floor = x.actual[c][x.counts[c]];
            double chain = total(floor);
            best = Math.max(best, total(x.pt[x.first[c]]));
            double prefix = 0;
            for (int m = 0; m < x.machines; m++) {
                load[m] += x.learnedTotal[c][m];
                head[m] = Math.min(head[m], prefix);
                tail[m] = Math.min(tail[m], chain - prefix - floor[m]);
                prefix += floor[m];
            }
        }
        for (int m = 0; m < x.machines; m++) {
            best = Math.max(best, head[m] + load[m] + tail[m]);
        }
        return best;
    }

    private static Result timed(String name, Evaluator evaluator, Supplier<List<Integer>> construct) {
        long start = System.nanoTime();
        List<Integer> seq = construct.get();
        double makespan = evaluator.clfe(toArray(seq), seq.size());
        return new Result(name, seq, makespan, (System.nanoTime() - start) / 1_000_000.0);
    }

    private static Result batchResult(Instance x, String name, Evaluator evaluator,
                                      Supplier<List<Integer>> construct) {
        Result cached = x.batchCache.get(name);
        if (cached != null) return cached;
        Result result = timed(name, evaluator, construct);
        if (!isBatch(x, result.sequence)) {
            throw new IllegalStateException(name + " must construct a batch schedule");
        }
        x.batchCache.put(name, result);
        return result;
    }

    private static Result nominalResult(Instance x, String name, Evaluator evaluator,
                                        Supplier<List<Integer>> construct) {
        Construction construction = x.nominalCache.get(name);
        if (construction == null) {
            long start = System.nanoTime();
            List<Integer> seq = construct.get();
            construction = new Construction(seq, (System.nanoTime() - start) / 1_000_000.0, isBatch(x, seq));
            x.nominalCache.put(name, construction);
        }
        if (construction.batch && construction.result != null) return construction.result;
        long start = System.nanoTime();
        double makespan = evaluator.clfe(toArray(construction.sequence), construction.sequence.size());
        Result result = new Result(name, construction.sequence, makespan,
                construction.millis + (System.nanoTime() - start) / 1_000_000.0);
        if (construction.batch) construction.result = result;
        return result;
    }

    private static final class Construction {
        final List<Integer> sequence;
        final double millis;
        final boolean batch;
        Result result;
        Construction(List<Integer> sequence, double millis, boolean batch) {
            this.sequence = sequence;
            this.millis = millis;
            this.batch = batch;
        }
    }

    private static List<Integer> bj(Instance x) {
        double[] p1 = column(x, 0), p2 = column(x, 1);
        double[] score = new double[x.jobs];
        for (int c = 0; c < x.categories; c++) {
            double value = Math.max(x.actual[c][x.counts[c]][1],
                    x.pt[x.first[c]][0] + x.learnedTotal[c][1] - x.learnedTotal[c][0]);
            Arrays.fill(score, x.first[c], x.first[c] + x.counts[c], value);
        }
        return splitOrder(x.jobs(), p1, p2, score);
    }

    // MBJ algorithm
    private static List<Integer> mbj(Instance x, Evaluator evaluator, boolean legacy) {
        List<Integer> best = null;
        double bestValue = Double.POSITIVE_INFINITY;
        for (int split = 1; split < x.machines; split++) {
            List<Integer> candidate;
            if (legacy) {
                double[] e1 = new double[x.jobs], e2 = new double[x.jobs], score = new double[x.jobs];
                for (int c = 0; c < x.categories; c++) {
                    int first = x.first[c];
                    double prefix = 0, suffix = 0, learnedPrefix = 0, learnedSuffix = 0, end = 0;
                    for (int m = 0; m < x.machines; m++) {
                        if (m < split) prefix += x.pt[first][m];
                        else suffix += x.pt[first][m];
                        // Preserve the original last-machine omission.
                        if (m == x.machines - 1) continue;
                        if (m < split) learnedPrefix += x.learnedTotal[c][m];
                        else {
                            learnedSuffix += x.learnedTotal[c][m];
                            end += x.actual[c][x.counts[c]][m];
                        }
                    }
                    double s = Math.max(end, x.pt[first][split - 1] + learnedSuffix - learnedPrefix);
                    Arrays.fill(e1, first, first + x.counts[c], prefix);
                    Arrays.fill(e2, first, first + x.counts[c], suffix);
                    Arrays.fill(score, first, first + x.counts[c], s);
                }
                candidate = splitOrder(x.jobs(), e1, e2, score);
            } else {
                double[] e1 = new double[x.categories], e2 = new double[x.categories];
                List<Integer> categories = new ArrayList<>();
                for (int c = 0; c < x.categories; c++) {
                    categories.add(c + 1);
                    double[] p = x.pt[x.first[c]];
                    double total = total(p);
                    if (p[split - 1] <= p[split]) {
                        for (int m = 0; m < split; m++) e1[c] += p[m];
                        e2[c] = total;
                    } else {
                        e1[c] = total;
                        double boundary = p[split - 1];
                        double end = 0;
                        for (int m = split; m < x.machines; m++) {
                            boundary += x.learnedTotal[c][m];
                            end += x.actual[c][x.counts[c]][m];
                        }
                        for (int m = split - 1; m < x.machines - 1; m++) {
                            boundary -= x.learnedTotal[c][m];
                        }
                        e2[c] = Math.max(boundary, end);
                    }
                }
                candidate = expandCategories(x, johnson(categories, e1, e2));
            }
            double value = evaluator.clfe(toArray(candidate), x.jobs);
            if (value < bestValue) {
                bestValue = value;
                best = candidate;
            }
        }
        return best;
    }

    private static List<Integer> johnson(List<Integer> jobs, double[] first, double[] second) {
        return splitOrder(jobs, first, second, second);
    }

    private static List<Integer> splitOrder(List<Integer> jobs, double[] first,
                                             double[] second, double[] secondKey) {
        List<Integer> left = new ArrayList<>(), right = new ArrayList<>();
        for (int j : jobs) {
            if (first[j - 1] <= second[j - 1]) left.add(j);
            else right.add(j);
        }
        left.sort(Comparator.comparingDouble(j -> first[j - 1]));
        right.sort(Comparator.comparingDouble((Integer j) -> secondKey[j - 1]).reversed());
        left.addAll(right);
        return left;
    }

    // NEH algorithm
    private static List<Integer> neh(Instance x, Evaluator evaluator) {
        List<Integer> priority = x.jobs();
        priority.sort(Comparator.comparingDouble((Integer j) -> total(x.pt[j - 1])).reversed());
        int[] current = new int[x.jobs], trial = new int[x.jobs];
        int size = 0;
        for (int job : priority) {
            int bestPosition = 0;
            double bestValue = Double.POSITIVE_INFINITY;
            for (int position = 0; position <= size; position++) {
                // Equal-category runs give equivalent insertions.
                if (position > 0 && x.category[current[position - 1] - 1] == x.category[job - 1]) continue;
                System.arraycopy(current, 0, trial, 0, position);
                trial[position] = job;
                System.arraycopy(current, position, trial, position + 1, size - position);
                double value = evaluator.clfe(trial, size + 1);
                if (value < bestValue) {
                    bestValue = value;
                    bestPosition = position;
                }
            }
            System.arraycopy(current, bestPosition, current, bestPosition + 1, size - bestPosition);
            current[bestPosition] = job;
            size++;
        }
        return toList(current);
    }

    // CDS algorithm
    private static List<Integer> cds(Instance x, Evaluator evaluator) {
        List<Integer> best = null;
        double bestValue = Double.POSITIVE_INFINITY;
        for (int k = 1; k < x.machines; k++) {
            double[] first = new double[x.jobs], second = new double[x.jobs];
            for (int j = 0; j < x.jobs; j++) {
                for (int m = 0; m < k; m++) {
                    first[j] += x.pt[j][m];
                    second[j] += x.pt[j][x.machines - 1 - m];
                }
            }
            List<Integer> candidate = johnson(x.jobs(), first, second);
            double value = evaluator.clfe(toArray(candidate), x.jobs);
            if (value < bestValue) {
                bestValue = value;
                best = candidate;
            }
        }
        return best;
    }

    private static final class Evaluator {
        final Instance x;
        final int delta;
        final int[] learning;
        final double[] complete;

        Evaluator(Instance x, int delta) {
            this.x = x;
            this.delta = delta;
            this.learning = new int[x.categories];
            this.complete = new double[x.machines];
        }

        double clfe(int[] sequence, int length) {
            Arrays.fill(learning, 0);
            Arrays.fill(complete, 0);
            for (int i = 0; i < length; i++) {
                int job = sequence[i] - 1;
                if (job < 0 || job >= x.jobs) throw new IllegalArgumentException("Invalid job identifier");
                int c = x.category[job];
                for (int other = 0; other < x.categories; other++) {
                    if (other == c) learning[other]++;
                    else learning[other] = Math.max(0, learning[other] - delta);
                }
                if (learning[c] > x.counts[c]) throw new IllegalArgumentException("Repeated category jobs");
                double[] p = x.actual[c][learning[c]];
                complete[0] += p[0];
                for (int m = 1; m < x.machines; m++) {
                    complete[m] = Math.max(complete[m], complete[m - 1]) + p[m];
                }
            }
            return complete[x.machines - 1];
        }

    }

    // Searching exact optimal batch-wise schedule
    private static final class BatchReferenceSearch {
        final Instance x;
        final int full;
        final double[][][] transfer;
        final double[][] load, minTail;
        final double[][][] minHead;
        final List<double[]>[] frontiers;
        final double[][][] candidates;
        final int[][] candidateCategories;
        final double[][] candidateBounds;
        final int[] order, priority;
        final double pruningSlack;
        final Result seed;
        int[] bestOrder;
        double bestValue;

        @SuppressWarnings("unchecked")
        BatchReferenceSearch(Instance x, Result seed) {
            if (x.categories >= 30) {
                throw new IllegalArgumentException("Batch exact search supports fewer than 30 categories");
            }
            this.x = x;
            this.seed = seed;
            full = (1 << x.categories) - 1;
            transfer = new double[x.categories][x.machines][x.machines];
            load = new double[full + 1][x.machines];
            minTail = new double[full + 1][x.machines];
            minHead = new double[full + 1][x.machines][x.machines];
            frontiers = (List<double[]>[]) new List<?>[full + 1];
            candidates = new double[x.categories][x.categories][x.machines];
            candidateCategories = new int[x.categories][x.categories];
            candidateBounds = new double[x.categories][x.categories];
            order = new int[x.categories];
            priority = categoryOrder(x, seed.sequence);
            bestOrder = priority.clone();
            bestValue = seed.makespan;
            // Slack protects pruning against roundoff.
            pruningSlack = 1e-10 * Math.max(1.0, bestValue);
            initializeTransfers();
            initializeSubsetBounds();
        }

        double solve() {
            search(0, 0, new double[x.machines]);
            List<Integer> categories = new ArrayList<>(x.categories);
            for (int c : bestOrder) categories.add(c + 1);
            double evaluated = evaluate(x, expandCategories(x, categories), 0);
            return Math.min(seed.makespan, evaluated);
        }

        void initializeTransfers() {
            for (int c = 0; c < x.categories; c++) {
                for (int firstMachine = 0; firstMachine < x.machines; firstMachine++) {
                    double[] completion = new double[x.machines];
                    for (int r = 1; r <= x.counts[c]; r++) {
                        completion[firstMachine] += x.actual[c][r][firstMachine];
                        for (int m = firstMachine + 1; m < x.machines; m++) {
                            completion[m] = Math.max(completion[m], completion[m - 1]) + x.actual[c][r][m];
                        }
                    }
                    for (int m = firstMachine; m < x.machines; m++) {
                        transfer[c][firstMachine][m] = completion[m];
                    }
                }
            }
        }

        void initializeSubsetBounds() {
            Arrays.fill(minTail[0], Double.POSITIVE_INFINITY);
            for (double[] row : minHead[0]) Arrays.fill(row, Double.POSITIVE_INFINITY);
            for (int mask = 1; mask <= full; mask++) {
                int c = Integer.numberOfTrailingZeros(mask);
                int previous = mask & (mask - 1);
                double[] nominal = x.pt[x.first[c]], floor = x.actual[c][x.counts[c]];
                double suffix = 0;
                for (int m = x.machines - 1; m >= 0; m--) {
                    load[mask][m] = load[previous][m] + x.learnedTotal[c][m];
                    minTail[mask][m] = Math.min(minTail[previous][m], suffix);
                    suffix += floor[m];
                }
                for (int firstMachine = 0; firstMachine < x.machines; firstMachine++) {
                    double head = 0;
                    for (int m = firstMachine; m < x.machines; m++) {
                        minHead[mask][firstMachine][m] = Math.min(
                                minHead[previous][firstMachine][m], head);
                        head += nominal[m];
                    }
                }
            }
        }

        void append(double[] incoming, int c, double[] outgoing) {
            for (int m = 0; m < x.machines; m++) {
                double value = Double.NEGATIVE_INFINITY;
                for (int previousMachine = 0; previousMachine <= m; previousMachine++) {
                    value = Math.max(value, incoming[previousMachine] + transfer[c][previousMachine][m]);
                }
                outgoing[m] = value;
            }
        }

        double bound(double[] completion, int remaining) {
            if (remaining == 0) return completion[x.machines - 1];
            double bound = completion[x.machines - 1];
            for (int m = 0; m < x.machines; m++) {
                double earliest = completion[m];
                // The first remaining job uses nominal processing times.
                for (int k = 0; k < m; k++) {
                    earliest = Math.max(earliest, completion[k] + minHead[remaining][k][m]);
                }
                bound = Math.max(bound, earliest + load[remaining][m] + minTail[remaining][m]);
            }
            return bound;
        }

        boolean dominated(int processed, double[] completion) {
            List<double[]> frontier = frontiers[processed];
            if (frontier == null) {
                frontier = new ArrayList<>();
                frontiers[processed] = frontier;
            }
            for (double[] previous : frontier) {
                if (dominates(previous, completion)) return true;
            }
            for (int i = frontier.size() - 1; i >= 0; i--) {
                if (dominates(completion, frontier.get(i))) frontier.remove(i);
            }
            frontier.add(completion.clone());
            return false;
        }

        boolean dominates(double[] first, double[] second) {
            for (int m = 0; m < x.machines; m++) {
                if (first[m] > second[m]) return false;
            }
            return true;
        }

        void search(int processed, int depth, double[] completion) {
            if (processed == full) {
                if (completion[x.machines - 1] < bestValue) {
                    bestValue = completion[x.machines - 1];
                    bestOrder = order.clone();
                }
                return;
            }
            int remaining = full ^ processed;
            if (bound(completion, remaining) > bestValue + pruningSlack || dominated(processed, completion)) return;
            int count = 0;
            for (int c : priority) {
                if ((remaining & (1 << c)) == 0) continue;
                double[] candidate = candidates[depth][c];
                append(completion, c, candidate);
                int childRemaining = remaining ^ (1 << c);
                double lowerBound = bound(candidate, childRemaining);
                if (lowerBound > bestValue + pruningSlack) continue;
                int position = count;
                while (position > 0 && lowerBound < candidateBounds[depth][position - 1]) {
                    candidateBounds[depth][position] = candidateBounds[depth][position - 1];
                    candidateCategories[depth][position] = candidateCategories[depth][position - 1];
                    position--;
                }
                candidateBounds[depth][position] = lowerBound;
                candidateCategories[depth][position] = c;
                count++;
            }
            for (int i = 0; i < count; i++) {
                if (candidateBounds[depth][i] > bestValue + pruningSlack) continue;
                int c = candidateCategories[depth][i];
                order[depth] = c;
                search(processed | (1 << c), depth + 1, candidates[depth][c]);
            }
        }
    }

    private static int[] categoryOrder(Instance x, List<Integer> sequence) {
        int[] result = new int[x.categories];
        int previous = -1, index = 0;
        for (int job : sequence) {
            int c = x.category[job - 1];
            if (c != previous) result[index++] = c;
            previous = c;
        }
        if (index != x.categories) throw new IllegalArgumentException("Reference seed must be a complete batch schedule");
        return result;
    }

    private static final class ExactSearch {
        final Instance x;
        final int delta;
        final int[] used;
        final int[][] learning;
        final double[][] completion;
        double bestValue;

        ExactSearch(Instance x, int delta, Result seed) {
            this.x = x;
            this.delta = delta;
            used = new int[x.categories];
            learning = new int[x.jobs + 1][x.categories];
            completion = new double[x.jobs + 1][x.machines];
            bestValue = seed.makespan;
        }

        double solve() {
            search(0);
            return bestValue;
        }

        void search(int position) {
            if (position == x.jobs) {
                bestValue = Math.min(bestValue, completion[position][x.machines - 1]);
                return;
            }
            for (int c = 0; c < x.categories; c++) {
                if (used[c] == x.counts[c]) continue;
                int[] nextLearning = learning[position + 1];
                for (int other = 0; other < x.categories; other++)
                    nextLearning[other] = other == c ? learning[position][other] + 1
                            : Math.max(0, learning[position][other] - delta);
                double[] next = completion[position + 1];
                System.arraycopy(completion[position], 0, next, 0, x.machines);
                double[] processing = x.actual[c][nextLearning[c]];
                next[0] += processing[0];
                for (int m = 1; m < x.machines; m++)
                    next[m] = Math.max(next[m], next[m - 1]) + processing[m];
                used[c]++;
                if (next[x.machines - 1] < bestValue) search(position + 1);
                used[c]--;
            }
        }
    }

    private static List<Integer> expandCategories(Instance x, List<Integer> order) {
        List<Integer> result = new ArrayList<>(x.jobs);
        for (int category : order) {
            int c = category - 1;
            for (int r = 1; r <= x.counts[c]; r++) result.add(x.first[c] + r);
        }
        return result;
    }

    private static boolean isBatch(Instance x, List<Integer> seq) {
        boolean[] seen = new boolean[x.categories];
        int previous = -1;
        for (int job : seq) {
            int c = x.category[job - 1];
            if (c == previous) continue;
            if (seen[c]) return false;
            seen[c] = true;
            previous = c;
        }
        return true;
    }

    private static void validateResult(Instance x, Result result) {
        if (result.sequence.size() != x.jobs || !Double.isFinite(result.makespan)
                || result.makespan < 0 || !Double.isFinite(result.millis) || result.millis < 0) {
            throw new IllegalStateException("Invalid result for " + result.algorithm + ": " + x.path);
        }
        boolean[] seen = new boolean[x.jobs];
        for (int job : result.sequence) {
            if (job < 1 || job > x.jobs || seen[job - 1]) {
                throw new IllegalStateException("Invalid permutation for " + result.algorithm + ": " + x.path);
            }
            seen[job - 1] = true;
        }
    }

    private static double[] column(Instance x, int machine) {
        double[] result = new double[x.jobs];
        for (int j = 0; j < x.jobs; j++) result[j] = x.pt[j][machine];
        return result;
    }

    private static double total(double[] values) {
        double result = 0;
        for (double value : values) result += value;
        return result;
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) result.add(value);
        return result;
    }
}
