# FGW numerical validation

CPU/reference result: PASS
CUDA coverage: UNVERIFIED: no native execution report supplied

Versions: `{"python": "3.14.3", "numpy": "2.5.3", "scipy": "1.18.1", "POT": "0.9.7.post1"}`.

POT configuration: `{"loss_fun": "square_loss", "symmetric": true, "alpha": 0.8, "armijo": false, "max_iter": 200, "tol_abs": 1e-06, "tol_rel": 1e-05}`, explicit FP64 inputs, `G0=outer(p,q)`.
FP64 tolerance: `1e-8`. No loss scaling or distance normalisation.

| Check | Maximum absolute error | Result |
| --- | ---: | --- |
| M_attr | 0 | PASS |
| D1 | 0 | PASS |
| D2 | 0 | PASS |
| p | 0 | PASS |
| q | 0 | PASS |
| CPU fresh final gap trace | 0 | PASS |
| CPU iteration 0 P | 0 | PASS |
| CPU iteration 0 G | 3.33337801806e-11 | PASS |
| CPU iteration 0 S | 2.77555756156e-17 | PASS |
| CPU iteration 0 delta | 2.77555756156e-17 | PASS |
| CPU iteration 0 gap | 3.3333558136e-11 | PASS |
| CPU iteration 0 material | 3.33333360913e-11 | PASS |
| CPU iteration 0 structure | 4.44443370995e-11 | PASS |
| CPU iteration 0 fgw | 1.11111120304e-11 | PASS |
| CPU iteration 0 gamma | 0 | PASS |
| CPU iteration 0 P finite/nonnegative | 0 | PASS |
| CPU iteration 0 P row errors | 0 | PASS |
| CPU iteration 0 P column errors | 0 | PASS |
| CPU iteration 0 P total | 1.11022302463e-16 | PASS |
| CPU iteration 0 S finite/nonnegative | 0 | PASS |
| CPU iteration 0 S row errors | 5.55111512313e-17 | PASS |
| CPU iteration 0 S column errors | 0 | PASS |
| CPU iteration 0 S total | 0 | PASS |
| CPU iteration 0 direction row sums | 2.77555756156e-17 | PASS |
| CPU iteration 0 direction column sums | 0 | PASS |
| CPU iteration 0 feasible directional derivative 0 | 8.00359778452e-13 | PASS |
| CPU iteration 0 feasible directional derivative 1 | 8.00359778452e-13 | PASS |
| CPU iteration 1 P | 2.77555756156e-17 | PASS |
| CPU iteration 1 G | 3.33333360913e-11 | PASS |
| CPU iteration 1 S | 2.77555756156e-17 | PASS |
| CPU iteration 1 gap | 0 | PASS |
| CPU iteration 1 material | 3.33333360913e-11 | PASS |
| CPU iteration 1 structure | 1.11022302463e-16 | PASS |
| CPU iteration 1 fgw | 3.33334471136e-11 | PASS |
| CPU iteration 1 P finite/nonnegative | 0 | PASS |
| CPU iteration 1 P row errors | 5.55111512313e-17 | PASS |
| CPU iteration 1 P column errors | 0 | PASS |
| CPU iteration 1 P total | 0 | PASS |
| CPU iteration 1 S finite/nonnegative | 0 | PASS |
| CPU iteration 1 S row errors | 5.55111512313e-17 | PASS |
| CPU iteration 1 S column errors | 0 | PASS |
| CPU iteration 1 S total | 0 | PASS |
| CPU iteration 1 direction row sums | 0 | PASS |
| CPU iteration 1 direction column sums | 0 | PASS |
| CPU iteration 1 feasible directional derivative 0 | 2.74996692085e-11 | PASS |
| CPU objective monotonicity | 0 | PASS |
| CPU convergence | 0 | PASS |
| POT iteration 0 structure | 1.11022302463e-16 | PASS |
| POT iteration 0 gradient | 2.22044604925e-16 | PASS |
| POT iteration 0 exact OT | 5.55111512313e-17 | PASS |
| POT iteration 1 structure | 1.11022302463e-16 | PASS |
| POT iteration 1 gradient | 4.4408920985e-16 | PASS |
| POT iteration 1 exact OT | 0 | PASS |
| POT final P | 2.77555756156e-17 | PASS |
| POT final FGW | 3.33336691583e-11 | PASS |
| POT final P finite/nonnegative | 0 | PASS |
| POT final P row errors | 5.55111512313e-17 | PASS |
| POT final P column errors | 0 | PASS |
| POT final P total | 0 | PASS |
| CPU ordering best cost | 0 | PASS |
| POT ordering start 0 finite/nonnegative | 0 | PASS |
| POT ordering start 0 row errors | 0 | PASS |
| POT ordering start 0 column errors | 0 | PASS |
| POT ordering start 0 total | 0 | PASS |
| POT ordering start 1 finite/nonnegative | 0 | PASS |
| POT ordering start 1 row errors | 0 | PASS |
| POT ordering start 1 column errors | 0 | PASS |
| POT ordering start 1 total | 0 | PASS |
| POT ordering best cost | 1.66533453694e-16 | PASS |
| HOST native dimensions | 0 | PASS |
| HOST native material dimensions | 0 | PASS |
| HOST native material | 0 | PASS |
| HOST native d1 dimensions | 0 | PASS |
| HOST native d1 | 0 | PASS |
| HOST native d2 dimensions | 0 | PASS |
| HOST native d2 | 0 | PASS |
| HOST native fixed p | 0 | PASS |
| HOST native fixed q | 0 | PASS |
| HOST native fresh terminal gap state | 0 | PASS |
| HOST native iteration 0 P dimensions | 0 | PASS |
| HOST native iteration 0 P | 0 | PASS |
| HOST native iteration 0 P finite/nonnegative | 0 | PASS |
| HOST native iteration 0 P row errors | 0 | PASS |
| HOST native iteration 0 P column errors | 0 | PASS |
| HOST native iteration 0 P total | 1.11022302463e-16 | PASS |
| HOST native iteration 0 G dimensions | 0 | PASS |
| HOST native iteration 0 G | 2.22044604925e-16 | PASS |
| HOST native iteration 0 S dimensions | 0 | PASS |
| HOST native iteration 0 S | 2.77555756156e-17 | PASS |
| HOST native iteration 0 S finite/nonnegative | 0 | PASS |
| HOST native iteration 0 S row errors | 0 | PASS |
| HOST native iteration 0 S column errors | 0 | PASS |
| HOST native iteration 0 S total | 0 | PASS |
| HOST native iteration 0 delta dimensions | 0 | PASS |
| HOST native iteration 0 delta | 2.77555756156e-17 | PASS |
| HOST native iteration 0 independently differentiated gradient | 2.22044604925e-16 | PASS |
| HOST native iteration 0 original material | 0 | PASS |
| HOST native iteration 0 original structure | 1.11022302463e-16 | PASS |
| HOST native iteration 0 original fgw | 2.22044604925e-16 | PASS |
| HOST native iteration 0 fresh gap | 0 | PASS |
| HOST native iteration 0 independent HiGHS OT objective | 0 | PASS |
| HOST native iteration 0 OT dual certificate | 0 | PASS |
| HOST native iteration 0 reduced cost violation | 0 | PASS |
| HOST native iteration 0 gamma | 0 | PASS |
| HOST native iteration 0 a | 5.55111512313e-17 | PASS |
| HOST native iteration 0 b | 1.66533453694e-16 | PASS |
| HOST native iteration 1 P dimensions | 0 | PASS |
| HOST native iteration 1 P | 2.77555756156e-17 | PASS |
| HOST native iteration 1 P finite/nonnegative | 0 | PASS |
| HOST native iteration 1 P row errors | 0 | PASS |
| HOST native iteration 1 P column errors | 0 | PASS |
| HOST native iteration 1 P total | 0 | PASS |
| HOST native iteration 1 G dimensions | 0 | PASS |
| HOST native iteration 1 G | 2.22044604925e-16 | PASS |
| HOST native iteration 1 S dimensions | 0 | PASS |
| HOST native iteration 1 S | 2.77555756156e-17 | PASS |
| HOST native iteration 1 S finite/nonnegative | 0 | PASS |
| HOST native iteration 1 S row errors | 0 | PASS |
| HOST native iteration 1 S column errors | 0 | PASS |
| HOST native iteration 1 S total | 0 | PASS |
| HOST native iteration 1 delta dimensions | 0 | PASS |
| HOST native iteration 1 delta | 0 | PASS |
| HOST native iteration 1 independently differentiated gradient | 2.22044604925e-16 | PASS |
| HOST native iteration 1 original material | 0 | PASS |
| HOST native iteration 1 original structure | 0 | PASS |
| HOST native iteration 1 original fgw | 0 | PASS |
| HOST native iteration 1 fresh gap | 0 | PASS |
| HOST native iteration 1 independent HiGHS OT objective | 2.22044604925e-16 | PASS |
| HOST native iteration 1 OT dual certificate | 0 | PASS |
| HOST native iteration 1 reduced cost violation | 0 | PASS |
| HOST native final P | 2.77555756156e-17 | PASS |
| HOST native final FGW | 0 | PASS |
| HOST native final gap | 0 | PASS |
| HOST native convergence | 0 | PASS |
| HOST unequal-size multi-start count | 0 | PASS |
| HOST feasible unequal start final P | 0 | PASS |
| HOST feasible unequal start costs | 3.33334471136e-11 | PASS |
| HOST feasible unequal start fresh convergence | 0 | PASS |
| HOST feasible unequal start gradient | 3.33333360913e-11 | PASS |
| HOST host ordering best FGW | 0 | PASS |
| HOST host ordering start 0 finite/nonnegative | 0 | PASS |
| HOST host ordering start 0 row errors | 0 | PASS |
| HOST host ordering start 0 column errors | 0 | PASS |
| HOST host ordering start 0 total | 0 | PASS |
| HOST host ordering start 1 finite/nonnegative | 0 | PASS |
| HOST host ordering start 1 row errors | 0 | PASS |
| HOST host ordering start 1 column errors | 0 | PASS |
| HOST host ordering start 1 total | 0 | PASS |
| HOST host orderingReverse best FGW | 0 | PASS |
| HOST host orderingReverse start 0 finite/nonnegative | 0 | PASS |
| HOST host orderingReverse start 0 row errors | 0 | PASS |
| HOST host orderingReverse start 0 column errors | 0 | PASS |
| HOST host orderingReverse start 0 total | 0 | PASS |
| HOST host orderingReverse start 1 finite/nonnegative | 0 | PASS |
| HOST host orderingReverse start 1 row errors | 0 | PASS |
| HOST host orderingReverse start 1 column errors | 0 | PASS |
| HOST host orderingReverse start 1 total | 0 | PASS |
| CPU Exact OT fixture 0 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 0 row errors | 0 | PASS |
| CPU Exact OT fixture 0 column errors | 0 | PASS |
| CPU Exact OT fixture 0 total | 0 | PASS |
| CPU Exact OT fixture 0 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 0 POT objective | 0 | PASS |
| CPU Exact OT fixture 0 dual objective | 0 | PASS |
| CPU Exact OT fixture 0 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 0 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 0 row errors | 0 | PASS |
| CPU Warm exact OT fixture 0 column errors | 0 | PASS |
| CPU Warm exact OT fixture 0 total | 0 | PASS |
| CPU Warm exact OT fixture 0 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 0 dual objective | 0 | PASS |
| CPU Warm exact OT fixture 0 reduced costs | 0 | PASS |
| CPU Exact OT fixture 1 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 1 row errors | 0 | PASS |
| CPU Exact OT fixture 1 column errors | 0 | PASS |
| CPU Exact OT fixture 1 total | 0 | PASS |
| CPU Exact OT fixture 1 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 1 POT objective | 0 | PASS |
| CPU Exact OT fixture 1 dual objective | 4.4408920985e-16 | PASS |
| CPU Exact OT fixture 1 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 1 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 1 row errors | 0 | PASS |
| CPU Warm exact OT fixture 1 column errors | 0 | PASS |
| CPU Warm exact OT fixture 1 total | 0 | PASS |
| CPU Warm exact OT fixture 1 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 1 dual objective | 0 | PASS |
| CPU Warm exact OT fixture 1 reduced costs | 0 | PASS |
| CPU Exact OT fixture 2 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 2 row errors | 0 | PASS |
| CPU Exact OT fixture 2 column errors | 0 | PASS |
| CPU Exact OT fixture 2 total | 0 | PASS |
| CPU Exact OT fixture 2 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 2 POT objective | 0 | PASS |
| CPU Exact OT fixture 2 dual objective | 0 | PASS |
| CPU Exact OT fixture 2 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 2 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 2 row errors | 0 | PASS |
| CPU Warm exact OT fixture 2 column errors | 0 | PASS |
| CPU Warm exact OT fixture 2 total | 0 | PASS |
| CPU Warm exact OT fixture 2 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 2 dual objective | 0 | PASS |
| CPU Warm exact OT fixture 2 reduced costs | 0 | PASS |
| CPU Exact OT fixture 3 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 3 row errors | 0 | PASS |
| CPU Exact OT fixture 3 column errors | 0 | PASS |
| CPU Exact OT fixture 3 total | 0 | PASS |
| CPU Exact OT fixture 3 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 3 POT objective | 0 | PASS |
| CPU Exact OT fixture 3 dual objective | 0 | PASS |
| CPU Exact OT fixture 3 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 3 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 3 row errors | 0 | PASS |
| CPU Warm exact OT fixture 3 column errors | 0 | PASS |
| CPU Warm exact OT fixture 3 total | 0 | PASS |
| CPU Warm exact OT fixture 3 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 3 dual objective | 0 | PASS |
| CPU Warm exact OT fixture 3 reduced costs | 0 | PASS |
| CPU Exact OT fixture 4 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 4 row errors | 0 | PASS |
| CPU Exact OT fixture 4 column errors | 0 | PASS |
| CPU Exact OT fixture 4 total | 0 | PASS |
| CPU Exact OT fixture 4 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 4 POT objective | 0 | PASS |
| CPU Exact OT fixture 4 dual objective | 1.11022302463e-16 | PASS |
| CPU Exact OT fixture 4 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 4 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 4 row errors | 0 | PASS |
| CPU Warm exact OT fixture 4 column errors | 0 | PASS |
| CPU Warm exact OT fixture 4 total | 0 | PASS |
| CPU Warm exact OT fixture 4 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 4 dual objective | 0 | PASS |
| CPU Warm exact OT fixture 4 reduced costs | 0 | PASS |
| CPU Exact OT fixture 5 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 5 row errors | 0 | PASS |
| CPU Exact OT fixture 5 column errors | 0 | PASS |
| CPU Exact OT fixture 5 total | 1.11022302463e-16 | PASS |
| CPU Exact OT fixture 5 HiGHS objective | 2.22044604925e-16 | PASS |
| CPU Exact OT fixture 5 POT objective | 2.22044604925e-16 | PASS |
| CPU Exact OT fixture 5 dual objective | 4.4408920985e-16 | PASS |
| CPU Exact OT fixture 5 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 5 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 5 row errors | 0 | PASS |
| CPU Warm exact OT fixture 5 column errors | 0 | PASS |
| CPU Warm exact OT fixture 5 total | 1.11022302463e-16 | PASS |
| CPU Warm exact OT fixture 5 POT objective | 4.4408920985e-16 | PASS |
| CPU Warm exact OT fixture 5 dual objective | 4.4408920985e-16 | PASS |
| CPU Warm exact OT fixture 5 reduced costs | 0 | PASS |
| CPU Exact OT fixture 6 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 6 row errors | 0 | PASS |
| CPU Exact OT fixture 6 column errors | 0 | PASS |
| CPU Exact OT fixture 6 total | 0 | PASS |
| CPU Exact OT fixture 6 HiGHS objective | 2.22044604925e-16 | PASS |
| CPU Exact OT fixture 6 POT objective | 2.22044604925e-16 | PASS |
| CPU Exact OT fixture 6 dual objective | 0 | PASS |
| CPU Exact OT fixture 6 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 6 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 6 row errors | 0 | PASS |
| CPU Warm exact OT fixture 6 column errors | 0 | PASS |
| CPU Warm exact OT fixture 6 total | 1.11022302463e-16 | PASS |
| CPU Warm exact OT fixture 6 POT objective | 4.4408920985e-16 | PASS |
| CPU Warm exact OT fixture 6 dual objective | 4.4408920985e-16 | PASS |
| CPU Warm exact OT fixture 6 reduced costs | 0 | PASS |
| CPU Exact OT fixture 7 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 7 row errors | 0 | PASS |
| CPU Exact OT fixture 7 column errors | 0 | PASS |
| CPU Exact OT fixture 7 total | 1.11022302463e-16 | PASS |
| CPU Exact OT fixture 7 HiGHS objective | 1.11022302463e-16 | PASS |
| CPU Exact OT fixture 7 POT objective | 2.22044604925e-16 | PASS |
| CPU Exact OT fixture 7 dual objective | 1.11022302463e-16 | PASS |
| CPU Exact OT fixture 7 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 7 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 7 row errors | 2.77555756156e-17 | PASS |
| CPU Warm exact OT fixture 7 column errors | 0 | PASS |
| CPU Warm exact OT fixture 7 total | 1.11022302463e-16 | PASS |
| CPU Warm exact OT fixture 7 POT objective | 2.22044604925e-16 | PASS |
| CPU Warm exact OT fixture 7 dual objective | 2.22044604925e-16 | PASS |
| CPU Warm exact OT fixture 7 reduced costs | 0 | PASS |
| CPU Exact OT fixture 8 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 8 row errors | 0 | PASS |
| CPU Exact OT fixture 8 column errors | 2.77555756156e-17 | PASS |
| CPU Exact OT fixture 8 total | 0 | PASS |
| CPU Exact OT fixture 8 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 8 POT objective | 0 | PASS |
| CPU Exact OT fixture 8 dual objective | 0 | PASS |
| CPU Exact OT fixture 8 reduced costs | 5.55111512313e-17 | PASS |
| CPU Warm exact OT fixture 8 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 8 row errors | 0 | PASS |
| CPU Warm exact OT fixture 8 column errors | 0 | PASS |
| CPU Warm exact OT fixture 8 total | 2.22044604925e-16 | PASS |
| CPU Warm exact OT fixture 8 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 8 dual objective | 4.4408920985e-16 | PASS |
| CPU Warm exact OT fixture 8 reduced costs | 2.22044604925e-16 | PASS |
| CPU Exact OT fixture 9 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 9 row errors | 0 | PASS |
| CPU Exact OT fixture 9 column errors | 2.77555756156e-17 | PASS |
| CPU Exact OT fixture 9 total | 0 | PASS |
| CPU Exact OT fixture 9 HiGHS objective | 2.22044604925e-16 | PASS |
| CPU Exact OT fixture 9 POT objective | 0 | PASS |
| CPU Exact OT fixture 9 dual objective | 2.22044604925e-16 | PASS |
| CPU Exact OT fixture 9 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 9 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 9 row errors | 0 | PASS |
| CPU Warm exact OT fixture 9 column errors | 2.77555756156e-17 | PASS |
| CPU Warm exact OT fixture 9 total | 1.11022302463e-16 | PASS |
| CPU Warm exact OT fixture 9 POT objective | 2.22044604925e-16 | PASS |
| CPU Warm exact OT fixture 9 dual objective | 0 | PASS |
| CPU Warm exact OT fixture 9 reduced costs | 0 | PASS |
| CPU Exact OT fixture 10 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 10 row errors | 0 | PASS |
| CPU Exact OT fixture 10 column errors | 0 | PASS |
| CPU Exact OT fixture 10 total | 0 | PASS |
| CPU Exact OT fixture 10 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 10 POT objective | 0 | PASS |
| CPU Exact OT fixture 10 dual objective | 4.4408920985e-16 | PASS |
| CPU Exact OT fixture 10 reduced costs | 2.22044604925e-16 | PASS |
| CPU Warm exact OT fixture 10 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 10 row errors | 0 | PASS |
| CPU Warm exact OT fixture 10 column errors | 0 | PASS |
| CPU Warm exact OT fixture 10 total | 0 | PASS |
| CPU Warm exact OT fixture 10 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 10 dual objective | 0 | PASS |
| CPU Warm exact OT fixture 10 reduced costs | 2.22044604925e-16 | PASS |
| CPU Exact OT fixture 11 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 11 row errors | 0 | PASS |
| CPU Exact OT fixture 11 column errors | 0 | PASS |
| CPU Exact OT fixture 11 total | 0 | PASS |
| CPU Exact OT fixture 11 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 11 POT objective | 0 | PASS |
| CPU Exact OT fixture 11 dual objective | 0 | PASS |
| CPU Exact OT fixture 11 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 11 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 11 row errors | 0 | PASS |
| CPU Warm exact OT fixture 11 column errors | 0 | PASS |
| CPU Warm exact OT fixture 11 total | 0 | PASS |
| CPU Warm exact OT fixture 11 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 11 dual objective | 0 | PASS |
| CPU Warm exact OT fixture 11 reduced costs | 0 | PASS |
| CPU Exact OT fixture 12 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 12 row errors | 0 | PASS |
| CPU Exact OT fixture 12 column errors | 0 | PASS |
| CPU Exact OT fixture 12 total | 0 | PASS |
| CPU Exact OT fixture 12 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 12 POT objective | 4.4408920985e-16 | PASS |
| CPU Exact OT fixture 12 dual objective | 0 | PASS |
| CPU Exact OT fixture 12 reduced costs | 8.881784197e-16 | PASS |
| CPU Warm exact OT fixture 12 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 12 row errors | 0 | PASS |
| CPU Warm exact OT fixture 12 column errors | 0 | PASS |
| CPU Warm exact OT fixture 12 total | 0 | PASS |
| CPU Warm exact OT fixture 12 POT objective | 4.4408920985e-16 | PASS |
| CPU Warm exact OT fixture 12 dual objective | 4.4408920985e-16 | PASS |
| CPU Warm exact OT fixture 12 reduced costs | 8.881784197e-16 | PASS |
| CPU Exact OT fixture 13 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 13 row errors | 0 | PASS |
| CPU Exact OT fixture 13 column errors | 0 | PASS |
| CPU Exact OT fixture 13 total | 0 | PASS |
| CPU Exact OT fixture 13 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 13 POT objective | 8.881784197e-16 | PASS |
| CPU Exact OT fixture 13 dual objective | 4.4408920985e-16 | PASS |
| CPU Exact OT fixture 13 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 13 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 13 row errors | 0 | PASS |
| CPU Warm exact OT fixture 13 column errors | 0 | PASS |
| CPU Warm exact OT fixture 13 total | 0 | PASS |
| CPU Warm exact OT fixture 13 POT objective | 8.881784197e-16 | PASS |
| CPU Warm exact OT fixture 13 dual objective | 4.4408920985e-16 | PASS |
| CPU Warm exact OT fixture 13 reduced costs | 0 | PASS |
| CPU Exact OT fixture 14 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 14 row errors | 0 | PASS |
| CPU Exact OT fixture 14 column errors | 0 | PASS |
| CPU Exact OT fixture 14 total | 0 | PASS |
| CPU Exact OT fixture 14 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 14 POT objective | 0 | PASS |
| CPU Exact OT fixture 14 dual objective | 2.6645352591e-15 | PASS |
| CPU Exact OT fixture 14 reduced costs | 8.881784197e-16 | PASS |
| CPU Warm exact OT fixture 14 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 14 row errors | 0 | PASS |
| CPU Warm exact OT fixture 14 column errors | 0 | PASS |
| CPU Warm exact OT fixture 14 total | 0 | PASS |
| CPU Warm exact OT fixture 14 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 14 dual objective | 3.5527136788e-15 | PASS |
| CPU Warm exact OT fixture 14 reduced costs | 8.881784197e-16 | PASS |
| CPU Exact OT fixture 15 finite/nonnegative | 0 | PASS |
| CPU Exact OT fixture 15 row errors | 0 | PASS |
| CPU Exact OT fixture 15 column errors | 0 | PASS |
| CPU Exact OT fixture 15 total | 0 | PASS |
| CPU Exact OT fixture 15 HiGHS objective | 0 | PASS |
| CPU Exact OT fixture 15 POT objective | 0 | PASS |
| CPU Exact OT fixture 15 dual objective | 5.77315972805e-15 | PASS |
| CPU Exact OT fixture 15 reduced costs | 0 | PASS |
| CPU Warm exact OT fixture 15 finite/nonnegative | 0 | PASS |
| CPU Warm exact OT fixture 15 row errors | 0 | PASS |
| CPU Warm exact OT fixture 15 column errors | 0 | PASS |
| CPU Warm exact OT fixture 15 total | 0 | PASS |
| CPU Warm exact OT fixture 15 POT objective | 0 | PASS |
| CPU Warm exact OT fixture 15 dual objective | 5.77315972805e-15 | PASS |
| CPU Warm exact OT fixture 15 reduced costs | 0 | PASS |

