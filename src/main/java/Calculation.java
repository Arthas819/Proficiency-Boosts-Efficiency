import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
    Static methods used by BJ rule and MBJ algorithm
 */

public class Calculation {

    // Function to get the category info of a job
    public static int getPartNumber(int job, int[] NumCate) {
        int sum = 0;
        for (int i = 0; i < NumCate.length; i++) {
            sum += NumCate[i];
            if (job <= sum) {
                return i;
            }
        }
        return -1;
    }

    // Function to generate all permutations of a job list
    public static List<List<Integer>> permute(List<Integer> nums) {
        List<List<Integer>> result = new ArrayList<>();
        backtrack(result, new ArrayList<>(), nums);
        return result;
    }

    public static void printArray(double[][] array) {
        for (int i = 1; i <= array.length; i++) {
            System.out.print("Job"+ " "+i+ ":"+ " ");
            for (int j = 0; j < array[i-1].length; j++) {
                System.out.print(array[i-1][j] + " ");
            }
            System.out.println();
        }
    }

    // Function to judge whether a schedule is a batch-wise schedule
    public static boolean SameCate(List<Integer> sequence,int[] NumCate){

        int[] jobCate = new int[sequence.size()];

        for (int job : sequence) {
            // get the cate of current job
            int job_c = getPartNumber(job, NumCate);
            // Remember
            jobCate[job-1] = job_c;
        }
        // Judge whether categories in jobCate are processed consecutively
        if (jobCate.length == 0) {
            return true;
        }
        int size = jobCate.length;
        // Iterate through the array and check for non-consecutive elements
        for (int i = 1; i < size; i++) {
            if (jobCate[i] == jobCate[i - 1]) {
                continue;
            }
            // Check if the element appeared before in the array
            for (int j = 0; j < i - 1; j++) {
                if (jobCate[j] == jobCate[i]) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void backtrack(List<List<Integer>> result, List<Integer> tempList, List<Integer> nums) {
        if (tempList.size() == nums.size()) {
            result.add(new ArrayList<>(tempList));
        } else {
            for (int i = 0; i < nums.size(); i++) {
                if (tempList.contains(nums.get(i))) continue;
                tempList.add(nums.get(i));
                backtrack(result, tempList, nums);
                tempList.remove(tempList.size() - 1);
            }
        }
    }

    // Calculate the makespan of a schedule with CLFE
    public static double traverseSequence(List<Integer> sequence, double[][] PT,int[] NumCate,int C,int delta,int M,int p_max,int p_min) {
        double[] Machine = new double[M];
        double[] p_a = new double[M];
        double L = 0.7;
        double t1 = 0.7;
        double t2 = 0.2;
        // Effective learning position
        int[] u = new int[C];
        // traverse all jobs in the schedule
        for (int job : sequence) {
            // Update the processing times
            int job_c = getPartNumber(job, NumCate);
            for (int u_e = 0 ; u_e < u.length; u_e++){
                if(u_e == job_c){
                    u[u_e]++;
                }
                else{
                    u[u_e] = Math.max(0,u[u_e]-delta);
                }
            }
            // Learning rate
            double alpha_a_m1 = Math.log(t1+t2*(PT[job - 1][0]-p_min)/(p_max-p_min)) / Math.log(2);
            double alpha_a_m2 = Math.log(t1+t2*(PT[job - 1][1]-p_min)/(p_max-p_min)) / Math.log(2);
            // Actual processing time
            for(int i = 0; i < M ; i++){
                if(i==0){
                    p_a[i] = PT[job-1][i]*(L+(1-L)*Math.pow(u[job_c],alpha_a_m1));
                }
                else {
                    p_a[i] = PT[job-1][i]*(L+(1-L)*Math.pow(u[job_c],alpha_a_m2));
                }

            }
            // Update complete time on each machine
            for(int i = 0; i < M ; i++){
                if(i==0){
                    Machine[0] += p_a[i];
                } else{
                    Machine[i] = Math.max(Machine[i-1],Machine[i])+p_a[i];
                }
            }
        }
        return Machine[M-1];
    }

    // Calculate the makespan of a schedule without CLFE
    public static double traverseSequence_PFSP(List<Integer> sequence, double[][] PT,int M) {
        double[] Machine = new double[M];
        // traverse all jobs in the schedule
        for (int job : sequence) {
            // Update complete time on each machine
            for(int i = 0; i < M ; i++){
                if(i==0){
                    Machine[0] += PT[job-1][i];
                } else{
                    Machine[i] = Math.max(Machine[i-1],Machine[i])+PT[job-1][i];
                }
            }
        }
        return Machine[M-1];
    }

    // Calculate the makespan of a schedule withouut CLFE
    public static double calSequence(List<Integer> sequence, double[][] PT, int M) {
        double[] Machine = new double[M];
        // traverse all jobs in the schedule
        for (int job : sequence) {
            // Update complete time on each machine
            for(int i = 0; i < M ; i++){
                if(i==0){
                    Machine[0] += PT[job-1][i];
                } else{
                    Machine[i] = Math.max(Machine[i-1],Machine[i])+PT[job-1][i];
                }
            }
        }
        return Machine[M-1];
    }

    public static List<Integer> constructJobOrder(List<Integer> batchOrder, Map<Integer, List<Integer>> jobsInCate) {
        List<Integer> batchsc = new ArrayList<>();

        // Construct a job-wise schedule by a batch-wise schedule
        for (int batch : batchOrder) {
            int categoryIndex = batch - 1;
            List<Integer> jobList = jobsInCate.get(categoryIndex);
            batchsc.addAll(jobList);
        }

        return batchsc;
    }
}
