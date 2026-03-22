import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Math.min;

public class BJInstances_Delta {
    public static void main(String[] args) throws IOException {
        /*  Number of iterations  */
        int Ite = 5;

        // Files optput path

//        // Small-scale
//        int[] J_instances = {5,10,15,20};
////        int[] J_instances = {5};
//        int[] D_instances = {0,1,2,3,4};
//        int[] C_instances = {2,3,4};
//        String outPutName = "F2-BJ-Small-scale-Change-delta";
//        String ReadPath = "src/main/Two-Machine-Instances/";

        // Large-scale
        int[] J_instances = {40,60,80,100};
        int[] D_instances = {5,10,15,20};
        int[] C_instances = {4,5,6,7,8,9,10};
        String outPutName = "F2-BJ-Large-scale-Change-delta";
        String ReadPath = "src/main/Two-Machine-LargeInstances/";

        // Solving Process
        String outPath = "src/main/Results/" +outPutName+".xlsx";
        // Head of the table
        String[] headers = new String[] {"Index", "delta", "Jobs", "Categories", "Machines",
                "Gap0-ave","Gap0-max","Gap1-ave","Gap1-max","Gap2-ave","Gap2-max",
                "Delta1-ave","Delta1-max","Delta2-ave","Delta2-max","CPU(Ms)"};
        // A class for storing data. Need a close() at the end
        Workbook workbook = new XSSFWorkbook();
        // Create a sheet and a container to record results
        Sheet sheet = workbook.createSheet(outPutName);
        List<Object[]> data_storage = new ArrayList<>();

        /* Parameters */
        double L = 0.7;  // Lower bound of Dejong learning curve
        double t1 = 0.7;  // Paramenter in learning rate function
        double t2 = 0.2;  // Paramenter in learning rate function

        /* Instance  */
        int M = 2;  // Machine number
        int p_min = 0;  // Minimum processing time
        int p_max = 100; // Maximum processing time

        // Interrupt flag
        Random rand = new Random();
        // Record the result of each scale
        double[][] Gap0 = new double[J_instances.length * C_instances.length* D_instances.length][2]; // Benefit of CLFE = (optPFSPandCLFE-opt)* 100/opt, average and maximum
        double[][] Gap1 = new double[J_instances.length * C_instances.length* D_instances.length][2]; // Benefit of CLFE = (optPFSP-opt)* 100/opt, average and maximum
        double[][] Gap2 = new double[J_instances.length * C_instances.length* D_instances.length][2]; // Gap = (optBW-opt)* 100/opt, average and maximum
        double[][] Delta1 = new double[J_instances.length * C_instances.length* D_instances.length][2]; // Delta1 = (opt-LB)* 100/opt, average and maximum
        double[][] Delta2 = new double[J_instances.length * C_instances.length* D_instances.length][2]; // Delta2 = (opt-LB)* 100/LB, average and maximum
        double[] averTime = new double[J_instances.length * C_instances.length* D_instances.length];
        // Iterate through each example, and each example cycles Ite times
        int ins = 0;
        for(int delta: D_instances) {
            for (int J : J_instances) {
                if (J == 15) {
                    C_instances = new int[]{3, 4, 5};
                } else if (J == 20) {
                    C_instances = new int[]{4, 5, 6};
                }else if(J <= 10) {
                    C_instances = new int[]{2,3,4};
                }
                for (int C : C_instances) {
                    System.out.println(J + "----" + C);
                    // For each random instance, record the following infomation:
                    double[] optPFSP = new double[Ite]; // makespan of the optimal schedule without CLFE
                    double[] optPC = new double[Ite];  // makespan of the optimal schedule without CLFE, then consider the CLFE
                    double[] optBW = new double[Ite];  // makespan of optimal batch-wise schedule
                    double[] opt = new double[Ite];  // makespan of optimal schedule
                    double[] LB = new double[Ite];  // lower bound of optimal value
                    double[] cputime = new double[Ite];  // makespan of optimal schedule

                    for (int itt = 1; itt <= Ite; itt++) {

                        /*---- Step 1 & 2: Read the well-designed instances ----*/

                        /*---- Step 1: Generate a sequence and category information ----*/
                        List<Integer> Jobs = IntStream.rangeClosed(1, J).boxed().collect(Collectors.toList());
                        // Divide Jobs into C parts
                        int[] NumCate = new int[C]; // Number of jobs in each category
                        /*---- Step 2: Generate random values p1 and p2 for each part and create PT ----*/
                        double[][] PT = new double[J][M];
                        // Read path
                        String filePath = ReadPath + J + "-" + C + "-" + M + "-" + itt + ".txt";
                        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
                            String line;
                            int rowIndex = 0;
                            // Sorting the Mapping of each category
                            Map<String, Integer> rowMap = new LinkedHashMap<>();

                            // Read the .txt file by rows
                            while ((line = br.readLine()) != null) {
                                // Spilt numbers by space
                                String[] values = line.trim().split("\\s+");
                                double num1 = Double.parseDouble(values[0]);
                                double num2 = Double.parseDouble(values[1]);
                                // Processing time
                                PT[rowIndex][0] = num1;
                                PT[rowIndex][1] = num2;
                                // Set key
                                String rowKey = num1 + " " + num2;
                                // Update the value of keys
                                rowMap.put(rowKey, rowMap.getOrDefault(rowKey, 0) + 1);
                                rowIndex++;
                            }
                            // Put the values of each key in the Hashmap to NumCate array
                            int index = 0;
                            for (Integer count : rowMap.values()) {
                                NumCate[index++] = count;
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        /*---- Step 3: Separate into Type1 and Type2 and sort to form T1 and T2 ----*/
                        List<Integer> Type1 = new ArrayList<>();
                        List<Integer> Type2 = new ArrayList<>();

                        for (int i = 0; i < J; i++) {
                            if (PT[i][0] > PT[i][1]) {
                                Type2.add(Jobs.get(i));
                            } else {
                                Type1.add(Jobs.get(i));
                            }
                        }

                        /*----  Step 4: Sorting  ----*/
                        // Sort Type1 array by p1 in ascending order to form T1
                        List<Integer> T1 = new ArrayList<>(Type1);

                        // Time
                        long startTime = System.nanoTime();
                        // Ascending order
                        T1.sort(Comparator.comparingDouble(a -> PT[a - 1][0]));

                        // Sort Type2 array by max{--,-} in descending order to form T2
                        List<Integer> T2 = new ArrayList<>(Type2);
                        T2.sort((a, b) -> {
                            // Get the category information of batches a and b
                            int partNumA = Calculation.getPartNumber(a, NumCate);
                            int partNumB = Calculation.getPartNumber(b, NumCate);
                            // Calculating the learning rate
                            double alpha_a_m1 = Math.log(t1 + t2 * (PT[a - 1][0] - p_min) / (p_max - p_min)) / Math.log(2);
                            double alpha_a_m2 = Math.log(t1 + t2 * (PT[a - 1][1] - p_min) / (p_max - p_min)) / Math.log(2);
                            double alpha_b_m1 = Math.log(t1 + t2 * (PT[b - 1][0] - p_min) / (p_max - p_min)) / Math.log(2);
                            double alpha_b_m2 = Math.log(t1 + t2 * (PT[b - 1][1] - p_min) / (p_max - p_min)) / Math.log(2);
                            // Actual processing time of the last job in each category
                            double endA = PT[a - 1][1] * (L + (1 - L) * Math.pow(NumCate[partNumA], alpha_a_m2));
                            double endB = PT[b - 1][1] * (L + (1 - L) * Math.pow(NumCate[partNumB], alpha_b_m2));
                            // P_{g,1} + \bar{P}_{g,2} - \bar{P}_{g,1}
                            double score1 = PT[a - 1][0];
                            double score2 = PT[b - 1][0];
                            // NumCate: number of jobs in a category
                            // For cate A
                            for (int i = 1; i <= NumCate[partNumA]; i++) {
                                score1 += PT[a - 1][1] * (L + (1 - L) * Math.pow(i, alpha_a_m2)) - PT[a - 1][0] * (L + (1 - L) * Math.pow(i, alpha_a_m1));
                            }
                            // For cate B
                            for (int i = 1; i <= NumCate[partNumB]; i++) {
                                score2 += PT[b - 1][1] * (L + (1 - L) * Math.pow(i, alpha_b_m2)) - PT[b - 1][0] * (L + (1 - L) * Math.pow(i, alpha_b_m1));
                            }
                            // Final score of the two categories
                            double scoreA = Math.max(endA, score1);
                            double scoreB = Math.max(endB, score2);
                            // Sort in non-increasing order
                            return Double.compare(scoreB, scoreA);
                        });

                        // Form optimal batch-wise schedule T3 by concatenating T1 and T2
                        List<Integer> T3 = new ArrayList<>();
                        T3.addAll(T1);
                        T3.addAll(T2);
                        long endTime = System.nanoTime();
                        long durationMicro = (endTime - startTime) / 1000;
                        cputime[itt - 1] = durationMicro;

                        // Calculate the makespan of optimal batch-wise schedule
                        double optMS = Calculation.traverseSequence(T3, PT, NumCate, C, delta, M, p_max, p_min);
                        optBW[itt - 1] = optMS;

                        // Find the optimal schedule of conresponding F2/Prmu/Cmax, i.e. T4
                        List<Integer> T4_2 = new ArrayList<>(Type2);
                        // Decreasing order
                        T4_2.sort(Comparator.comparingDouble(a -> PT[(Integer) a - 1][1]).reversed());
                        List<Integer> T4 = new ArrayList<>();
                        T4.addAll(T1);
                        T4.addAll(T2);
                        double opt_pfsp = Calculation.traverseSequence_PFSP(T4, PT, M);
                        optPFSP[itt - 1] = opt_pfsp;
                        double opt_pc = Calculation.traverseSequence(T4, PT, NumCate, C, delta,M,p_max,p_min);
                        optPC[itt-1] = opt_pc;

                        /* --- Step 5: Generate all permutations of the Jobs sequence ----*/
                        // Find a schedule better than the optimal batch-wise schedule
                        double makespan = optMS;
                        List<Integer> optJobs = null;
                        if (J < 12) {  // Small-scale
                            // List all of the permutations of the discussing problem
                            List<List<Integer>> permsJobs = Calculation.permute(Jobs);
                            for (List<Integer> jobs : permsJobs) {
                                // Calculate makespan
                                double m_per = Calculation.traverseSequence(jobs, PT, NumCate, C, delta, M, p_max, p_min);
                                // Better or not ?
                                if (m_per < makespan - 0.000001) {
                                    makespan = m_per;
                                    // break;
                                    optJobs = jobs;
                                }
                            }
                        } else {  // Large-scale, don't search it!
                            makespan = -1;
                        }

                                //the optimal value(Non-batch-wise)
                        opt[itt - 1] = makespan;

//                    /* --- Step 6: Calculate the lower bound ----*/
//                    // Calculate the actual processing time of jobs in each category
                        double[][] PT_CLE = Arrays.stream(PT)
                                .map(double[]::clone)
                                .toArray(double[][]::new);  // Equivalent processing time in DJ rule, deep copy
                        List<Integer> TDJ = new ArrayList<>(Type2);
                        //DJ rule, modify the actual processing time
                        int former = -1; // Former category index
                        int counter = 1; // counter-th consecutive job in same category
                        double alpha_a_m1;
                        double alpha_a_m2;
                        for (int i : TDJ) {
                            // Get category and learning rate
                            int cate = Calculation.getPartNumber(i, NumCate);
                            alpha_a_m1 = Math.log(t1 + t2 * (PT[i - 1][0] - p_min) / (p_max - p_min)) / Math.log(2);
                            alpha_a_m2 = Math.log(t1 + t2 * (PT[i - 1][1] - p_min) / (p_max - p_min)) / Math.log(2);
                            // Modify
                            if (cate != former) {
                                // Number of jobs in this category
                                counter = 1;
                                PT_CLE[i - 1][0] = PT[i - 1][0] * (L + (1 - L) * Math.pow(counter, alpha_a_m1));
                                PT_CLE[i - 1][1] = PT[i - 1][1] * (L + (1 - L) * Math.pow(counter, alpha_a_m2));
                            } else {
                                counter++;
                                PT_CLE[i - 1][0] = PT[i - 1][0] * (L + (1 - L) * Math.pow(counter, alpha_a_m1));
                                PT_CLE[i - 1][1] = PT[i - 1][1] * (L + (1 - L) * Math.pow(counter, alpha_a_m2));
                            }
                            former = cate;
                        }
                        // Construct and Calculate the lower bound by DCJ rule
                        TDJ.sort(Comparator.comparingDouble(a -> PT_CLE[(Integer) a - 1][1]).reversed());
                        List<Integer> TDC = new ArrayList<>();
                        TDC.addAll(T1);
                        TDC.addAll(TDJ);
                        // Calculate lower bound
                        double lb = Calculation.traverseSequence(TDC, PT, NumCate, C, 0, M, p_max, p_min);

//                    Calculation.printArray(PT);
                        double lb2 = optMS;
                        if (J < 12) {  // Small-scale
                            // List all of the permutations of the discussing problem
                            List<List<Integer>> permsJobs = Calculation.permute(Jobs);
                            for (List<Integer> jobs : permsJobs) {
                                // Calculate makespan
                                double m_per = Calculation.traverseSequence(jobs, PT, NumCate, C, 0, M, p_max, p_min);
                                // Better or not ?
                                if (m_per < lb2 - 0.000001) {
                                    lb2 = m_per;
                                }
                            }
                            LB[itt - 1] = min(lb, lb2);
                        } else {
                            LB[itt - 1] = lb;
                        }
                    }

                    // Summarize results of instances in each scale
                    // Calculate the differences
                    double[] differences = new double[Ite];
                    double sum = 0.0;
                    double max = 0.0;
                    double mean = 0.0;

                    /*---- Calculate Gap0  ----*/
                    if(J<=12){
                        for (int i = 0; i < Ite; i++) {
                            differences[i] = (optPC[i] - opt[i]) * 100  / opt[i];
                        }
                    }else{
                        for (int i = 0; i < Ite; i++) {
                            differences[i] = (optPC[i] - optBW[i])  * 100 / optBW[i];
                        }
                    }
                    // Mean and Max
                    sum = 0.0;
                    max = differences[0];
                    for (int i = 0; i < Ite; i++) {
                        sum += differences[i];
                        if (differences[i] > max) {
                            max = differences[i];
                        }
                    }
                    mean = sum / Ite;
                    // Store them in array
                    Gap0[ins][0] = mean;
                    Gap0[ins][1] = max;

                    /*---- Calculate Gap1  ----*/
                    if (J <= 12) {
                        for (int i = 0; i < Ite; i++) {
                            differences[i] = (optPFSP[i] - opt[i]) * 100 / opt[i];
                        }
                    } else {
                        for (int i = 0; i < Ite; i++) {
                            differences[i] = (optPFSP[i] - optBW[i]) * 100 / optBW[i];
                        }
                    }

                    // Time
                    sum = 0.0;
                    for (int i = 0; i < Ite; i++) {
                        sum += cputime[i];
                    }
                    mean = sum / Ite;
                    averTime[ins] = mean;

                    // Mean and Max
                    sum = 0.0;
                    max = differences[0];
                    for (int i = 0; i < Ite; i++) {
                        sum += differences[i];
                        if (differences[i] > max) {
                            max = differences[i];
                        }
                    }
                    mean = sum / Ite;
                    // Store them in array
                    Gap1[ins][0] = mean;
                    Gap1[ins][1] = max;

                    /*---- Calculate Gap2  ----*/
                    for (int i = 0; i < Ite; i++) {
                        differences[i] = (optBW[i] - opt[i])* 100 / opt[i];
                    }
                    // Mean and Max
                    sum = 0.0;
                    max = differences[0];
                    for (int i = 0; i < Ite; i++) {
                        sum += differences[i];
                        if (differences[i] > max) {
                            max = differences[i];
                        }
                    }
                    mean = sum / Ite;
                    // Store them in array
                    Gap2[ins][0] = mean;
                    Gap2[ins][1] = max;

                    /*---- Calculate Delta1  ----*/
                    for (int i = 0; i < Ite; i++) {
                        differences[i] = (optBW[i] - LB[i])* 100 / opt[i];
                    }
                    // Mean and Max
                    sum = 0.0;
                    max = differences[0];
                    for (int i = 0; i < Ite; i++) {
                        sum += differences[i];
                        if (differences[i] > max) {
                            max = differences[i];
                        }
                    }
                    mean = sum / Ite;
                    // Store them in array
                    Delta1[ins][0] = mean;
                    Delta1[ins][1] = max;

                    /*---- Calculate Delta2  ----*/
                    for (int i = 0; i < Ite; i++) {
                        differences[i] = (optBW[i] - LB[i]) * 100 / LB[i];
                    }
                    // Mean and Max
                    sum = 0.0;
                    max = differences[0];
                    for (int i = 0; i < Ite; i++) {
                        sum += differences[i];
                        if (differences[i] > max) {
                            max = differences[i];
                        }
                    }
                    mean = sum / Ite;
                    // Store them in array
                    Delta2[ins][0] = mean;
                    Delta2[ins][1] = max;

                    // Data storage
                    Object[] data_s = new Object[]{ins, delta, J, C, M, Gap0[ins][0], Gap0[ins][1], Gap1[ins][0], Gap1[ins][1], Gap2[ins][0], Gap2[ins][1],
                            Delta1[ins][0], Delta1[ins][1], Delta2[ins][0], Delta2[ins][1], averTime[ins]};
                    data_storage.add(data_s);
                    System.out.println(Arrays.toString(data_s));

                    // The end
                    ins++;
                }
            }
        }

        // Output results into an excel
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        for (int row = 0; row < data_storage.size(); row++) {
            Row dataRow = sheet.createRow(row + 1);
            Object[] data =  data_storage.get(row);
            for (int col = 0; col < data.length; col++) {
                Cell cell = dataRow.createCell(col);
                if (data[col] instanceof String) {
                    cell.setCellValue((String) data[col]);
                } else if (data[col] instanceof Integer) {
                    cell.setCellValue((Integer) data[col]);
                }else if (data[col] instanceof Double) {
                    cell.setCellValue((Double) data[col]);
                }
            }
        }
        try  {
            File file = new File(outPath);
            FileOutputStream fileOut = new FileOutputStream(file);
            workbook.write(fileOut);
            System.out.println("Excel file has been generated successfully!");
        } catch (IOException e) {
            e.printStackTrace();
        }
        workbook.close();
    }
}
