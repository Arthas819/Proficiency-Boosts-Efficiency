# This file solves scheduling models.

import math
from pathlib import Path

import coptpy as cp
from coptpy import COPT
import numpy as np


def get_instance_sizes(M):
    # Find job and category counts in both instance folders.
    prefix = "Two-Machine" if M == 2 else "M-Machine"
    base = Path(__file__).resolve().parents[1] / "Instances"
    sizes = {}
    for suffix in ["Instances", "LargeInstances"]:
        for file_path in (base / f"{prefix}-{suffix}").glob("*.txt"):
            parts = file_path.stem.split("-")
            if len(parts) != 4 or not all(part.isdigit() for part in parts):
                continue
            J, C, machines, itt = map(int, parts)
            if machines == M:
                sizes.setdefault(J, set()).add(C)
    return {J: sorted(sizes[J]) for J in sorted(sizes)}


def generate_data(J, C, M, itt):
    # Read all machine times and keep the original job order.
    prefix = "Two-Machine" if M == 2 else "M-Machine"
    base = Path(__file__).resolve().parents[1] / "Instances"
    for suffix in ["Instances", "LargeInstances"]:
        file_path = base / f"{prefix}-{suffix}" / f"{J}-{C}-{M}-{itt}.txt"
        if file_path.is_file():
            break
    PT = np.loadtxt(file_path, ndmin=2)
    if PT.shape != (J, M):
        raise ValueError(f"Invalid processing-time matrix: {file_path}")

    rows = [tuple(row) for row in PT]
    categories = list(dict.fromkeys(rows))
    num_cate = [rows.count(row) for row in categories]
    a_jc = [categories.index(row) for row in rows]
    if len(categories) != C or a_jc != sorted(a_jc):
        raise ValueError(f"Expected {C} consecutive category groups: {file_path}")
    return list(range(1, J + 1)), PT, num_cate, a_jc