## Actual and expected values

### M_attr

```json
{
  "actual": [
    [
      0.0,
      1.0,
      4.0
    ],
    [
      4.0,
      1.0,
      0.0
    ]
  ],
  "expected": [
    [
      0.0,
      1.0,
      4.0
    ],
    [
      4.0,
      1.0,
      0.0
    ]
  ]
}
```

### D1

```json
{
  "actual": [
    [
      0.0,
      1.0
    ],
    [
      1.0,
      0.0
    ]
  ],
  "expected": [
    [
      0.0,
      1.0
    ],
    [
      1.0,
      0.0
    ]
  ]
}
```

### D2

```json
{
  "actual": [
    [
      0.0,
      1.0,
      2.0
    ],
    [
      1.0,
      0.0,
      1.0
    ],
    [
      2.0,
      1.0,
      0.0
    ]
  ],
  "expected": [
    [
      0.0,
      1.0,
      2.0
    ],
    [
      1.0,
      0.0,
      1.0
    ],
    [
      2.0,
      1.0,
      0.0
    ]
  ]
}
```

### p

```json
{
  "actual": [
    0.5,
    0.5
  ],
  "expected": [
    0.5,
    0.5
  ]
}
```

### q

```json
{
  "actual": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ],
  "expected": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ]
}
```

