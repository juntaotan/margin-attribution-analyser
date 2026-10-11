# Default combined simplex validation

- PASS: CTest 5/5 without skips; default engine/session and reference/individual modes.
- PASS: 824 prescribed independent tensor, HiGHS and POT numerical checks, including device cold/warm OT; maximum absolute error 4.444455914409673e-11 at tolerance 1e-8.
- PASS: `benchmark-default.json` selects mode 3, completes 100/100 comparisons, matches all 16 independently checked reference targets and exercises both optimisations.
- PASS: `cli-default.json` selects mode 3, completes 100/100 comparisons, matches all 16 independently checked reference targets and exercises both optimisations.
- PASS: `cli-default-with-slots.json` selects mode 3, completes 100/100 comparisons, matches all 16 independently checked reference targets and exercises both optimisations.

These 100-comparison runs verify default selection and numerical equivalence; they are not new throughput conclusions. The preceding experiment contains the six-tier performance and sanitizer evidence for the same combined algorithm.