def solve_flowshop_scheduling(M, J,
                              p, d, param,
                              p_min, p_max,
                              time_limit=900, env=None):
    t1, LB, delta, C, t2 = param
    if delta < 0 or int(delta) != delta:
        raise ValueError("The exact lookup model requires a nonnegative integer delta.")
    delta = int(delta)
    if env is None:
        env = cp.Envr()
    model = env.createModel("Flowshop Scheduling")
    model.setParam(COPT.Param.Logging, 1)
    model.setParam(COPT.Param.TimeLimit, time_limit)
    model.setParam(COPT.Param.RelGap, 1e-4)

    j_list = range(J)
    k_list = range(J)
    m_list = range(1, M + 1)
    f_list = range(C)
    big_M = float(np.sum(p))
    alpha_jm = {(j, m): math.log2(t1 + t2 * (p[j, m - 1] - p_min) / (p_max - p_min))
                for j in j_list for m in m_list}

    # Decision variables.
    c_max = model.addVar(lb=0, name="c_max")
    c_km = model.addVars(k_list, range(M + 1), lb=0, nameprefix="c_km")
    x_jk = model.addVars(j_list, k_list, vtype=COPT.BINARY, nameprefix="x_jk")
    u_kf = model.addVars(k_list, f_list, lb=0, ub=J,
                         vtype=COPT.INTEGER, nameprefix="u_kf")
    ue_kf = model.addVars(k_list, f_list, lb=-delta, ub=J,
                          vtype=COPT.INTEGER, nameprefix="ue_kf")
    v_kf = model.addVars(k_list, f_list, lb=0, ub=J,
                         vtype=COPT.INTEGER, nameprefix="v_kf")
    re_k = model.addVars(k_list, lb=1, ub=J, vtype=COPT.INTEGER, nameprefix="re_k")
    kr = [(k, r) for k in k_list for r in range(1, k + 2)]
    z_kr = model.addVars(kr, vtype=COPT.BINARY, nameprefix="z_kr")
    model.setObjective(c_max, COPT.MINIMIZE)

    # Assign each job to one position.
    for j in j_list:
        model.addConstr(cp.quicksum(x_jk[j, k] for k in k_list) == 1)
    for k in k_list:
        model.addConstr(cp.quicksum(x_jk[j, k] for j in j_list) == 1)

    for k in k_list:
        # Update category proficiency with learning and forgetting.
        for f in f_list:
            selected = cp.quicksum(d[j, f] * x_jk[j, k] for j in j_list)
            if k == 0:
                model.addConstr(u_kf[k, f] == selected)
            else:
                model.addConstr(ue_kf[k, f] == u_kf[k - 1, f] - delta
                                + (delta + 1) * selected)
                model.addGenConstrMax(u_kf[k, f], [ue_kf[k, f]], constant=0)
            model.addConstr(v_kf[k, f] <= u_kf[k, f])
            model.addConstr(v_kf[k, f] <= J * selected)
            model.addConstr(v_kf[k, f] >= u_kf[k, f] - J * (1 - selected))
        model.addConstr(re_k[k] == cp.quicksum(v_kf[k, f] for f in f_list))

        # Exact power lookup at integer learning positions.
        r_list = range(1, k + 2)
        model.addConstr(cp.quicksum(z_kr[k, r] for r in r_list) == 1)
        model.addConstr(re_k[k] == cp.quicksum(r * z_kr[k, r] for r in r_list))

        # Machine precedence also applies to the first job.
        model.addConstr(c_km[k, 0] == 0)
        for m in m_list:
            for j in j_list:
                r_jkm = cp.quicksum(r ** alpha_jm[j, m] * z_kr[k, r] for r in r_list)
                p_actual = p[j, m - 1] * (LB + (1 - LB) * r_jkm)
                relaxed = big_M * (1 - x_jk[j, k])
                model.addConstr(c_km[k, m] >= c_km[k, m - 1] + p_actual - relaxed)
                if k > 0:
                    model.addConstr(c_km[k, m] >= c_km[k - 1, m] + p_actual - relaxed)
    model.addConstr(c_max >= c_km[J - 1, M])

    model.solve()
    if model.status == COPT.OPTIMAL:
        print("Optimal objective value:", model.objval)
    elif model.hasmipsol:
        print("Best feasible objective value:", model.objval)
    else:
        print("No feasible solution found. Status:", model.status)
    return model


def results_copt(model, a_jc, PT, num_cate, C, delta, M, p_max, p_min, param=None):
    # Read an incumbent only when one is available.
    solve_time = model.solvingtime
    if not model.hasmipsol:
        return 0, None, solve_time, None
    flag = int(model.status == COPT.OPTIMAL)
    sequence = Trans(model, model.getVars(), a_jc)
    objective_value = traverse_sequence(sequence, PT, num_cate, C, delta, M,
                                       p_max, p_min, param)
    gap = model.bestgap * 100
    return flag, objective_value, solve_time, gap


def Trans(model, Vars, a_jc):
    # Recover jobs in position order, not variable order.
    selected = []
    for var in Vars:
        if var.name.startswith("x_jk") and var.x > 0.5:
            j, k = map(int, var.name[4:].strip("()[]").split(","))
            selected.append((k, j + 1))
    selected.sort()
    if [k for k, j in selected] != list(range(len(a_jc))):
        raise ValueError("The incumbent does not contain a complete schedule.")
    return [j for k, j in selected]


def get_part_number(job, num_cate):
    total = 0
    for c, count in enumerate(num_cate):
        total += count
        if job <= total:
            return c
    raise ValueError(f"Invalid job number: {job}")