### CPU iteration 0 P

```json
{
  "actual": [
    [
      0.16666666666666666,
      0.16666666666666666,
      0.16666666666666666
    ],
    [
      0.16666666666666666,
      0.16666666666666666,
      0.16666666666666666
    ]
  ],
  "expected": [
    [
      0.16666666666666666,
      0.16666666666666666,
      0.16666666666666666
    ],
    [
      0.16666666666666666,
      0.16666666666666666,
      0.16666666666666666
    ]
  ]
}
```

### CPU iteration 0 G

```json
{
  "actual": [
    [
      1.8666666666666665,
      1.0,
      2.666666666666666
    ],
    [
      2.666666666666666,
      1.0,
      1.8666666666666665
    ]
  ],
  "expected": [
    [
      1.8666666667,
      1.0,
      2.6666666667
    ],
    [
      2.6666666667,
      1.0,
      1.8666666667
    ]
  ]
}
```

### CPU iteration 0 S

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ]
}
```

### CPU iteration 0 delta

```json
{
  "actual": [
    [
      0.16666666666666666,
      2.7755575615628914e-17,
      -0.16666666666666666
    ],
    [
      -0.16666666666666666,
      -2.7755575615628914e-17,
      0.16666666666666666
    ]
  ],
  "expected": [
    [
      0.16666666666666666,
      0.0,
      -0.16666666666666666
    ],
    [
      -0.16666666666666666,
      0.0,
      0.16666666666666666
    ]
  ]
}
```

### CPU iteration 0 gap

```json
{
  "actual": 0.26666666666666644,
  "expected": 0.2666666667
}
```

### CPU iteration 0 material

```json
{
  "actual": 1.6666666666666667,
  "expected": 1.6666666667
}
```

### CPU iteration 0 structure

```json
{
  "actual": 0.9444444444444443,
  "expected": 0.9444444444
}
```

### CPU iteration 0 fgw

```json
{
  "actual": 1.0888888888888888,
  "expected": 1.0888888889
}
```

### CPU iteration 0 gamma

```json
{
  "actual": 1.0,
  "expected": 1.0
}
```

### CPU iteration 0 P finite/nonnegative

```json
{
  "actual": 1,
  "expected": 1
}
```

### CPU iteration 0 P row errors

```json
{
  "actual": [
    0.5,
    0.5
  ],
  "expected": [
    0.5,
    0.5
  ]
}
```

### CPU iteration 0 P column errors

```json
{
  "actual": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ],
  "expected": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ]
}
```

### CPU iteration 0 P total

```json
{
  "actual": 0.9999999999999999,
  "expected": 1.0
}
```

### CPU iteration 0 S finite/nonnegative

```json
{
  "actual": 1,
  "expected": 1
}
```

### CPU iteration 0 S row errors

```json
{
  "actual": [
    0.5,
    0.49999999999999994
  ],
  "expected": [
    0.5,
    0.5
  ]
}
```

### CPU iteration 0 S column errors

```json
{
  "actual": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ],
  "expected": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ]
}
```

### CPU iteration 0 S total

```json
{
  "actual": 1.0,
  "expected": 1.0
}
```

### CPU iteration 0 direction row sums

```json
{
  "actual": [
    2.7755575615628914e-17,
    -2.7755575615628914e-17
  ],
  "expected": [
    0.0,
    0.0
  ]
}
```

### CPU iteration 0 direction column sums

```json
{
  "actual": [
    0.0,
    0.0,
    0.0
  ],
  "expected": [
    0.0,
    0.0,
    0.0
  ]
}
```

### CPU iteration 0 feasible directional derivative 0

```json
{
  "actual": -0.8000000000008,
  "expected": -0.7999999999999996
}
```

### CPU iteration 0 feasible directional derivative 1

```json
{
  "actual": -0.8000000000008,
  "expected": -0.7999999999999996
}
```

### CPU iteration 1 P

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ]
}
```

