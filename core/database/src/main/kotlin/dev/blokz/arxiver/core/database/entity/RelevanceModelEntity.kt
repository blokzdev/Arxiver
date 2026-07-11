package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The SINGLE-ROW per-user relevance-model store (P5.3): the fitted Platt calibration now, the gated learned
 * head's weights later (nullable blob — designed in NOW so P5.4 needs no second migration). The single-row
 * invariant lives in the DAO (`id = 0` + REPLACE upsert), not a SQL CHECK — Room can neither express nor
 * validate one. **Never seeded**: an absent row ≡ below-the-calibration-floor ≡ the legacy 0.55 constant, so a
 * fresh install and a migrated install behave identically by construction.
 *
 * LOCAL-ONLY, like `paper_feedback`: never exported, backed up, or serialized — the backup wall is pinned by a
 * serializer-descriptor test, and `allowBackup=false` backstops OS-level backup.
 */
@Entity(tableName = "relevance_model")
data class RelevanceModelEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 0,
    /** The embedding model the calibration/weights were fit against — stale-tag rows are discarded, not reused. */
    @ColumnInfo(name = "embedding_model") val embeddingModel: String,
    /** Platt slope; null = no calibration fitted (below floor). Monotone by the fitter's contract (a > 0). */
    @ColumnInfo(name = "calibration_a") val calibrationA: Double?,
    @ColumnInfo(name = "calibration_b") val calibrationB: Double?,
    /**
     * The latest eval pass's harness-selected shrinkage λ. On a P5.5 KEEP row this is the FRESH pass's λ while
     * `calibration_a`/`_b` are the retained (previous-pass) fit — the row means "the calibration in force" plus
     * "the latest eval", deliberately mixed; see [consecutiveNullFits].
     */
    @ColumnInfo(name = "shrinkage_lambda") val shrinkageLambda: Double,
    /** Raw label counts behind the fit — the floor audit trail. */
    @ColumnInfo(name = "label_positives") val labelPositives: Int,
    @ColumnInfo(name = "label_negatives") val labelNegatives: Int,
    @ColumnInfo(name = "fitted_at") val fittedAt: Long,
    /** P5.4's future weight vector (little-endian float blob) + bias — nullable until the head is earned. */
    @ColumnInfo(name = "head_weights", typeAffinity = ColumnInfo.BLOB) val headWeights: ByteArray? = null,
    @ColumnInfo(name = "head_bias") val headBias: Double? = null,
    /**
     * Downgrade hysteresis (P5.5): how many consecutive eval passes have failed to fit a calibration while
     * the row still carries one. A single null fit KEEPS the previous a/b (streak → 1); a second consecutive
     * null downgrades to the legacy 0.55 (a/b → null, streak → 0). A fresh fit always applies immediately and
     * resets the streak. On a kept pass [fittedAt] retains the a/b vintage while λ/label counts are the fresh
     * pass's — the row describes "the calibration in force" plus "the latest eval", deliberately.
     */
    @ColumnInfo(name = "consecutive_null_fits", defaultValue = "0") val consecutiveNullFits: Int = 0,
) {
    override fun equals(other: Any?): Boolean = other is RelevanceModelEntity && other.id == id

    override fun hashCode(): Int = id
}
