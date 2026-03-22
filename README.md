This library is an open source project for our submitted paper ***Proficiency Boosts Efficiency: Extended Johnson's Rules for Flow Shop Scheduling with Learning and Forgetting Effects*** , joint work with Prof. Yuli ZHANG and Prof. Zuo-Jun Max SHEN. 

The propsoed algorithms are coded in Java (JDK 22.0.1) and run on Intel(R) Core(TM) i9-13900HX (2.20 GHz) on Windows 11. 

In the following, we introduce all programs and folders. 



## 1.Codes in src/main/java

### (1) Main Programs 

| Files                      | Descriptions                                                 |
| -------------------------- | ------------------------------------------------------------ |
| **BJInstance.java**        | Solve all instances of the F2/Prmu,CLFEs/C_{max} problem in the folder **Two-Machine-Instances** by the proposed Bathc-wise Johnson's (BJ) rule. |
| **MBJInstance.java**       | Solve all instances of the Fm/Prmu,CLFEs/C_{max} problem in the folder **M-Machine-Instances** by the proposed M-machine Bathc-wise Johnson's (MBJ) algorithm. |
| **BJInstance_Delta.java**  | Solve all instances of the F2/Prmu,CLFEs/C_{max} problem in the folders **Two-Machine-Instances** and **Two-Machine-LargeInstances** by the BJ rule under different value of the forgetting factor δ. |
| **MBJInstance_Delta.java** | Solve all instances of the Fm/Prmu,CLFEs/C_{max} problem in the folders **M-Machine-Instances** and **M-Machine-LargeInstances** by the MBJ algorithm under different value of the forgetting factor δ. |

### (2) Other Files

| Files                | Descriptions                                      |
| -------------------- | ------------------------------------------------- |
| **Calculation.java** | Static methods used by BJ rule and MBJ algorithm. |



## 2.Datasets in src/main

Note that all instance files are named by "J-C-M-I", where J is the number of jobs, C is the number of categories, M is the number of machines / workstations / operations, and I is the identification number of this instance. 

| Folders                        | Descriptions                                                 |
| ------------------------------ | ------------------------------------------------------------ |
| **Two-Machine-Instances**      | Small-scale instances for the F2/Prmu,CLFEs/C_{max} problem with J \in {5, 10, 15, 20}, C \in {2, 3, 4, 5, 6}, M=2. |
| **Two-Machine-LargeInstances** | Large-scale instances for the F2/Prmu,CLFEs/C_{max} problem with J \in {40, 60, 80, 100}, C \in {4, 5, 6, 7, 8, 9, 10}, M=2. |
| **M-Machine-Instances**        | Small-scale instances for the Fm/Prmu,CLFEs/C_{max} problem with J \in {5, 10, 15, 20}, C \in {2, 3, 4, 5, 6}, M \in {3, 4, 5}. |
| **M-Machine-LargeInstances**   | Large-scale instances for the Fm/Prmu,CLFEs/C_{max} problem with J \in {40, 60, 80, 100}, C \in {4, 5, 6, 7, 8, 9, 10}, M \in {3, 4, 5}. |

Each instance file records the processing times (PTs) of all operations. For example, in instance "**Two-Machine-Instances/5-2-2-1.txt**":

| Jobs      | PT on Machine 1 | PT on Machine 2 |
| --------- | --------------- | --------------- |
| **Job 1** | 39.0            | 38.0            |
| **Job 2** | 42.0            | 41.0            |
| **Job 3** | 42.0            | 41.0            |
| **Job 4** | 42.0            | 41.0            |
| **Job 5** | 42.0            | 41.0            |



## 3.Results

Results for the general F2/Prmu,CLFEs/C_{max} problem:

- F2-BJ-Instances.xlsx
- F2-BJ-Small-scale-Change-delta.xlsx
- F2-BJ-Large-scale-Change-delta.xlsx

Results for the general Fm/Prmu,CLFEs/C_{max} problem:

- FM-MBJ-Instances.xlsx
- FM-MBJ-Small-scale-Change-delta.xlsx
- FM-MBJ-Large-scale-Change-delta.xlsx