### CPU iteration 1 G

```json
{
  "actual": [
    [
      0.8,
      0.9999999999999999,
      3.7333333333333334
    ],
    [
      3.7333333333333334,
      1.0,
      0.7999999999999999
    ]
  ],
  "expected": [
    [
      0.8,
      1.0,
      3.7333333333
    ],
    [
      3.7333333333,
      1.0,
      0.8
    ]
  ]
}
```

### CPU iteration 1 S

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ]
}
```

### CPU iteration 1 gap

```json
{
  "actual": 0.0,
  "expected": 0.0
}
```

### CPU iteration 1 material

```json
{
  "actual": 0.3333333333333333,
  "expected": 0.3333333333
}
```

### CPU iteration 1 structure

```json
{
  "actual": 0.4999999999999999,
  "expected": 0.5
}
```

### CPU iteration 1 fgw

```json
{
  "actual": 0.46666666666666656,
  "expected": 0.4666666667
}
```

### CPU iteration 1 P finite/nonnegative

```json
{
  "actual": 1,
  "expected": 1
}
```

### CPU iteration 1 P row errors

```json
{
  "actual": [
    0.5,
    0.49999999999999994
  ],
  "expected": [
    0.5,
    0.5
  ]
}
```

### CPU iteration 1 P column errors

```json
{
  "actual": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ],
  "expected": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ]
}
```

### CPU iteration 1 P total

```json
{
  "actual": 1.0,
  "expected": 1.0
}
```

### CPU iteration 1 S finite/nonnegative

```json
{
  "actual": 1,
  "expected": 1
}
```

### CPU iteration 1 S row errors

```json
{
  "actual": [
    0.5,
    0.49999999999999994
  ],
  "expected": [
    0.5,
    0.5
  ]
}
```

### CPU iteration 1 S column errors

```json
{
  "actual": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ],
  "expected": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ]
}
```

### CPU iteration 1 S total

```json
{
  "actual": 1.0,
  "expected": 1.0
}
```

### CPU iteration 1 direction row sums

```json
{
  "actual": [
    0.0,
    0.0
  ],
  "expected": [
    0.0,
    0.0
  ]
}
```

### CPU iteration 1 direction column sums

```json
{
  "actual": [
    0.0,
    0.0,
    0.0
  ],
  "expected": [
    0.0,
    0.0,
    0.0
  ]
}
```

### CPU iteration 1 feasible directional derivative 0

```json
{
  "actual": 0.9777777778052775,
  "expected": 0.9777777777777779
}
```

### POT iteration 0 structure

```json
{
  "actual": 0.9444444444444442,
  "expected": 0.9444444444444443
}
```

### POT iteration 0 gradient

```json
{
  "actual": [
    [
      1.8666666666666665,
      0.9999999999999998,
      2.666666666666666
    ],
    [
      2.666666666666666,
      0.9999999999999998,
      1.8666666666666665
    ]
  ],
  "expected": [
    [
      1.8666666666666665,
      1.0,
      2.666666666666666
    ],
    [
      2.666666666666666,
      1.0,
      1.8666666666666665
    ]
  ]
}
```

### POT iteration 0 exact OT

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666663,
      0.0
    ],
    [
      0.0,
      0.16666666666666669,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ]
}
```

