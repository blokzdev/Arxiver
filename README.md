# Arxiver

**A local-first arXiv directory & research explorer for Android — with a Claude-powered intelligence layer you bring yourself.**

Arxiver turns your phone into a personal research engine. Browse and search all of arXiv live, then pull what matters into your orbit: a deeply indexed local library with full-text keyword search, on-device semantic search, a citation/author graph, collections, tags, and notes — all stored in a single SQLite database on your device. No account. No server. No telemetry.

When you want intelligence on top, Arxiver connects to the **Claude app's Routines** feature: paste in a routine's API-trigger URL and token, and dispatch any paper, collection, or research question to Claude — where it runs with your full set of connectors, skills, and memory. Arxiver does the indexing; your Claude does the thinking.

## Core ideas

- **Local-first.** Your library, notes, embeddings, and graph live in one SQLite file on your device. Browse works online; everything in your library works on a plane.
- **Index your orbit, stream the rest.** arXiv has 2.4M+ papers — you don't need them all. Arxiver deeply indexes what you follow (categories, authors, saved queries) and what you save, and uses the live arXiv API for the long tail.
- **One storage engine.** SQLite plays four roles: relational (Room), keyword search (FTS5), vector search (sqlite-vec), and graph (edge tables + recursive CTEs).
- **On-device semantics.** A small embedding model (~25MB, ONNX Runtime) powers semantic search and related-papers — offline, free, private.
- **Bring-your-own-Claude.** Zero LLM calls inside the app. The Claude bridge POSTs rich payloads to routine triggers you configure. Your token, your connectors, your cost control.

## Documentation

| Doc | What's in it |
|---|---|
| [docs/PRD.md](docs/PRD.md) | Product requirements, personas, features, non-goals |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System design, module map, data flow |
| [docs/SPEC-DATA.md](docs/SPEC-DATA.md) | Database schema, arXiv API mapping |
| [docs/SPEC-SEARCH.md](docs/SPEC-SEARCH.md) | Hybrid keyword+semantic search engine |
| [docs/SPEC-CLAUDE-BRIDGE.md](docs/SPEC-CLAUDE-BRIDGE.md) | Claude Routines integration contract |
| [docs/SPEC-UI.md](docs/SPEC-UI.md) | Screens, navigation, design language |
| [docs/SPEC-AI-PROVIDERS.md](docs/SPEC-AI-PROVIDERS.md) | Multi-provider + on-device AI platform (v2) |
| [docs/P2-PLAN.md](docs/P2-PLAN.md) | Chat-with-paper & knowledge-base (RAG) plan (v2) |
| [ROADMAP.md](ROADMAP.md) | Phased build plan & progress tracker |
| [CLAUDE.md](CLAUDE.md) | Agent workflow & engineering conventions |

## Building

Requirements: JDK 17+, Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug        # debug APK
./gradlew test lint            # unit tests + lint
./gradlew assembleRelease      # release APK (requires signing config)
```

CI builds, tests, and publishes APK artifacts on every push (see `.github/workflows/`).

## Distribution

v1 ships as signed APKs via GitHub Releases (sideload). Play Store comes later.

**Installing:** grab the APK from the latest [Release](../../releases), allow installs from your browser/files app, open it. On first launch you pick categories to follow; the semantic-search model (~34MB) downloads over Wi-Fi in the background.

**Releasing (maintainers):** push a `v*` tag. The release workflow signs with repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (see `.github/workflows/release.yml` for keystore generation) and attaches the APK to a GitHub Release.

## Connecting your Claude

1. In the Claude app, create a **Routine** with an **API trigger** and the connectors you want it to use.
2. In Arxiver: Library → ⋮ → *Claude routines…* → add the routine's trigger URL + token. Use the copy icon for paste-ready routine instructions that teach Claude the payload schema (`arxiver/v1`).
3. Send any paper (detail screen ✨), selection (long-press in Library), or your weekly review (✨ on Today) — your routine does the thinking with your connectors, and delivers results wherever you configured it to.

## Status

Pre-1.0, under active development. See [ROADMAP.md](ROADMAP.md) for live progress.

## Acknowledgements

Conceptual inspiration from [arxiv-sanity-preserver](https://github.com/karpathy/arxiv-sanity-preserver), [arxiv-mcp-server](https://github.com/blazickjp/arxiv-mcp-server), and [ArxivExplorer](https://github.com/Teycir/ArxivExplorer). Thank you to arXiv for use of its open access interoperability.
