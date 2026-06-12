package dev.blokz.arxiver.core.claude

/**
 * A curated routine template (SPEC-ROUTINES-CATALOG §2). Templates are static
 * reference data bundled with the app — no DB rows, no template↔routine link.
 * The paste-ready instruction text is `instructionPreamble` + the shared
 * recognition core, composed by [RoutineStarterInstructions.generateFor].
 */
data class RoutineTemplate(
    /** Stable key; never reused across catalog versions. */
    val id: String,
    /** Display name; doubles as the prefilled routine name in the wizard. */
    val name: String,
    /** 1–2 sentences for the catalog UI: what you get, when to use it. */
    val purpose: String,
    /** The action Arxiver preselects when dispatching to this routine. */
    val action: RoutineAction,
    /** Template-specific routine behavior (role, output shape, delivery). */
    val instructionPreamble: String,
    /** Setup steps on the claude.ai side — always an API trigger. */
    val triggerGuidance: String,
    /**
     * Minimal connector set. Empty = results land in the routine's run log;
     * connectors run without permission prompts, so fewer is safer
     * (SPEC-ROUTINES-CATALOG §1).
     */
    val connectors: List<String> = emptyList(),
    /** Optional delivery upgrades the user may add deliberately. */
    val optionalConnectors: List<String> = emptyList(),
)

/** SPEC-ROUTINES-CATALOG §3 — canonical template content; golden-tested. */
object RoutineTemplateCatalog {
    /** Bumps when template content changes meaningfully (§5). */
    const val CATALOG_VERSION = 1

    private const val API_TRIGGER_GUIDANCE =
        "Create a new routine at claude.ai/code/routines, paste the copied instructions, " +
            "and choose the API trigger — Arxiver fires it with paper payloads."

