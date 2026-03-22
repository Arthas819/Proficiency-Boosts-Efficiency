import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MBJInstance {
    public static void main(String[] args) throws IOException {
        /*  Number of iterations  */
        int Ite = 5;
        int itt;

        // Files optput path
        String outPutName = "FM-MBJ-Instances";
        String outPath = "src/main/Results/" +outPutName+".xlsx";
        // Head of the table
        String[] headers = new String[] {"Index", "Jobs", "Categories", "Machines",
                "Gap0-ave","Gap0-max", "Gap1-ave","Gap1-max","Gap2-ave","Gap2-max", "CPU(Ms)"};
        // A class for storing data. Need a close() at the end
        Workbook workbook = new XSSFWorkbook();
        // Create a sheet and a container to record results
        Sheet sheet = workbook.createSheet(outPutName);
        List<Object[]> data_storage = new ArrayList<>();

        /* Parameters */
        double L = 0.7;  // Lower bound of Dejong learning curve
        double t1 = 0.7;  // Paramenter in learning rate function
        double t2 = 0.2;  // Paramenter in learning rate function
        int delta = 1;  // Forgetting factor

        int[] J_instances = {5, 10, 15, 20};
        int[] C_instances = {2, 3, 4};
        int[] M_instances = {3, 4, 5};
        int p_min = 0;  // Minimum processing time
        int p_max = 100; // Maximum processing time

        // Interrupt flag
        Random rand = new Random();
        // Record the result of each scale
        double[][] Gap0 = new double[J_instances.length * C_instances.length * M_instances.length][2]; // Gap0 = (optPFSP-optMBJ)* 100/optMBJ, average and maximum
        double[][] Gap1 = new double[J_instances.length * C_instances.length * M_instances.length][2]; // Gap1 = (optMBJ-optBW) * 100/optBW, average and maximum
        double[][] Gap2 = new double[J_instances.length * C_instances.length * M_instances.length][2]; // Gap2 = (optMBJ-opt)* 100/opt, average and maximum
        double[] averTime = new double[J_instances.length * C_instances.length * M_instances.length];
        // Iterate through each example, and each example cycles Ite times
        int ins = 0;
        for (int J : J_instances){  // Job number
            if(J==15){
                C_instances = new int[]{3, 4, 5};
            } else if (J==20) {
                C_instances = new int[]{4, 5, 6};
            }
            for (int C : C_instances){  // Category number//
                for (int M : M_instances){  // Machine number
                    // Flag
                    System.out.println(J+"----"+C+"----"+M);
                    // For each random instance, record the following infomation:
                    double[] optMBJ = new double[Ite]; // makespan of the MBJ schedule without CLFE
                    double[] optBW = new double[Ite];  // makespan of optimal batch-wise schedule
                    double[] optPFSP = new double[Ite];  // makespan of PFSP without CLFE
                    double[] opt = new double[Ite];  // makespan of optimal schedule
                    double[] cputime = new double[Ite];  // makespan of optimal schedule
                    // For searching the optimal batch-wise schedule
                    List<Integer> batches = IntStream.rangeClosed(1, C).boxed().collect(Collectors.toList());

                    // Start
                    for (itt = 1; itt <= Ite; itt++) {
                        /*---- Step 1 & 2: Read the well-designed instances ----*/

                        /*---- Step 1: Generate a sequence and category information ----*/
                        List<Integer> Jobs = IntStream.rangeClosed(1, J).boxed().collect(Collectors.toList());
                        // Divide Jobs into C parts
                        int[] NumCate = new int[C]; // Number of jobs in each category
                        /*---- Step 2: Generate random values p1 and p2 for each part and create PT ----*/
                        double[][] PT = new double[J][M];
                        // Read path
                        String filePath = "src/main/M-Machine-Instances/"+J+"-"+C+"-"+M+"-"+itt+".txt";
                        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
                            String line;
                            int rowIndex = 0;
                            // Sorting the Mapping of each category
                            Map<String, Integer> rowMap = new LinkedHashMap<>();

                            // Read the .txt file by rows
                            while ((line = br.readLine()) != null) {
                                // Split numbers by space
                                String[] values = line.trim().split("\\s+");
                                if (values.length != M) {
                                    throw new IllegalArgumentException("not M!");
                                }
                                // 将 M 个数字赋值给 PT 的对应行
                                for (int i = 0; i < M; i++) {
                                    PT[rowIndex][i] = Double.parseDouble(values[i]);
                                }
                                // Set key
                                String rowKey = String.join(" ", values);
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

                        // Time
                        long startTime = System.nanoTime();
                        // CDS Algorithm
                        double optMBJCDS = 100*J*M;
                        for (int m = 0 ; m < M-1; m++){
                            /*---- Step 3: Equivalent Transformation ----*/
                            List<Integer> Type1 = new ArrayList<>();
                            List<Integer> Type2 = new ArrayList<>();
                            double[] E1 = new double[J];
                            double[] E2 = new double[J];
                            // Classification
                            for (int i = 0; i < J; i++){
                                for(int j = 0; j < M; j++){
                                    if(j<=m){
                                        E1[i] += PT[i][j];
                                    }
                                    else {
                                        E2[i] += PT[i][j];
                                    }
                                }
                                if (E1[i] > E2[i]) {
                                    Type2.add(Jobs.get(i));
                                } else {
                                    Type1.add(Jobs.get(i));
                                }
                            }

                            /*----  Step 4: Sorting  ----*/
                            // Sort Type1 array by p1 in ascending order to form T1
                            List<Integer> T1 = new ArrayList<>(Type1);
                            // Ascending order based on the 1~m sum of elements
                            T1.sort(Comparator.comparingDouble(a -> E1[a - 1]));

                            // Sort Type2 array by max{--,-} in descending order to form T2
                            List<Integer> T2 = new ArrayList<>(Type2);
                            int finalM = m;
                            T2.sort((a, b) -> {
                                // Get the category information of batches a and b
                                int partNumA = Calculation.getPartNumber(a, NumCate);
                                int partNumB = Calculation.getPartNumber(b, NumCate);

                                double[] PE1_sum = new double[2];
                                double[] PE2_sum = new double[2];
                                double[] PE2_las = new double[2];

                                for(int j = 0; j < M-1; j++){
                                    double alpha_a = Math.log(t1+t2*(PT[a - 1][j]-p_min)/(p_max-p_min)) / Math.log(2);
                                    double alpha_b = Math.log(t1+t2*(PT[b - 1][j]-p_min)/(p_max-p_min)) / Math.log(2);
                                    if(j<= finalM){
                                        // For cate A
                                        for(int i = 1; i<=NumCate[partNumA];i++){
                                            PE1_sum[0] += PT[a - 1][j]* (L + (1 - L) * Math.pow(i, alpha_a));
                                        }
                                        // For cate B
                                        for(int i = 1; i<=NumCate[partNumB];i++){
                                            PE1_sum[1] += PT[b - 1][j]* (L + (1 - L) * Math.pow(i, alpha_b));
                                        }
                                    }
                                    else {
                                        PE2_las[0] += PT[a - 1][j] * (L + (1 - L) * Math.pow(NumCate[partNumA], alpha_a));
                                        PE2_las[1] += PT[b - 1][j] * (L + (1 - L) * Math.pow(NumCate[partNumB], alpha_b));
                                        // For cate A
                                        for(int i = 1; i<=NumCate[partNumA];i++){
                                            PE2_sum[0] += PT[a - 1][j]* (L + (1 - L) * Math.pow(i, alpha_a));
                                        }
                                        // For cate B
                                        for(int i = 1; i<=NumCate[partNumB];i++){
                                            PE2_sum[1] += PT[b - 1][j]* (L + (1 - L) * Math.pow(i, alpha_b));
                                        }
                                    }
                                }
                                // Final score of the two categories
                                double scoreA = Math.max(PE2_las[0],PT[a-1][finalM]+PE2_sum[0]-PE1_sum[0]);
                                double scoreB = Math.max(PE2_las[1],PT[b-1][finalM]+PE2_sum[1]-PE1_sum[1]);
                                // Sort in non-increasing order
                                return Double.compare(scoreB,scoreA);
                            });
                            List<Integer> T3 = new ArrayList<>();
                            T3.addAll(T1);
                            T3.addAll(T2);
                            // Calculate the makespan of optimal batch-wise schedule
                            double optMS = Calculation.traverseSequence(T3, PT, NumCate, C, delta,M,p_max,p_min);
                            if(optMS < optMBJCDS-0.00001){
                                optMBJCDS = optMS;
                            }
                        }
                        long endTime = System.nanoTime();
                        long durationMicro = (endTime - startTime) / 1000;
                        cputime[itt-1] = durationMicro;
                        optMBJ[itt-1] = optMBJCDS;

                        /* --- Step 5: Generate all permutations of the Jobs sequence ----*/
                        // Find a schedule better than the optimal batch-wise schedule
                        double makespan = optMBJCDS+1;
                        if(J<12){  // Small-scale
                            // List all of the permutations of the discussing problem
                            List<List<Integer>> permsJobs = Calculation.permute(Jobs);
                            for (List<Integer> jobs : permsJobs) {
                                // Calculate makespan
                                double m_per = Calculation.traverseSequence(jobs, PT, NumCate, C, delta,M,p_max,p_min);
                                // Better or not ?
                                if (m_per < makespan - 0.000001) {
                                    makespan = m_per;
                                    // break;
                                }
                            }
                        }else{  // Large-scale, don't search it!
                            makespan = -1;
                        }

                        //the optimal value (Non-batch-wise)
                        opt[itt-1] = makespan;

                        // Find a schedule best for the PFSP without CLFE
                        double mp_pfsp = optMBJCDS + 300;
                        if (J < 12) {  // Small-scale
                            // List all of the permutations of the discussing problem
                            List<List<Integer>> permsJobs = Calculation.permute(Jobs);
                            for (List<Integer> jobs : permsJobs) {
                                // Calculate makespan
                                double m_per = Calculation.traverseSequence_PFSP(jobs, PT, M);
                                // Better or not ?
                                if (m_per < mp_pfsp - 0.000001) {
                                    mp_pfsp = m_per;
                                    // break;
                                }
                            }
                        } else {  // Large-scale, don't search it!
                            mp_pfsp = -1;
                        }
                        //the optimal value (Non-batch-wise)
                        optPFSP[itt - 1] = mp_pfsp;

                        // Calculate the makespan of the optimal batch-wise schedule
                        double optBaW = optMBJCDS+1;
                        // Jobs-Cate
                        Map<Integer, List<Integer>> jobsInCate = new HashMap<>();
                        int jobIndex = 0; // 当前作业索引

                        for (int i = 0; i < C; i++) {
                            List<Integer> jobList = new ArrayList<>();
                            for (int j = 0; j < NumCate[i]; j++) {
                                jobList.add(Jobs.get(jobIndex++));
                            }
                            jobsInCate.put(i, jobList);
                        }

                        // All permutations of batches
                        List<List<Integer>> permsBatches = Calculation.permute(batches);
                        for (List<Integer> batchschedule : permsBatches) {
                            // Transform the batch permutation to a job-wise schedule
                            List<Integer> batchsc = Calculation.constructJobOrder(batchschedule, jobsInCate);
                            // Calculate makespan
                            double m_per = Calculation.traverseSequence(batchsc, PT, NumCate, C, delta,M,p_max,p_min);
                            // Better or not ?
                            if (m_per < optBaW - 0.000001) {
                                optBaW = m_per;
                                // break;
                            }
                        }
                        // Record the optimal batch-wise value
                        optBW[itt-1] = optBaW;
                    }

                    // Summarize results of instances in each scale
                    // Calculate the differences
                    double[] differences = new double[Ite];
                    double sum = 0.0;
                    double max = 0.0;
                    double mean = 0.0;

                    // Time
                    sum = 0.0;
                    for (int i = 0; i < Ite; i++) {
                        sum += cputime[i];
                    }
                    mean = sum / Ite;
                    averTime[ins] = mean;

                    /*---- Calculate Gap1  ----*/
                    if(J<=12){
                        for (int i = 0; i < Ite; i++) {
                            differences[i] = (optMBJ[i] - optBW[i]) * 100  / optBW[i];
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
                    Gap1[ins][0] = mean;
                    Gap1[ins][1] = max;

                    /*---- Calculate Gap2  ----*/
                    for (int i = 0; i < Ite; i++) {
                        differences[i] = (optMBJ[i] - opt[i]) * 100  / opt[i];
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

                    /*---- Calculate Gap0  ----*/
                    for (int i = 0; i < Ite; i++) {
                        differences[i] = (optPFSP[i] - optMBJ[i]) * 100  / optMBJ[i];
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

                    // Data storage
                    Object[] data_s = new Object[]{ins, J, C, M, Gap0[ins][0], Gap0[ins][1],Gap1[ins][0], Gap1[ins][1],
                            Gap2[ins][0], Gap2[ins][1], averTime[ins]};
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
