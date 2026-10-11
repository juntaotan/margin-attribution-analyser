Start time: 2026-10-11 15:38:12 Pacific/Auckland (UTC+13:00)
End time: 2026-10-11 15:38:43 Pacific/Auckland (UTC+13:00)
Summary: Keep readable task records and validation reports eligible for Git while ignoring raw task run artefacts.
Details:
- Changes: Updated `.gitignore` to ignore all non-Markdown files beneath `docs/tasks/`, including raw JSON results, logs, text snapshots, hashes, command captures and temporary Python scripts. Retained Markdown task summaries, numerical validation reports and benchmark reports. Preserved all local files and unrelated existing changes; no commit was created.
- Validation: PASS — checked every existing task file with `git check-ignore --no-index`: 212 non-Markdown artefacts totalling 205.21 MiB are ignored and 28 existing Markdown files remain eligible for tracking. No tracked task files match the new ignore rules, so no index removal is needed. Representative project instructions, specifications and native test documentation remain eligible for tracking. `git diff --check` passed. No application build or device test is required for this ignore-only change.
- Limitations: Markdown records and reports remain eligible for upload. Existing JSON and log ignore rules already excluded most raw files; the new rule also covers other current and future non-Markdown run artefacts.