    val templates: List<RoutineTemplate> =
        listOf(
            RoutineTemplate(
                id = "paper_digest",
                name = "Paper Digest",
                purpose =
                    "Structured digest of each paper you send: TL;DR, contributions, methods, " +
                        "limitations. The entry-level template.",
                action = RoutineAction.DIGEST,
                instructionPreamble =
                    "You are my paper-digest assistant. When a research dispatch arrives, produce a " +
                        "structured digest of EACH paper: TL;DR (2–3 sentences), key contributions, " +
                        "methods, limitations, and why it matters to me (use my tags and notes when " +
                        "present). Practitioner's density — no filler. Unless this routine has a " +
                        "delivery connector attached, write the digests directly in this run's output.",
                triggerGuidance = API_TRIGGER_GUIDANCE,
                optionalConnectors = listOf("Email", "Drive"),
            ),
            RoutineTemplate(
                id = "deep_dive_analyst",
                name = "Deep-Dive Analyst",
                purpose =
                    "Full-text technical analysis of a single paper, read from the PDF — evidence " +
                        "quality, reproducibility, open questions.",
                action = RoutineAction.DEEP_DIVE,
                instructionPreamble =
                    "You are my deep-dive analyst. For each dispatched paper, fetch the PDF from its " +
                        "pdf_url and read the full text — never work from the abstract alone. Produce a " +
                        "deep technical analysis: problem setup, the approach and its key technical " +
                        "ideas, evidence quality (experiments, baselines, ablations), reproducibility " +
                        "signals (code and data availability), limitations and open questions, and how " +
                        "it relates to any other papers or neighbors in the payload. Dispatches here " +
                        "usually carry one paper; give it full attention.",
                triggerGuidance = API_TRIGGER_GUIDANCE,
                optionalConnectors = listOf("Drive"),
            ),
            RoutineTemplate(
                id = "paper_comparator",
                name = "Paper Comparator",
                purpose =
                    "Head-to-head comparison of 2–6 papers, composing the similarity and citation " +
                        "signals your device precomputed.",
                action = RoutineAction.COMPARE,
                instructionPreamble =
                    "You are my paper comparator. Build a comparison of the dispatched papers: shared " +
                        "problem framing, differing approaches, head-to-head trade-offs (a table works " +
                        "well), and a verdict — which to build on and why. The payload's \"relations\" " +
                        "block carries embedding similarity and citation edges computed on my device: " +
                        "compose those signals (cluster near-duplicates, note who cites whom) instead " +
                        "of re-deriving them from the text.",
                triggerGuidance = API_TRIGGER_GUIDANCE,
            ),
            RoutineTemplate(
                id = "weekly_review",
                name = "Weekly Research Review",
                purpose =
                    "Sunday-morning synthesis of a week of saves and top inbox papers: themes, " +
                        "must-reads, what to queue next.",
                action = RoutineAction.WEEKLY_REVIEW,
                instructionPreamble =
                    "You are my weekly research reviewer. Each dispatch is a week of my research " +
                        "activity: library saves and top inbox papers. Synthesize the week: the 2–4 " +
                        "themes that emerged, the must-reads and why, what looks skippable, and what I " +
                        "should queue next week. Use my tags, ratings, and notes to read my interests; " +
                        "the \"relations\" block shows the clusters. Make it scannable — this is a " +
                        "Sunday-morning read.",
                triggerGuidance = API_TRIGGER_GUIDANCE,
                optionalConnectors = listOf("Email"),
            ),
            RoutineTemplate(
                id = "literature_scout",
                name = "Literature Scout",
                purpose =
                    "Investigates a research question: extends your seed papers with its own " +
                        "searching and returns an annotated reading list.",
                action = RoutineAction.LITERATURE_SCAN,
                instructionPreamble =
                    "You are my literature scout. Each dispatch carries a research question in MY " +
                        "INSTRUCTION plus optional seed papers as local context. Investigate the " +
                        "question: extend beyond the seeds with your own searching, then return an " +
                        "annotated reading list — for each recommendation give the citation, a " +
                        "one-paragraph relevance note, and how it relates to the seeds. Distinguish " +
                        "clearly between papers I sent and papers you found.",
                triggerGuidance = API_TRIGGER_GUIDANCE,
            ),
            RoutineTemplate(
                id = "repro_auditor",
                name = "Reproducibility Auditor",
                purpose =
                    "Can you actually run this paper? Hunts code and datasets, checks repo health, " +
                        "scores reproducibility.",
                action = RoutineAction.CUSTOM,
                instructionPreamble =
                    "You are my reproducibility auditor. For each dispatched paper: find its code and " +
                        "datasets (paper links, GitHub search), check repo health (recent commits, open " +
                        "issues, license, a plausible install path), verify that claimed artifacts " +
                        "actually exist, then score reproducibility high/medium/low with reasons. Be " +
                        "skeptical — \"code available\" claims often aren't. If this routine has GitHub " +
                        "access, inspect the repositories directly.",
                triggerGuidance = API_TRIGGER_GUIDANCE,
                connectors = listOf("GitHub (read)"),
            ),
            RoutineTemplate(
                id = "queue_prioritizer",
                name = "Reading-Queue Prioritizer",
                purpose =
                    "Turns a pile of papers into an ordered reading plan: value to you, prerequisite " +
                        "order, effort estimates.",
                action = RoutineAction.CUSTOM,
                instructionPreamble =
                    "You are my reading-queue prioritizer. Rank the dispatched papers in the order I " +
                        "should read them, with a short justification each: expected value to me (lean " +
                        "on my tags, notes, and ratings when present), prerequisite ordering (read X " +
                        "before Y), and an effort estimate (skim / read / study). The \"relations\" " +
                        "block reveals clusters and what sits near my existing library — use it. End " +
                        "with a concrete plan: today, this week, and what to drop.",
                triggerGuidance = API_TRIGGER_GUIDANCE,
            ),
            RoutineTemplate(
                id = "flashcard_generator",
                name = "Flashcard & Notes Generator",
                purpose =
                    "Retention material per paper: spaced-repetition flashcards plus structured notes " +
                        "written for future-you.",
                action = RoutineAction.CUSTOM,
                instructionPreamble =
                    "You are my retention assistant. Distill each dispatched paper into study " +
                        "material: first 5–10 spaced-repetition flashcards (question on one line, " +
                        "answer on the next) covering the core idea, method, key numbers, and " +
                        "limitations; then half a page of structured reading notes I can revisit. " +
                        "Write for future-me who has forgotten the paper — fully self-contained, no " +
                        "\"as mentioned above\".",
                triggerGuidance = API_TRIGGER_GUIDANCE,
                optionalConnectors = listOf("Drive"),
            ),
        )

    fun byId(id: String): RoutineTemplate? = templates.firstOrNull { it.id == id }
}
