# Arxiver

**A local-first, multi-source preprint research engine for Android — with a bring-your-own intelligence layer that runs on-device, on your own API key, or on your Claude.**

Arxiver turns your phone into a personal research engine. Browse and search arXiv and the wider preprint ecosystem — bioRxiv, medRxiv, chemRxiv, SSRN, Research Square, Preprints.org, PsyArXiv — then pull what matters into your orbit: a deeply indexed local library with full-text keyword search, on-device semantic search, full-text body search over the papers you read, a citation/author graph, collections, tags, and notes — all in a single SQLite database on your device. No account. No server. No telemetry.

When you want to *reason* over papers, Arxiver gives you three options that never leave your control: run a small model **fully on-device**, bring your own **Anthropic or Gemini API key**, or dispatch to a **Claude Routine** you configure. These power in-app chat, per-paper "Ask", corpus-searching tool-use, and RAG over your library. Your device, your key, your cost control.

## Core ideas

- **Local-first.** Your library, notes, embeddings, and graph live in one SQLite file on your device. Browse works online; everything in your library works on a plane.
- **Multi-source, arXiv-centered.** arXiv is the center of gravity (the only source with full field-prefix search, versioning, and in-app HTML-edition reading); bioRxiv/medRxiv are native; chemRxiv/SSRN/Research Square/Preprints.org/PsyArXiv arrive via OpenAlex, and Semantic Scholar backs cross-source search and related-papers. Discovery exposes *source* as a dimension of the query; identity, de-dup, and your Today feed are source-blind.
- **Index your orbit, stream the rest.** You don't need every preprint. Arxiver deeply indexes what you follow (categories, authors, saved queries, across sources) and what you save, and uses the live source APIs for the long tail.
- **One storage engine.** SQLite plays four roles: relational (Room), keyword search (FTS4), vector search (a custom chunked cosine scan over an embedding BLOB table — `sqlite-vec` is a tracked v2 optimization, not shipped), and graph (edge tables + recursive CTEs).
- **On-device semantics.** A small embedding model (`bge-small-en-v1.5`, ~34MB, ONNX Runtime) powers semantic search and related-papers — offline, free, private.
- **Three ways to add intelligence.** (1) **On-device** models (Qwen3-0.6B light tier + Gemma via LiteRT/ONNX) — inference runs locally, nothing leaves the device. (2) **Bring your own key** (Anthropic / Google Gemini) — in-app chat, Ask, and RAG on your own account. (3) **Claude Routines** — POST rich payloads to a routine trigger you configure, where it runs with your full set of connectors, skills, and memory. Pick per use; the app never calls an LLM you didn't set up.

## Documentation

| Doc | What's in it |
|---|---|
| [docs/PRD.md](docs/PRD.md) | Product requirements, personas, features, non-goals |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System design, module map, data flow |
| [docs/SPEC-DATA.md](docs/SPEC-DATA.md) | Database schema, source mapping |
| [docs/SPEC-SEARCH.md](docs/SPEC-SEARCH.md) | Hybrid keyword + semantic + full-text search engine |
| [docs/SPEC-P-SOURCES.md](docs/SPEC-P-SOURCES.md) | Multi-source preprint model (identity, import, de-dup) |
| [docs/SPEC-P-FEEDS.md](docs/SPEC-P-FEEDS.md) | Cross-source feeds & follows |
| [docs/SPEC-P-HTML.md](docs/SPEC-P-HTML.md) | HTML-edition reader (arxiv.org/html → ar5iv → PDF) |
| [docs/SPEC-AI-PROVIDERS.md](docs/SPEC-AI-PROVIDERS.md) | On-device + BYOK AI provider platform |
| [docs/SPEC-P-TOOLS.md](docs/SPEC-P-TOOLS.md) | Agentic in-chat corpus tool-use |
| [docs/SPEC-CLAUDE-BRIDGE.md](docs/SPEC-CLAUDE-BRIDGE.md) | Claude Routines dispatch contract |
| [docs/SPEC-UI.md](docs/SPEC-UI.md) | Screens, navigation, design language |
| [docs/P2-PLAN.md](docs/P2-PLAN.md) | Chat-with-paper & knowledge-base (RAG) plan |
| [ROADMAP.md](ROADMAP.md) | Phased build plan & progress tracker |
| [CLAUDE.md](CLAUDE.md) | Agent workflow & engineering conventions |

## Building

Requirements: JDK 17+, Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug        # debug APK
./gradlew test lint            # unit tests + lint
./gradlew assembleRelease      # release APK (requires signing config)
```

CI builds, tests, and lints on every push (see `.github/workflows/`).

## Distribution

Ships as signed APKs via GitHub Releases (sideload). Play Store comes later.

**Installing:** grab the APK from the latest [Release](../../releases), allow installs from your browser/files app, open it. On first launch you pick categories to follow; the semantic-search model (~34MB) downloads over Wi-Fi in the background. On-device chat models (Qwen3/Gemma) are opt-in downloads from Settings.

**Releasing (maintainers):** push a `v*` tag. The release workflow signs with repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (see `.github/workflows/release.yml` for keystore generation) and attaches the APK to a GitHub Release.

## Adding intelligence

Arxiver keeps LLM choice in your hands — pick any or all:

- **On-device** — Settings → download a chat model (Qwen3-0.6B light tier, or Gemma). Ask, chat, and RAG then run offline, on your phone. Nothing leaves the device.
- **Bring your own key** — Settings → *AI providers* → paste an Anthropic or Google Gemini API key (stored encrypted, never exported). In-app chat, per-paper "Ask", and library RAG use your account directly.
- **Claude Routines** — in the Claude app, create a **Routine** with an **API trigger** and the connectors you want. In Arxiver: Library → ⋮ → *Claude routines…* → add the trigger URL + token (use the copy icon for paste-ready instructions that teach Claude the `arxiver/v1` payload schema). Then dispatch any paper (detail ✨), selection (long-press in Library), or your weekly review (✨ on Today) — your routine does the thinking with your connectors.

## Status

Under active development (2.x). See [ROADMAP.md](ROADMAP.md) for live progress.

## Acknowledgements

Conceptual inspiration from [arxiv-sanity-preserver](https://github.com/karpathy/arxiv-sanity-preserver), [arxiv-mcp-server](https://github.com/blazickjp/arxiv-mcp-server), and [ArxivExplorer](https://github.com/Teycir/ArxivExplorer). Thank you to arXiv and the preprint servers Arxiver reaches for their open-access interoperability.
