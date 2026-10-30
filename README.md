This library is an open-source project for our submitted paper **_Extended Johnson's Rules for Flow Shop Scheduling with Category-Dependent Learning and Forgetting Effects_**, a joint work with Prof. Yuli ZHANG.

Citation:

```latex
@article{zhang2026extended,
  title={Extended Johnson's Rules for Flow Shop Scheduling with Category-Dependent Learning and Forgetting Effects},
  author={Zhang, Fenglin and Zhang, Yuli},
  journal={Available at SSRN 7209658},
  year={2026}
}
```

We provide totally **3,200 instances** for the Fm/Prmu,CLFEs/C\_{max} problem.&#x20;

The proposed algorithms are coded in **Java (JDK 22.0.1)** and run on Intel(R) Core(TM) i9-13900HX (2.20 GHz) on Windows 11.

We benchmark the proposed algorithms against one of the state-of-the-art commercial solvers **COPT (version 8.0.7) in Python 3.8**. One can apply for a COPT license at <https://copt.shanshu.ai/copt>.&#x20;

In the following, we introduce the **code, data, and results** included in this project.

## 1. Codes in src/main/java

### (1) Main Programs

| Files                           | Descriptions                                                                                                                                                                                                                                                        |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **BJInstance_comparison.java**  | Solve all instances of the F2/Prmu, CLFEs/C\_{max} problem in the folder **Two-Machine-Instances** by the proposed Batch-wise Johnson's (BJ) rule and benchmarks.                                                                                                    |
| **MBJInstance_comparison.java** | Solve all instances of the Fm/Prmu, CLFEs/C\_{max} problem in the folder **M-Machine-Instances** by the proposed M-machine Batch-wise Johnson's (MBJ) algorithm and benchmarks.                                                                                      |
| **BJInstance_SA.java**          | Sensitivity analysis for all instances of the F2/Prmu,CLFEs/C\_{max} problem in the folders **Two-Machine-Instances** and **Two-Machine-LargeInstances** using the BJ rule, under different values of the learning parameters L and a, and the forgetting factor δ. |
| **MBJInstance_SA.java**         | Sensitivity analysis for all instances of the Fm/Prmu,CLFEs/C\_{max} problem in the folders **M-Machine-Instances** and **M-Machine-LargeInstances** using the MBJ algorithm under different values of the learning parameters L and a, and the forgetting factor δ. |
| **BJ_CLFE.java**                | Analyses of the impact of CLFEs on the scheduling decisions of the F2/Prmu, CLFEs/C\_{max} problem using instances in **Two-Machine-Instances**.                                                                                                                       |

### (2) Other Files

| Files                         | Descriptions                                                   |
| ----------------------------- | -------------------------------------------------------------- |
| **Calculation.java**          | Static methods used by the proposed BJ rule and MBJ algorithm. |
| **ComparisonAlgorithms.java** | Methods for benchmarks, including NEH, IG, and CDS algorithms. |

## 2.Codes in src/main/py_copt

| Files                    | Descriptions                                                                                                                      |
| :----------------------- | :-------------------------------------------------------------------------------------------------------------------------------- |
| **BJ_Rule_copt.py**      | Solve all small-scale instances of the F2/Prmu,CLFEs/C\_{max} problem in the folder **Two-Machine-Instances** by the COPT solver. |
| **MBJ_Algo_copt.py**     | Solve all small-scale instances of the Fm/Prmu,CLFEs/C\_{max} problem in the folder **M-Machine-Instances** by the COPT solver.   |
| **Calculations_copt.py** | Solve instances by the COPT solver.                                                                                               |

## 3.Datasets in src/main/Instances

Note that all instance files are named "J-C-M-I", where J is the number of jobs, C is the number of categories, M is the number of machines/workstations/operations, and I is the instance ID.

| Folders                        | Descriptions                                                                                                                                    |
| ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| **Two-Machine-Instances**      | 240 small-scale instances for the F2/Prmu,CLFEs/C\_{max} problem with J \in {5, 10, 15, 20}, C \in {2, 3, 4, 5, 6}, M\=2.                       |
| **Two-Machine-LargeInstances** | 560 large-scale instances for the F2/Prmu,CLFEs/C\_{max} problem with J \in {40, 60, 80, 100}, C \in {4, 5, 6, 7, 8, 9, 10}, M\=2.              |
| **M-Machine-Instances**        | 720 small-scale instances for the Fm/Prmu,CLFEs/C\_{max} problem with J \in {5, 10, 15, 20}, C \in {2, 3, 4, 5, 6}, M \in {3, 4, 5}.            |
| **M-Machine-LargeInstances**   | 1,680 Large-scale instances for the Fm/Prmu,CLFEs/C\_{max} problem with J \in {40, 60, 80, 100}, C \in {4, 5, 6, 7, 8, 9, 10}, M \in {3, 4, 5}. |

Each instance file records the processing times (PTs) of all operations. For example, in the instance "**Two-Machine-Instances/5-2-2-1.txt**":

| Jobs      | PT on Machine 1 | PT on Machine 2 |
| --------- | --------------- | --------------- |
| **Job 1** | 39.0            | 38.0            |
| **Job 2** | 42.0            | 41.0            |
| **Job 3** | 42.0            | 41.0            |
| **Job 4** | 42.0            | 41.0            |
| **Job 5** | 42.0            | 41.0            |

## 4.Results in src/main/Results

| Folders                             | Descriptions                                                                                                                                      |
| :---------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------ |
| **\[1] Result_COPT**                | The computational results of COPT for small-scale instances, output by **BJ_Rule_copt.py** and **MBJ_Algo_copt.py**.                              |
| **\[2] Result_Gaps**                | The results of our algorithms and benchmarks on all instances, output by **BJInstance_comparison.java** and **MBJInstance_comparison.java**.      |
| **\[3] Result_F2_CLFE**             | Results of the impact of CLFEs on scheduling decisions of the F2/Prmu, CLFEs/C\_{max} problem, output by **BJ_CLFE.java**.                        |
| **\[4] Result_SensitivityAnalysis** | Results of sensitivity analysis for our algorithms, output by **BJInstance_SA.java** and **MBJInstance_SA.java**.                                 |