def traverse_sequence(sequence, PT, num_cate, C, delta, M, p_max, p_min, param=None):
    # Evaluate the schedule on all machines.
    t1, L, _, _, t2 = param if param is not None else [0.7, 0.7, delta, C, 0.2]
    Machine = np.zeros(M)
    u = np.zeros(C, dtype=int)
    for job in sequence:
        job_c = get_part_number(job, num_cate)
        for f in range(C):
            u[f] = u[f] + 1 if f == job_c else max(0, u[f] - delta)
        for m in range(M):
            alpha = math.log2(t1 + t2 * (PT[job - 1, m] - p_min) / (p_max - p_min))
            p_actual = PT[job - 1, m] * (L + (1 - L) * u[job_c] ** alpha)
            previous = Machine[m - 1] if m > 0 else 0
            Machine[m] = max(previous, Machine[m]) + p_actual
    return float(Machine[-1])


def BJ_rule(Jobs, PT, num_cate, C, delta, M, p_max, p_min, param):
    t1, L, _, _, t2 = param
    Type1 = []
    Type2 = []
    for i in range(len(Jobs)):
        if PT[i, 0] > PT[i, 1]:
            Type2.append(Jobs[i])
        else:
            Type1.append(Jobs[i])
    T1 = sorted(Type1, key=lambda a: PT[a - 1, 0])

    def sort_key(a):
        partNumA = get_part_number(a, num_cate)
        alpha_a_m1 = math.log2(t1 + t2 * (PT[a - 1, 0] - p_min) / (p_max - p_min))
        alpha_a_m2 = math.log2(t1 + t2 * (PT[a - 1, 1] - p_min) / (p_max - p_min))
        endA = PT[a - 1, 1] * (L + (1 - L) * num_cate[partNumA] ** alpha_a_m2)
        score1 = PT[a - 1, 0]
        for i in range(1, num_cate[partNumA] + 1):
            score1 += PT[a - 1, 1] * (L + (1 - L) * i ** alpha_a_m2)
            score1 -= PT[a - 1, 0] * (L + (1 - L) * i ** alpha_a_m1)
        return max(endA, score1)

    T2 = sorted(Type2, key=sort_key, reverse=True)
    return traverse_sequence(T1 + T2, PT, num_cate, C, delta, M, p_max, p_min, param)


def MBJ_algo(Jobs, PT, num_cate, C, delta, M, p_max, p_min, param):
    # Keep the original MBJ sorting rule.
    t1, L, _, _, t2 = param
    optMBJCDS = float("inf")
    for m in range(M - 1):
        Type1 = []
        Type2 = []
        E1 = PT[:, :m + 1].sum(axis=1)
        E2 = PT[:, m + 1:].sum(axis=1)
        for i in range(len(Jobs)):
            if E1[i] > E2[i]:
                Type2.append(Jobs[i])
            else:
                Type1.append(Jobs[i])
        T1 = sorted(Type1, key=lambda a: E1[a - 1])

        def sort_key(a):
            partNumA = get_part_number(a, num_cate)
            PE1_sum = 0
            PE2_sum = 0
            PE2_las = 0
            for j in range(M - 1):
                alpha_a = math.log2(t1 + t2 * (PT[a - 1, j] - p_min) / (p_max - p_min))
                if j <= m:
                    for i in range(1, num_cate[partNumA] + 1):
                        PE1_sum += PT[a - 1, j] * (L + (1 - L) * i ** alpha_a)
                else:
                    PE2_las += PT[a - 1, j] * (L + (1 - L) * num_cate[partNumA] ** alpha_a)
                    for i in range(1, num_cate[partNumA] + 1):
                        PE2_sum += PT[a - 1, j] * (L + (1 - L) * i ** alpha_a)
            return max(PE2_las, PT[a - 1, m] + PE2_sum - PE1_sum)

        T2 = sorted(Type2, key=sort_key, reverse=True)
        optMS = traverse_sequence(T1 + T2, PT, num_cate, C, delta, M, p_max, p_min, param)
        optMBJCDS = min(optMBJCDS, optMS)
    return optMBJCDS