### POT iteration 1 structure

```json
{
  "actual": 0.5,
  "expected": 0.4999999999999999
}
```

### POT iteration 1 gradient

```json
{
  "actual": [
    [
      0.8,
      0.9999999999999998,
      3.7333333333333334
    ],
    [
      3.733333333333333,
      0.9999999999999998,
      0.8
    ]
  ],
  "expected": [
    [
      0.8,
      0.9999999999999999,
      3.7333333333333334
    ],
    [
      3.7333333333333334,
      1.0,
      0.7999999999999999
    ]
  ]
}
```

### POT iteration 1 exact OT

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ]
}
```

### POT final P

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666663,
      0.0
    ],
    [
      0.0,
      0.16666666666666669,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ]
}
```

### HOST native material

```json
{
  "actual": [
    [
      0.0,
      1.0,
      4.0
    ],
    [
      4.0,
      1.0,
      0.0
    ]
  ],
  "expected": [
    [
      0.0,
      1.0,
      4.0
    ],
    [
      4.0,
      1.0,
      0.0
    ]
  ]
}
```

### HOST native d1

```json
{
  "actual": [
    [
      0.0,
      1.0
    ],
    [
      1.0,
      0.0
    ]
  ],
  "expected": [
    [
      0.0,
      1.0
    ],
    [
      1.0,
      0.0
    ]
  ]
}
```

