#!/usr/bin/env python3
"""Validate captured real-model workflow inputs using independent tensor/HiGHS/POT calculations.

No production numerical routines are called. Tied OT solutions are compared by
objective and marginals. Semantic labels are measured, never assigned an invented
acceptance threshold. Each native trace state gets a fresh independent gap check.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import ot
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import shortest_path

from validate import Report, objective, gradient, linear_ot, reference, serialisable, POT_CONFIG, TOL


def distances(graph):
    n = len(graph['rowOffsets']) - 1
    adjacency = np.zeros((n, n))
    for i in range(n):
        for edge in range(graph['rowOffsets'][i], graph['rowOffsets'][i + 1]):
            j = graph['colIndices'][edge]
            adjacency[i, j] = adjacency[j, i] = 1
    return shortest_path(csr_matrix(adjacency), directed=False, unweighted=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    captured = json.loads(args.input.read_text())
    left, right, result = (captured[k] for k in ('left', 'right', 'result'))
    d1, d2 = distances(left), distances(right)
    n, m = d1.shape[0], d2.shape[0]
    x, y = np.array(left['embeddings']).reshape(n, 384), np.array(right['embeddings']).reshape(m, 384)
    material = np.sum((x[:, None] - y[None]) ** 2, axis=2)
    p, q = np.full(n, 1/n), np.full(m, 1/m)
    report = Report()
    settings = captured['config']
    for key, expected in dict(alpha=.8, tolAbs=1e-6, tolRel=1e-5, maxIterations=200,
                              tolGapAbs=1e-6, tolGapRel=1e-5, costScaleFloor=1e-12,
                              feasibilityTolerance=1e-9, numericalTolerance=1e-10, otTolerance=1e-12).items():
        report.check('captured numerical setting '+key, settings[key], expected, 0)
    report.condition('captured trace setting', settings['captureTrace'])
    report.condition('captured uniform start', captured['starts'] == ['UNIFORM_OUTER_PRODUCT'])
    for key, expected in [('materialDistances', material), ('leftDistances', d1), ('rightDistances', d2)]:
        report.check(key, np.array(result[key]).reshape(expected.shape), expected)
    report.check('weights p', result['p'], p)
    report.check('weights q', result['q'], q)
    final = result['starts'][result['bestStart']]
    native_states = final['trace']
    report.check('initial uniform correspondence', np.array(native_states[0]['correspondence']).reshape(n, m), np.outer(p, q))
    for index, state in enumerate(native_states):
        report.prefix = f'iteration {index}: '
        P = np.array(state['correspondence']).reshape(n, m)
        G = np.array(state['gradient']).reshape(n, m)
        S = np.array(state['ot']['correspondence']).reshape(n, m)
        delta = np.array(state['direction']).reshape(n, m)
        report.feasible('P', P); report.feasible('S', S)
        report.check('gradient', G, gradient(material, d1, d2, P))
        report.check('direction', delta, S-P)
        for term, value in objective(material, d1, d2, P).items():
            report.check('original ' + term, state['costs'][term], value)
        independent_s = linear_ot(G, p, q)
        report.check('OT objective vs HiGHS', np.sum(G*S), np.sum(G*independent_s))
        pot_s = ot.emd(p, q, np.asarray(G, order='C'), numItermax=100000)
        report.check('OT objective vs POT', np.sum(G*S), np.sum(G*pot_s))
        gap = float(np.sum(G*(P-S)))
        report.check('fresh gap', state['gap'], gap)
        report.check('OT primal/dual', state['ot']['linearCost'], state['ot']['dualCost'])
        report.check('OT reduced-cost violation', state['ot']['maximumReducedCostViolation'], 0)
        # Feasible direction: zero row and column sums. The original quadratic
        # directional derivative is valid even at a boundary, using central differences.
        h = np.outer(np.arange(n)-np.mean(np.arange(n)), np.arange(m)-np.mean(np.arange(m)))
        h /= np.linalg.norm(h)
        epsilon = 1e-5
        derivative = (objective(material, d1, d2, P+epsilon*h)['fgw']-objective(material, d1, d2, P-epsilon*h)['fgw'])/(2*epsilon)
        report.check('feasible directional derivative', np.sum(G*h), derivative)
        gamma = state['step']['gamma']
        if index + 1 < len(native_states):
            f0 = objective(material, d1, d2, P)['fgw']
            f1 = objective(material, d1, d2, P+delta)['fgw']
            fm = objective(material, d1, d2, P+.5*delta)['fgw']
            a = 2*(f1+f0-2*fm); b = f1-f0-a
            report.check('line coefficient a', state['step']['a'], a)
            report.check('line coefficient b', state['step']['b'], b)
            candidates = [0., 1.] + ([float(np.clip(-b/(2*a),0,1))] if a > 0 else [])
            next_P = P+gamma*delta
            report.feasible('updated P', next_P)
            report.condition('non-increasing line search', objective(material,d1,d2,next_P)['fgw'] <= f0+TOL)
            report.check('line-search minimum', objective(material,d1,d2,next_P)['fgw'], min(objective(material,d1,d2,P+c*delta)['fgw'] for c in candidates))
            report.check('applied update', np.array(native_states[index+1]['correspondence']).reshape(n,m), next_P)
    report.prefix = ''
    P = np.array(final['correspondence']).reshape(n,m)
    report.feasible('final P', P)
    fresh_G = gradient(material,d1,d2,P)
    fresh_S = linear_ot(fresh_G,p,q)
    report.check('fresh terminal independent gap', final['finalGap'], np.sum(fresh_G*(P-fresh_S)))
    report.check('applied update count', final['iterations'], len(native_states)-1, 0)
    ref = reference(material,d1,d2)
    # Both runs start with exactly the captured uniform product. Preserve their
    # outcomes; equal costs here are evidence for this fixture, not a general promise.
    for term in ('material','structure','fgw'):
        report.check('independent CPU final '+term, final['costs'][term], ref['costs'][term])
    pot_P, log = ot.gromov.fused_gromov_wasserstein(material,d1,d2,p,q,G0=np.outer(p,q),log=True,**POT_CONFIG)
    report.feasible('POT final P', pot_P)
    report.check('POT final objective', final['costs']['fgw'], objective(material,d1,d2,pot_P)['fgw'])
    report.check('POT reported objective', log['fgw_dist'], objective(material,d1,d2,pot_P)['fgw'])
    correspondences = []
    for i, source in enumerate(left['sources']):
        expected_code = 'R5' if source['materialId'] == 'R4' else source['materialId']
        j = next(j for j,s in enumerate(right['sources']) if s['materialId']==expected_code)
        correspondences.append(dict(left=source['occurrenceId'], right=right['sources'][j]['occurrenceId'],
                                    label=source['materialId']+'↔'+expected_code, mass=P[i,j], share=P[i,j]/p[i], attributeDistance=material[i,j],
                                    structureCostContribution=float(P[i,j]*np.sum((d1[i,:,None]-d2[j,None,:])**2*P))))
    status = 'PASS' if all(c['result']=='PASS' for c in report.checks) else 'FAIL'
    output = dict(status=status, tolerance=TOL, potVersion=ot.__version__, potConfig=POT_CONFIG, numericalConfig=settings,
                  effectiveThresholds=dict(leaf=captured["leafThreshold"], stop=captured["stopThreshold"]),
                  initial=np.outer(p,q), capturedInputs=captured, checks=report.checks,
                  independentCpu=ref, potCorrespondence=pot_P, potLosses=log['loss'],
                  labels=correspondences, semanticAcceptance='UNVERIFIED: model-specific quality thresholds are not calibrated')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(serialisable(output),indent=2,allow_nan=False)+'\n')
    maximum=max(c['maximumAbsoluteError'] or 0 for c in report.checks)
    args.output.with_suffix('.md').write_text(f'# Real-model FGW validation\n\n{status}; {len(report.checks)} checks; maximum absolute error {maximum:.12g}; POT {ot.__version__}.\n\n'
        + f'Final costs: {final["costs"]}; status {final["status"]}; updates {final["iterations"]}; fresh gap {final["finalGap"]:.12g}.\n\n'
        + '\n'.join(f'- {c["label"]}: mass {c["mass"]:.12g}, row share {c["share"]:.12g}, attribute distance {c["attributeDistance"]:.12g}, structure contribution {c["structureCostContribution"]:.12g}.' for c in correspondences)
        + '\n\nSemantic acceptance remains unverified until model-specific thresholds are calibrated.\n')
    print(status,len(report.checks),'checks; maximum error',maximum)
    if status != 'PASS':
        print([(c['name'],c['maximumAbsoluteError']) for c in report.checks if c['result']=='FAIL'])
        raise SystemExit(1)


if __name__ == '__main__':
    main()
