# P-HTML test corpus — provenance

Adversarial + benign fixtures for the P-HTML `HtmlSanitizer` (PH.1) and `HtmlReaderTransform` /
fidelity detection (PH.3). See `docs/SPEC-P-HTML.md` §5 (security model) and §4 (fidelity).

| File | Source | Captured | Role |
|---|---|---|---|
| `native-clean-2412.19437.html` | `https://arxiv.org/html/2412.19437v2` (native arXiv HTML edition) | 2026-06-27 | Clean native LaTeXML: resolvable `bib.bibN`, inline MathML, **absolute** in-text cite hrefs (`https://arxiv.org/html/…#bib.bibN`), jsdelivr CDN ref → benign-survives + native-cite-rewrite + CDN-strip goldens. |
| `native-degraded-2510.04905.html` | `https://arxiv.org/html/2510.04905v3` (native) | 2026-06-27 | **Degraded** conversion: ~419 `ltx_missing_citation`, zero resolvable `bib.bibN`, but real native MathML (`intent=`), figures, table, the Typekit font `<link>`, 2 inline `onclick=` → fidelity-marker detection + Typekit/onclick-strip goldens. |
| `ar5iv-clean-1706.03762.html` | `https://ar5iv.labs.arxiv.org/html/1706.03762` (ar5iv) | 2026-06-27 | Clean ar5iv LaTeXML: resolvable `bib.bibN`, **bare-fragment** in-text cites (`#bib.bibN`), MathML, ar5iv stylesheets → benign-survives (ar5iv markup family) + bare-cite-rewrite goldens. |
| `malicious-battery.html` | hand-built | 2026-06-27 | Hostile document exercising every §5 vector (script/on*/js:&data:text-html/external refs/iframe/object/embed/base/meta-refresh/foreignObject/MathML maction+annotation-xml+href/dangerous CSS/mXSS) alongside benign structure that must survive. |

## Notes

- The three `arxiv.org`/`ar5iv` files are **trimmed structural excerpts** (head + masthead + a
  math-bearing body slice + the bibliography), captured for **interoperability and security
  testing** only — not full-text redistribution. The full documents are ~350–960 KB; these are
  ~30–60 KB. Each remains under its paper's own arXiv license; arXiv is attributed via the source
  URLs above. Trimming is byte-window slicing (see the capture script in the PH.0 commit message),
  so internal markup is genuine LaTeXML 0.8.8 output, not synthesised.
- Both native and ar5iv are emitted by **LaTeXML 0.8.8**; one jsoup allowlist + one transform
  target both. Native adds MathML `intent=` accessibility hints; ar5iv references an external
  jsdelivr MathJax fallback. Both CDN reference styles (Typekit `<link>`, jsdelivr `<script>`)
  appear across the corpus and must be stripped.
- If real-paper excerpts are ever undesirable, they can be swapped for synthetic LaTeXML fixtures
  without changing the sanitizer/transform contracts — the goldens assert *structure*, not content.
