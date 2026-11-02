# This file solve two-machine problems by the COPT solver (version 8.0.7)

from pathlib import Path

import coptpy as cp
import numpy as np
import pandas as pd

from Calculations_copt import BJ_rule, generate_data, results_copt, solve_flowshop_scheduling


def main():
    Ite = 20
    L = 0.7
    t1 = 0.7
    t2 = 0.2
    delta = 1
    time_limit = 60
    M = 2
    J_instances = [5, 10, 15, 20]
    p_min = 0
    p_max = 100

    outPath = Path(__file__).resolve().parents[1] / "Results" / "[1] Result_COPT"
    outPath.mkdir(parents=True, exist_ok=True)
    headers = ["Index", "Jobs", "Categories", "Machines", "Ite",
               "BJ Rule", "Solved-0/1", "COPT-time(s)", "Solved-opt",
               "Unsolved-solution", "Unsolved-COPT-gap(%)", "COPT-bound", "COPT-status"]
    env = cp.Envr()

    for J in J_instances:
        C_instances = [2, 3, 4]
        if (J == 15):
            C_instances = [3, 4, 5]
        elif (J == 20):
            C_instances = [4, 5, 6]
        elif (J >= 40):
            C_instances = [4, 5, 6, 7, 8, 9, 10]
        df = pd.DataFrame(columns=headers)
        outFile = outPath / f"F2-BJ-COPT-Instances{Ite}-J{J}-{time_limit}s.xlsx"
        for C in C_instances:
            for itt in range(1, Ite + 1):
                print(f"##### {J}----{C}----{M}----{itt}")
                param = [t1, L, delta, C, t2]
                Jobs, PT, num_cate, a_jc = generate_data(J, C, M, itt)
                d_jc = np.zeros((J, C), dtype=int)
                d_jc[np.arange(J), a_jc] = 1
                C_BJ = BJ_rule(Jobs, PT, num_cate, C, delta, M, p_max, p_min, param)

                model = solve_flowshop_scheduling(M, J, PT, d_jc, param, p_min, p_max, time_limit, env)
                flag, value, solve_time, gap3 = results_copt(
                    model, a_jc, PT, num_cate, C, delta, M, p_max, p_min, param)
                opt = value if flag and value is not None else "-"
                un_sol = value if not flag and value is not None else "-"
                gap3 = gap3 if not flag and gap3 is not None else "-"
                df.loc[len(df)] = [len(df) + 1, J, C, M, itt, C_BJ, flag,
                                   solve_time, opt, un_sol, gap3, model.bestbnd, model.status]
                # Save completed instances.
                df.to_excel(outFile, index=False)


if __name__ == "__main__":
    main()
