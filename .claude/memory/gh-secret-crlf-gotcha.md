---
name: gh-secret-crlf-gotcha
description: Setting gh secrets from Windows — piping appends a \r that breaks base64 -d on Linux runners
type: gotcha
---

Piping a value into `gh secret set` from PowerShell appends CRLF and the stored secret keeps the stray `\r`; GNU `base64 -d` on the runner then fails with `base64: invalid input` (it tolerates `\n`, not `\r`). This broke the first v1.0.0 release run at the keystore-decode step.

**How to apply:** set secrets byte-exact via stdin redirection of a file written with no trailing newline: `cmd /c "gh secret set NAME --repo owner/repo < file"` (write the file with `[IO.File]::WriteAllText(...)`, UTF-8 without BOM). Use `--body` only for non-sensitive values.

Related: [[release-engineering-state]]
