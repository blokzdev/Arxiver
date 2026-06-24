package dev.blokz.arxiver.feature.paper.ask

import androidx.annotation.StringRes
import dev.blokz.arxiver.R

/** Which chat scopes a preset is offered in (a paper sheet, a collection sheet, or both). */
enum class PresetScope { PAPER, COLLECTION, BOTH }

/**
 * A one-tap research action (P-Rich R3c). A preset is just a **predefined question**: tapping it
 * runs [instruction] through the same grounded `ask()` path as a typed question, so the answer
 * stays cited and privacy-gated and the request is byte-identical to typing the same text — no
 * system-prompt change, redaction goldens untouched. [labelRes] is user-facing (localized);
 * [instruction] is model-facing English, matching the `AskViewModel.SUMMARIZE_PROMPT` precedent.
 */
data class AskPreset(
    val id: String,
    @StringRes val labelRes: Int,
    val instruction: String,
    val appliesTo: PresetScope,
)

/**
 * The curated preset library — the "what you can ask" dimension. The existing Summarize action is
 * member #1 (reusing its exact prompt). Instructions cooperate with `ChatContextAssembler`'s
 * grounding rule ("answer only from the excerpts, cite [n], say so if missing") rather than
 * restating it. PAPER-only presets assume a single target paper (a citation key, a repro target);
 * the rest read sensibly across a multi-paper collection too.
 */
object AskPresets {
    val ALL: List<AskPreset> =
        listOf(
            AskPreset(
                id = "summarize",
                labelRes = R.string.preset_summarize,
                instruction = AskViewModel.SUMMARIZE_PROMPT,
                appliesTo = PresetScope.BOTH,
            ),
            AskPreset(
                id = "key_contributions",
                labelRes = R.string.preset_key_contributions,
                instruction =
                    "List the main contributions or claims as a short bulleted list — one line each — " +
                        "and cite the excerpt each one comes from.",
                appliesTo = PresetScope.BOTH,
            ),
            AskPreset(
                id = "explain_method",
                labelRes = R.string.preset_explain_method,
                instruction =
                    "Explain the core method or approach step by step: what it does, how it works, and " +
                        "why it is designed that way, using only what the excerpts describe.",
                appliesTo = PresetScope.PAPER,
            ),
            AskPreset(
                id = "limitations",
                labelRes = R.string.preset_limitations,
                instruction =
                    "Identify the limitations, assumptions, and weaknesses — both those the authors " +
                        "acknowledge and those evident from the excerpts. Do not invent critiques the " +
                        "context does not support.",
                appliesTo = PresetScope.PAPER,
            ),
            AskPreset(
                id = "compare_related",
                labelRes = R.string.preset_compare_related,
                instruction =
                    "Compare this work to the related or prior work mentioned in the excerpts: what it " +
                        "does differently and what it improves on. If the context names no related work, say so.",
                appliesTo = PresetScope.BOTH,
            ),
            AskPreset(
                id = "eli5",
                labelRes = R.string.preset_eli5,
                instruction =
                    "Explain the central idea in plain language for a non-specialist: avoid jargon and " +
                        "define any unavoidable technical term, while staying faithful to the excerpts.",
                appliesTo = PresetScope.BOTH,
            ),
            AskPreset(
                id = "glossary",
                labelRes = R.string.preset_glossary,
                instruction =
                    "Build a glossary of the key technical terms, notation, or acronyms as a Markdown " +
                        "table of term and a one-line plain definition, using only definitions supported " +
                        "by the excerpts.",
                appliesTo = PresetScope.BOTH,
            ),
            AskPreset(
                id = "bibtex",
                labelRes = R.string.preset_bibtex,
                instruction =
                    "Produce a BibTeX entry for this paper from the title, authors, year, and arXiv " +
                        "identifier present in the context. Output only a fenced bibtex code block; if a " +
                        "required field is missing, leave it blank rather than guessing.",
                appliesTo = PresetScope.PAPER,
            ),
            AskPreset(
                id = "reproducibility",
                labelRes = R.string.preset_reproducibility,
                instruction =
                    "Assess reproducibility as a checklist: is there code, data, hyperparameters, " +
                        "compute, and enough method detail to re-implement? Mark each as present, partial, " +
                        "or not mentioned, based only on the excerpts.",
                appliesTo = PresetScope.PAPER,
            ),
        )

    /** The presets offered for a paper sheet ([isPaper] true) vs a collection sheet. */
    fun forScope(isPaper: Boolean): List<AskPreset> =
        ALL.filter {
            when (it.appliesTo) {
                PresetScope.BOTH -> true
                PresetScope.PAPER -> isPaper
                PresetScope.COLLECTION -> !isPaper
            }
        }
}