### HOST native d2

```json
{
  "actual": [
    [
      0.0,
      1.0,
      2.0
    ],
    [
      1.0,
      0.0,
      1.0
    ],
    [
      2.0,
      1.0,
      0.0
    ]
  ],
  "expected": [
    [
      0.0,
      1.0,
      2.0
    ],
    [
      1.0,
      0.0,
      1.0
    ],
    [
      2.0,
      1.0,
      0.0
    ]
  ]
}
```

### HOST native iteration 0 P dimensions

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 0 P

```json
{
  "actual": [
    [
      0.16666666666666666,
      0.16666666666666666,
      0.16666666666666666
    ],
    [
      0.16666666666666666,
      0.16666666666666666,
      0.16666666666666666
    ]
  ],
  "expected": [
    [
      0.16666666666666666,
      0.16666666666666666,
      0.16666666666666666
    ],
    [
      0.16666666666666666,
      0.16666666666666666,
      0.16666666666666666
    ]
  ]
}
```

### HOST native iteration 0 P finite/nonnegative

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 0 P row errors

```json
{
  "actual": [
    0.5,
    0.5
  ],
  "expected": [
    0.5,
    0.5
  ]
}
```

### HOST native iteration 0 P column errors

```json
{
  "actual": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ],
  "expected": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ]
}
```

