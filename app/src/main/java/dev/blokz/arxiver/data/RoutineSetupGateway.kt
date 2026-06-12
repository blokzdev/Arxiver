package dev.blokz.arxiver.data

/**
 * The slice of [DispatchRepository] the guided setup wizard needs
 * (SPEC-CLAUDE-BRIDGE §8) — an interface so the wizard ViewModel is
 * unit-testable without the repository's DAO graph.
 */
interface RoutineSetupGateway {
    suspend fun addRoutine(
        name: String,
        triggerUrl: String,
        token: String,
    ): Long

    suspend fun updateRoutine(
        routineId: Long,
        name: String,
        triggerUrl: String,
    )

    suspend fun replaceToken(
        routineId: Long,
        token: String,
    )

    suspend fun ping(routineId: Long): DispatchSubmission
}
