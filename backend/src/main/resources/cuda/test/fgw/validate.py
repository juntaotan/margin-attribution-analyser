#!/usr/bin/env python3
"""Independent tensor/HiGHS reference, POT validation and step-by-step FP64 report.

No production FGW routines are imported. Missing CUDA evidence is UNVERIFIED,
never PASS. A supplied native report is validated at every recorded state.
"""
import argparse
import json
import platform
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import ot
import scipy
from scipy.optimize import linprog
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import shortest_path

TOL = 1e-8
ALPHA = 0.8
POT_CONFIG = dict(loss_fun="square_loss", symmetric=True, alpha=ALPHA,
                  armijo=False, max_iter=200, tol_abs=1e-6, tol_rel=1e-5)


def fixture(values, edges):
    embeddings = np.zeros((len(values), 384), dtype=np.float64)
    embeddings[:, 0] = values
    topology = np.zeros((len(values), len(values)), dtype=np.float64)
    for i, j in edges:
        topology[i, j] = topology[j, i] = 1
    distances = shortest_path(csr_matrix(topology), directed=False, unweighted=True)
    return embeddings, distances


def objective(material, d1, d2, correspondence):
    """Original four-index objective; intentionally independent of the fixed C form."""
    loss = (d1[:, None, :, None] - d2[None, :, None, :]) ** 2
    material_cost = float(np.sum(material * correspondence))
    structure_cost = float(np.einsum("ijkl,ij,kl->", loss, correspondence, correspondence))
    return dict(material=material_cost, structure=structure_cost,
                fgw=(1 - ALPHA) * material_cost + ALPHA * structure_cost)


def gradient(material, d1, d2, correspondence):
    loss = (d1[:, None, :, None] - d2[None, :, None, :]) ** 2
    return (1 - ALPHA) * material + 2 * ALPHA * np.einsum("ijkl,kl->ij", loss, correspondence)


def linear_ot(cost, p, q):
    n, m = cost.shape
    rows = np.kron(np.eye(n), np.ones((1, m)))
    columns = np.tile(np.eye(m), (1, n))
    result = linprog(cost.ravel(), A_eq=np.vstack((rows, columns)), b_eq=np.r_[p, q],
                     bounds=(0, None), method="highs",
                     options=dict(primal_feasibility_tolerance=1e-9,
                                  dual_feasibility_tolerance=1e-9))
    if not result.success:
        raise RuntimeError(result.message)
    return result.x.reshape(n, m)


def reference(material, d1, d2, initial=None):
    n, m = material.shape
    p, q = np.full(n, 1 / n), np.full(m, 1 / m)
    P = np.outer(p, q) if initial is None else initial.copy()
    trace = []
    for updates in range(201):
        costs = objective(material, d1, d2, P)
        G = gradient(material, d1, d2, P)
        S = linear_ot(G, p, q)
        delta = S - P
        gap = float(np.sum(G * (P - S)))
        state = dict(updates=updates, costs=costs, P=P.copy(), G=G, S=S, delta=delta,
                     gap=gap, gamma=0., a=0., b=0.)
        trace.append(state)
        if gap <= 1e-6 + 1e-5 * max(abs(costs["fgw"]), 1e-12):
            status = "FW_GAP_REACHED"
            break
        if updates == 200:
            status = "MAX_ITERATIONS"
            break
        f0 = costs["fgw"]
        f1 = objective(material, d1, d2, P + delta)["fgw"]
        fm = objective(material, d1, d2, P + .5 * delta)["fgw"]
        # Independent interpolation of the original quadratic, without using the
        # production coefficient formula or fixed-marginal cost computation.
        a = 2 * (f1 + f0 - 2 * fm)
        b = f1 - f0 - a
        candidates = [0., 1.]
        if a > 0:
            candidates.append(float(np.clip(-b / (2 * a), 0, 1)))
        gamma = min(candidates, key=lambda x: objective(material, d1, d2, P + x * delta)["fgw"])
        state.update(gamma=gamma, a=a, b=b)
        P += gamma * delta
    return dict(p=p, q=q, trace=trace, costs=costs, status=status, P=P, gap=gap)


def serialisable(value):
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, np.generic):
        return value.item()
    if isinstance(value, dict):
        return {k: serialisable(v) for k, v in value.items()}
    if isinstance(value, list):
        return [serialisable(v) for v in value]
    return value