### HOST native iteration 0 P total

```json
{
  "actual": 0.9999999999999999,
  "expected": 1.0
}
```

### HOST native iteration 0 G dimensions

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 0 G

```json
{
  "actual": [
    [
      1.8666666666666665,
      0.9999999999999998,
      2.666666666666666
    ],
    [
      2.666666666666666,
      0.9999999999999998,
      1.8666666666666665
    ]
  ],
  "expected": [
    [
      1.8666666666666665,
      1.0,
      2.666666666666666
    ],
    [
      2.666666666666666,
      1.0,
      1.8666666666666665
    ]
  ]
}
```

### HOST native iteration 0 S dimensions

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 0 S

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ]
}
```

### HOST native iteration 0 S finite/nonnegative

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 0 S row errors

```json
{
  "actual": [
    0.5,
    0.5
  ],
  "expected": [
    0.5,
    0.5
  ]
}
```

### HOST native iteration 0 S column errors

```json
{
  "actual": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ],
  "expected": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ]
}
```

### HOST native iteration 0 S total

```json
{
  "actual": 1.0,
  "expected": 1.0
}
```

### HOST native iteration 0 delta dimensions

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 0 delta

```json
{
  "actual": [
    [
      0.16666666666666666,
      0.0,
      -0.16666666666666666
    ],
    [
      -0.16666666666666666,
      0.0,
      0.16666666666666666
    ]
  ],
  "expected": [
    [
      0.16666666666666666,
      2.7755575615628914e-17,
      -0.16666666666666666
    ],
    [
      -0.16666666666666666,
      -2.7755575615628914e-17,
      0.16666666666666666
    ]
  ]
}
```

### HOST native iteration 0 independently differentiated gradient

```json
{
  "actual": [
    [
      1.8666666666666665,
      0.9999999999999998,
      2.666666666666666
    ],
    [
      2.666666666666666,
      0.9999999999999998,
      1.8666666666666665
    ]
  ],
  "expected": [
    [
      1.8666666666666665,
      1.0,
      2.666666666666666
    ],
    [
      2.666666666666666,
      1.0,
      1.8666666666666665
    ]
  ]
}
```

### HOST native iteration 0 original material

```json
{
  "actual": 1.6666666666666667,
  "expected": 1.6666666666666667
}
```

### HOST native iteration 0 original structure

```json
{
  "actual": 0.9444444444444442,
  "expected": 0.9444444444444443
}
```

### HOST native iteration 0 original fgw

```json
{
  "actual": 1.0888888888888886,
  "expected": 1.0888888888888888
}
```

### HOST native iteration 0 fresh gap

```json
{
  "actual": 0.26666666666666644,
  "expected": 0.26666666666666644
}
```

### HOST native iteration 0 independent HiGHS OT objective

```json
{
  "actual": 1.5777777777777775,
  "expected": 1.5777777777777775
}
```

### HOST native iteration 0 OT dual certificate

```json
{
  "actual": 1.5777777777777775,
  "expected": 1.5777777777777775
}
```

### HOST native iteration 0 reduced cost violation

```json
{
  "actual": 0.0,
  "expected": 0
}
```

### HOST native iteration 0 gamma

```json
{
  "actual": 1.0,
  "expected": 1.0
}
```

### HOST native iteration 0 a

```json
{
  "actual": -0.35555555555555557,
  "expected": -0.3555555555555556
}
```

### HOST native iteration 0 b

```json
{
  "actual": -0.26666666666666644,
  "expected": -0.2666666666666666
}
```

### HOST native iteration 1 P dimensions

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 1 P

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ]
}
```

### HOST native iteration 1 P finite/nonnegative

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 1 P row errors

```json
{
  "actual": [
    0.5,
    0.5
  ],
  "expected": [
    0.5,
    0.5
  ]
}
```

### HOST native iteration 1 P column errors

```json
{
  "actual": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ],
  "expected": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ]
}
```

### HOST native iteration 1 P total

```json
{
  "actual": 1.0,
  "expected": 1.0
}
```

### HOST native iteration 1 G dimensions

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 1 G

```json
{
  "actual": [
    [
      0.8,
      0.9999999999999998,
      3.7333333333333334
    ],
    [
      3.7333333333333334,
      0.9999999999999998,
      0.8
    ]
  ],
  "expected": [
    [
      0.8,
      0.9999999999999999,
      3.7333333333333334
    ],
    [
      3.7333333333333334,
      1.0,
      0.7999999999999999
    ]
  ]
}
```

### HOST native iteration 1 S dimensions

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 1 S

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ]
}
```

### HOST native iteration 1 S finite/nonnegative

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 1 S row errors

```json
{
  "actual": [
    0.5,
    0.5
  ],
  "expected": [
    0.5,
    0.5
  ]
}
```

### HOST native iteration 1 S column errors

```json
{
  "actual": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ],
  "expected": [
    0.3333333333333333,
    0.3333333333333333,
    0.3333333333333333
  ]
}
```

### HOST native iteration 1 S total

```json
{
  "actual": 1.0,
  "expected": 1.0
}
```

### HOST native iteration 1 delta dimensions

```json
{
  "actual": 1,
  "expected": 1
}
```

### HOST native iteration 1 delta

```json
{
  "actual": [
    [
      0.0,
      0.0,
      0.0
    ],
    [
      0.0,
      0.0,
      0.0
    ]
  ],
  "expected": [
    [
      0.0,
      0.0,
      0.0
    ],
    [
      0.0,
      0.0,
      0.0
    ]
  ]
}
```

### HOST native iteration 1 independently differentiated gradient

```json
{
  "actual": [
    [
      0.8,
      0.9999999999999998,
      3.7333333333333334
    ],
    [
      3.7333333333333334,
      0.9999999999999998,
      0.8
    ]
  ],
  "expected": [
    [
      0.8,
      1.0,
      3.7333333333333334
    ],
    [
      3.7333333333333334,
      1.0,
      0.8
    ]
  ]
}
```

### HOST native iteration 1 original material

```json
{
  "actual": 0.3333333333333333,
  "expected": 0.3333333333333333
}
```

### HOST native iteration 1 original structure

```json
{
  "actual": 0.4999999999999999,
  "expected": 0.4999999999999999
}
```

### HOST native iteration 1 original fgw

```json
{
  "actual": 0.46666666666666656,
  "expected": 0.46666666666666656
}
```

### HOST native iteration 1 fresh gap

```json
{
  "actual": -0.0,
  "expected": 0.0
}
```

### HOST native iteration 1 independent HiGHS OT objective

```json
{
  "actual": 0.8666666666666665,
  "expected": 0.8666666666666667
}
```

### HOST native iteration 1 OT dual certificate

```json
{
  "actual": 0.8666666666666665,
  "expected": 0.8666666666666665
}
```

### HOST native iteration 1 reduced cost violation

```json
{
  "actual": 0.0,
  "expected": 0
}
```

### HOST native final P

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666669,
      0.0
    ],
    [
      0.0,
      0.16666666666666663,
      0.3333333333333333
    ]
  ]
}
```

### HOST feasible unequal start final P

```json
{
  "actual": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ],
  "expected": [
    [
      0.3333333333333333,
      0.16666666666666666,
      0.0
    ],
    [
      0.0,
      0.16666666666666666,
      0.3333333333333333
    ]
  ]
}
```

### HOST feasible unequal start gradient

```json
{
  "actual": [
    [
      0.8,
      0.9999999999999998,
      3.7333333333333334
    ],
    [
      3.7333333333333334,
      0.9999999999999998,
      0.8
    ]
  ],
  "expected": [
    [
      0.8,
      1.0,
      3.7333333333
    ],
    [
      3.7333333333,
      1.0,
      0.8
    ]
  ]
}
```

## Iteration and convergence diagnostics

```json
{
  "costs": [
    {
      "material": 1.6666666666666667,
      "structure": 0.9444444444444443,
      "fgw": 1.0888888888888888
    },
    {
      "material": 0.3333333333333333,
      "structure": 0.4999999999999999,
      "fgw": 0.46666666666666656
    }
  ],
  "gaps": [
    0.26666666666666644,
    0.0
  ],
  "status": "FW_GAP_REACHED",
  "orderingStarts": [
    [
      [
        0.1111111111111111,
        0.1111111111111111,
        0.1111111111111111
      ],
      [
        0.1111111111111111,
        0.1111111111111111,
        0.1111111111111111
      ],
      [
        0.1111111111111111,
        0.1111111111111111,
        0.1111111111111111
      ]
    ],
    [
      [
        0.3333333333333333,
        0.0,
        0.0
      ],
      [
        0.0,
        0.0,
        0.3333333333333333
      ],
      [
        0.0,
        0.3333333333333333,
        0.0
      ]
    ]
  ],
  "orderingCosts": [
    0.0,
    0.0
  ],
  "orderingPotCosts": [
    -1.6653345369377348e-16,
    0.0
  ]
}
```

First-order stationarity is not a certificate of global optimality.
Native CUDA checks require a compatible NVIDIA driver/device. Absent CUDA evidence is not a passing device test.