class Report:
    def __init__(self):
        self.checks = []
        self.prefix = ""

    def check(self, name, actual, expected, tolerance=TOL):
        actual, expected = np.asarray(actual), np.asarray(expected)
        same_shape = actual.shape == expected.shape
        finite = np.all(np.isfinite(actual)) if actual.dtype.kind in "fiu" else True
        error = float(np.max(np.abs(actual - expected), initial=0)) if same_shape and finite else float("inf")
        self.checks.append(dict(name=self.prefix + name, actual=actual.tolist(), expected=expected.tolist(),
                                shape=list(actual.shape), maximumAbsoluteError=error if np.isfinite(error) else None,
                                result="PASS" if error <= tolerance else "FAIL"))

    def condition(self, name, value):
        self.check(name, int(bool(value)), 1, 0)

    def feasible(self, name, value):
        value = np.asarray(value)
        self.condition(name + " finite/nonnegative", np.all(np.isfinite(value)) and np.min(value) >= -TOL)
        self.check(name + " row errors", value.sum(axis=1), np.full(value.shape[0], 1 / value.shape[0]))
        self.check(name + " column errors", value.sum(axis=0), np.full(value.shape[1], 1 / value.shape[1]))
        self.check(name + " total", value.sum(), 1.)


def validate_native(report, native, material, d1, d2, ref):
    n, m = material.shape
    report.condition("native dimensions", native["rows"] == n and native["columns"] == m)
    for key, expected, shape in [("material", material, (n, m)), ("d1", d1, (n, n)), ("d2", d2, (m, m))]:
        value = np.array(native[key])
        report.condition("native " + key + " dimensions", value.size == np.prod(shape))
        if value.size == np.prod(shape):
            report.check("native " + key, value.reshape(shape), expected)
    report.check("native fixed p", native["p"], ref["p"])
    report.check("native fixed q", native["q"], ref["q"])
    start = native["starts"][0]
    report.condition("native fresh terminal gap state", len(start["trace"]) == len(ref["trace"]))
    for index, state in enumerate(start["trace"]):
        label = "native iteration " + str(index)
        for key in ["P", "G", "S", "delta"]:
            value = np.asarray(state[key])
            report.condition(label + " " + key + " dimensions", value.size == n * m)
            if value.size != n * m:
                continue
            value = value.reshape(n, m)
            if index < len(ref["trace"]):
                report.check(label + " " + key, value, ref["trace"][index][key])
            if key in ["P", "S"]:
                report.feasible(label + " " + key, value)
        P = np.asarray(state["P"]).reshape(n, m)
        G = np.asarray(state["G"]).reshape(n, m)
        S = np.asarray(state["S"]).reshape(n, m)
        report.check(label + " independently differentiated gradient", G, gradient(material, d1, d2, P))
        for key, value in objective(material, d1, d2, P).items():
            report.check(label + " original " + key, state["costs"][key], value)
        report.check(label + " fresh gap", state["gap"], np.sum(G * (P - S)))
        lp = linear_ot(G, ref["p"], ref["q"])
        report.check(label + " independent HiGHS OT objective", np.sum(G * S), np.sum(G * lp))
        report.check(label + " OT dual certificate", state["linearCost"], state["dualCost"])
        report.check(label + " reduced cost violation", state["dualViolation"], 0)
        if index < len(ref["trace"]) - 1:
            for key in ["gamma", "a", "b"]:
                report.check(label + " " + key, state[key], ref["trace"][index][key])
    report.check("native final P", np.asarray(start["P"]).reshape(n, m), ref["P"])
    report.check("native final FGW", start["costs"]["fgw"], ref["costs"]["fgw"])
    report.check("native final gap", start["gap"], ref["gap"])
    report.condition("native convergence", start["status"] == "FW_GAP_REACHED" and start["converged"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-report", type=Path)
    parser.add_argument("--cuda-report", type=Path)
    parser.add_argument("--native-executable", type=Path, help="Check native exact OT against independent HiGHS/POT")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = Report()
    e1, d1 = fixture([0., 2.], [(0, 1)])
    e2, d2 = fixture([0., 1., 2.], [(0, 1), (1, 2)])
    material = ((e1[:, None, :] - e2[None, :, :]) ** 2).sum(axis=2)
    report.check("M_attr", material, [[0., 1., 4.], [4., 1., 0.]])
    report.check("D1", d1, [[0., 1.], [1., 0.]])
    report.check("D2", d2, [[0., 1., 2.], [1., 0., 1.], [2., 1., 0.]])
    ref = reference(material, d1, d2)
    P0 = np.full((2, 3), 1 / 6)
    S = np.array([[1 / 3, 1 / 6, 0], [0, 1 / 6, 1 / 3]])
    expected = [dict(P=P0, G=[[1.8666666667, 1., 2.6666666667], [2.6666666667, 1., 1.8666666667]],
                     S=S, delta=[[1 / 6, 0., -1 / 6], [-1 / 6, 0., 1 / 6]], gap=.2666666667,
                     costs=dict(material=1.6666666667, structure=.9444444444, fgw=1.0888888889), gamma=1.),
                dict(P=S, G=[[.8, 1., 3.7333333333], [3.7333333333, 1., .8]], S=S,
                     gap=0., costs=dict(material=.3333333333, structure=.5, fgw=.4666666667))]
    report.check("p", ref["p"], [.5, .5])
    report.check("q", ref["q"], [1 / 3] * 3)
    report.condition("CPU fresh final gap trace", len(ref["trace"]) == 2)
    for i, values in enumerate(expected):
        state = ref["trace"][i]
        for key, expected_value in values.items():
            if key == "costs":
                for term, value in expected_value.items():
                    report.check(f"CPU iteration {i} {term}", state[key][term], value)
            else:
                report.check(f"CPU iteration {i} {key}", state[key], expected_value)
        for key in ["P", "S"]:
            report.feasible(f"CPU iteration {i} {key}", state[key])
        report.check(f"CPU iteration {i} direction row sums", state["delta"].sum(axis=1), [0., 0.])
        report.check(f"CPU iteration {i} direction column sums", state["delta"].sum(axis=0), [0., 0., 0.])
        # Feasible derivatives of the original tensor objective, centred in the
        # interior and second-order one-sided at the sparse boundary.
        directions = [np.array([[1., -1., 0.], [-1., 1., 0.]]),
                      np.array([[0., 1., -1.], [0., -1., 1.]])] if i == 0 else [P0 - S]
        for k, direction in enumerate(directions):
            eps = 1e-5
            f = lambda t: objective(material, d1, d2, state["P"] + t * direction)["fgw"]
            derivative = ((f(eps) - f(-eps)) / (2 * eps) if i == 0 else
                          (-3 * f(0) + 4 * f(eps) - f(2 * eps)) / (2 * eps))
            report.check(f"CPU iteration {i} feasible directional derivative {k}", derivative,
                         np.sum(state["G"] * direction))
    report.condition("CPU objective monotonicity", all(a["costs"]["fgw"] + TOL >= b["costs"]["fgw"]
                     for a, b in zip(ref["trace"], ref["trace"][1:])))
    report.condition("CPU convergence", ref["status"] == "FW_GAP_REACHED")
    C, h1, h2 = ot.gromov.init_matrix(d1, d2, ref["p"], ref["q"], loss_fun="square_loss")
    for i, state in enumerate(ref["trace"]):
        report.check(f"POT iteration {i} structure", ot.gromov.gwloss(C, h1, h2, state["P"]), state["costs"]["structure"])
        report.check(f"POT iteration {i} gradient", (1 - ALPHA) * material + ALPHA * ot.gromov.gwggrad(C, h1, h2, state["P"]), state["G"])
        pot_s = ot.emd(ref["p"], ref["q"], state["G"], numItermax=100000, numThreads=1, check_marginals=True)
        report.check(f"POT iteration {i} exact OT", pot_s, state["S"])
    pot_P, pot_log = ot.gromov.fused_gromov_wasserstein(material, d1, d2, ref["p"], ref["q"],
                                                      G0=P0.copy(), log=True, **POT_CONFIG)
    report.check("POT final P", pot_P, S)
    report.check("POT final FGW", pot_log["fgw_dist"], .4666666667)
    report.feasible("POT final P", pot_P)
    # Node indexing alone changes; explicitly record both uniform and feasible
    # permutation starts, and preserve the nonconvex multi-start results.
    ea, da = fixture([0., 1., 2.], [(0, 1), (0, 2)])
    eb, db = fixture([0., 2., 1.], [(0, 2), (0, 1)])
    ordering_material = ((ea[:, None, :] - eb[None, :, :]) ** 2).sum(axis=2)
    permutation = np.array([[1 / 3, 0., 0.], [0., 0., 1 / 3], [0., 1 / 3, 0.]])
    ordering_starts = [np.full((3, 3), 1 / 9), permutation]
    ordering = [reference(ordering_material, da, db, initial) for initial in ordering_starts]
    report.check("CPU ordering best cost", min(x["costs"]["fgw"] for x in ordering), 0.)
    ordering_pot = []
    for i, initial in enumerate(ordering_starts):
        pp, log = ot.gromov.fused_gromov_wasserstein(ordering_material, da, db, np.full(3, 1 / 3), np.full(3, 1 / 3),
                                                   G0=initial.copy(), log=True, **POT_CONFIG)
        ordering_pot.append(float(log["fgw_dist"]))
        report.feasible(f"POT ordering start {i}", pp)
    report.check("POT ordering best cost", min(ordering_pot), 0.)
    coverage = {}
    for label, path in [("host", args.native_report), ("cuda", args.cuda_report)]:
        if path is None:
            coverage[label] = "UNVERIFIED: no native execution report supplied"
            continue
        native = json.loads(path.read_text())
        coverage[label] = native["execution"]
        if label == "cuda":
            report.condition("CUDA evidence provenance", native["execution"] == "CUDA")
        report.prefix = label.upper() + " "
        validate_native(report, native["fixed"], material, d1, d2, ref)
        if label == "cuda":
            report.prefix = "CUDA POOLED "
            validate_native(report, native["pooledFixed"], material, d1, d2, ref)
            report.condition("persistent session checks", native["sessionChecks"] == "PASS")
            stats = native["sessionStatistics"]
            for key, value in dict(accepted=29, completed=28, failed=1, workersCreated=2, baselinePreparations=1, maximumQueued=1).items():
                report.check("session " + key, stats[key], value)
            report.prefix = "CUDA "
        multi = native["unequalMultiStart"]
        report.condition("unequal-size multi-start count", len(multi["starts"]) == 2)
        custom = multi["starts"][1]
        report.check("feasible unequal start final P", np.array(custom["P"]).reshape(2, 3), S)
        report.check("feasible unequal start costs", list(custom["costs"].values()), [.3333333333, .5, .4666666667])
        report.condition("feasible unequal start fresh convergence", custom["converged"] and custom["iterations"] == 0)
        report.check("feasible unequal start gradient", np.array(custom["trace"][0]["G"]).reshape(2, 3), expected[1]["G"])
        for key in ["ordering", "orderingReverse"]:
            item = native[key]
            report.check(label + " " + key + " best FGW", item["starts"][item["bestStart"]]["costs"]["fgw"], 0.)
            for i, start in enumerate(item["starts"]):
                report.feasible(label + " " + key + " start " + str(i), np.array(start["P"]).reshape(3, 3))
    report.prefix = ""
    ot_fixtures = []
    if args.native_executable:
        # Unequal sizes, one-node graphs, negative gradients and tied LP optima.
        # Tied optima are checked by linear objective and marginals, not identity.
        costs = [np.array([[0., 0., 0.], [0., 0., 0.]]),
                 np.array([[-3., 2.], [-1., -2.], [4., -5.]]),
                 np.array([[2., -1., 2., 0.]]), np.array([[2.], [-1.], [2.], [0.]]),
                 np.array([[1., 1., 3.], [1., 1., 2.], [-1., 2., 2.]]),
                 np.array([[3., -2., 5., 1.], [0., 7., -3., 4.], [-4., 2., 1., 0.]])]
        # Price/pivot coverage for the optimised exact solver, including the
        # benchmark shape. Degenerate integer costs exercise zero-mass pivots.
        rng = np.random.default_rng(20261011)
        for n, m in [(4, 7), (7, 4), (8, 8), (16, 20), (128, 160)]:
            costs.append(rng.normal(0, 2, (n, m)))
            costs.append(rng.integers(-3, 4, (n, m)).astype(np.float64))
        with tempfile.TemporaryDirectory(prefix="fgw-ot-") as directory:
            for i, cost in enumerate(costs):
                n, m = cost.shape
                path = Path(directory) / "cost.txt"
                path.write_text(f"{n} {m}\n" + " ".join(map(str, cost.ravel())) + "\n")
                actual = json.loads(subprocess.run([str(args.native_executable.resolve()), "--ot", str(path)],
                                                   capture_output=True, text=True, check=True).stdout)
                P = np.asarray(actual["P"]).reshape(n, m)
                p, q = np.full(n, 1 / n), np.full(m, 1 / m)
                independent = linear_ot(cost, p, q)
                pot = ot.emd(p, q, cost, numItermax=100000, check_marginals=True)
                report.feasible(f"Exact OT fixture {i}", P)
                report.check(f"Exact OT fixture {i} HiGHS objective", np.sum(cost * P), np.sum(cost * independent))
                report.check(f"Exact OT fixture {i} POT objective", np.sum(cost * P), np.sum(cost * pot))
                report.check(f"Exact OT fixture {i} dual objective", actual["linearCost"], actual["dualCost"])
                report.check(f"Exact OT fixture {i} reduced costs", actual["dualViolation"], 0.)
                warm = np.asarray(actual["warmP"]).reshape(n, m)
                warm_pot = ot.emd(p, q, -cost, numItermax=100000, check_marginals=True)
                report.feasible(f"Warm exact OT fixture {i}", warm)
                report.check(f"Warm exact OT fixture {i} POT objective", np.sum(-cost * warm), np.sum(-cost * warm_pot))
                report.check(f"Warm exact OT fixture {i} dual objective", actual["warmLinearCost"], actual["warmDualCost"])
                report.check(f"Warm exact OT fixture {i} reduced costs", actual["warmDualViolation"], 0.)
                ot_fixtures.append(dict(cost=cost, actual=P, highs=independent, POT=pot, warm=warm, warmPOT=warm_pot, certificate=actual))
    data = dict(versions=dict(python=platform.python_version(), numpy=np.__version__, scipy=scipy.__version__, POT=ot.__version__),
                configuration=POT_CONFIG, dtype="float64", G0=P0, coverage=coverage, otFixtures=ot_fixtures,
                cudaDeviceInfo=json.loads(args.cuda_report.read_text()).get("deviceInfo") if args.cuda_report else None,
                checks=report.checks, reference=ref, orderingInitials=ordering_starts,
                orderingReference=ordering, orderingPotCosts=ordering_pot,
                potLosses=pot_log["loss"], cpuResult="FAIL" if any(c["result"] == "FAIL" for c in report.checks) else "PASS")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix(".json").write_text(json.dumps(serialisable(data), indent=2, allow_nan=False) + "\n")
    lines = ["# FGW numerical validation", "", "CPU/reference result: " + data["cpuResult"],
             "CUDA coverage: " + coverage.get("cuda", "UNVERIFIED"), "", "Versions: `" + json.dumps(data["versions"]) + "`.",
             "", "POT configuration: `" + json.dumps(POT_CONFIG) + "`, explicit FP64 inputs, `G0=outer(p,q)`.",
             "FP64 tolerance: `1e-8`. No loss scaling or distance normalisation.", "",
             "| Check | Maximum absolute error | Result |", "| --- | ---: | --- |"]
    lines += [f"| {c['name']} | {format(c['maximumAbsoluteError'], '.12g') if c['maximumAbsoluteError'] is not None else 'nonfinite/shape mismatch'} | {c['result']} |" for c in report.checks]
    lines += ["", "## Actual and expected values", ""]
    for c in report.checks:
        if np.array(c["actual"]).ndim == 2 or "iteration" in c["name"] or c["name"] in ["p", "q"]:
            lines += ["### " + c["name"], "", "```json", json.dumps(dict(actual=c["actual"], expected=c["expected"]), indent=2), "```", ""]
    lines += ["## Iteration and convergence diagnostics", "", "```json",
              json.dumps(serialisable(dict(costs=[x["costs"] for x in ref["trace"]], gaps=[x["gap"] for x in ref["trace"]],
                                           status=ref["status"], orderingStarts=ordering_starts,
                                           orderingCosts=[x["costs"]["fgw"] for x in ordering], orderingPotCosts=ordering_pot)), indent=2), "```", "",
              "First-order stationarity is not a certificate of global optimality.",
              "Native CUDA checks require a compatible NVIDIA driver/device. Absent CUDA evidence is not a passing device test."]
    args.output.write_text("\n".join(lines) + "\n")
    print(data["cpuResult"], len(report.checks), "checks; CUDA:", coverage["cuda"])
    if data["cpuResult"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
