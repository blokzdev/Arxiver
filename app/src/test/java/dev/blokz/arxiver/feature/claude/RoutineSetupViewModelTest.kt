package dev.blokz.arxiver.feature.claude

import androidx.lifecycle.SavedStateHandle
import dev.blokz.arxiver.data.DispatchSubmission
import dev.blokz.arxiver.data.RoutineSetupGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineSetupViewModelTest {
    private class FakeGateway : RoutineSetupGateway {
        val calls = mutableListOf<String>()
        var pingResult: DispatchSubmission = DispatchSubmission.Sent(dispatchId = 1)

        override suspend fun addRoutine(
            name: String,
            triggerUrl: String,
            token: String,
        ): Long {
            calls += "add:$name:$triggerUrl"
            return 7
        }

        override suspend fun updateRoutine(
            routineId: Long,
            name: String,
            triggerUrl: String,
        ) {
            calls += "update:$routineId:$name:$triggerUrl"
        }

        override suspend fun replaceToken(
            routineId: Long,
            token: String,
        ) {
            calls += "replaceToken:$routineId"
        }

        override suspend fun ping(routineId: Long): DispatchSubmission {
            calls += "ping:$routineId"
            return pingResult
        }
    }

    private val gateway = FakeGateway()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(templateId: String? = null) =
        RoutineSetupViewModel(
            gateway = gateway,
            savedStateHandle = SavedStateHandle(templateId?.let { mapOf("templateId" to it) } ?: emptyMap()),
        )

    private fun RoutineSetupViewModel.fillValidConnect() {
        onNameChange("My Routine")
        onUrlChange("https://api.anthropic.com/v1/claude_code/routines/abc123")
        onTokenChange("a-token")
    }

    @Test
    fun `template id prefills template and routine name`() {
        val vm = viewModel(templateId = "paper_digest")
        assertEquals("paper_digest", vm.uiState.value.template?.id)
        assertEquals("Paper Digest", vm.uiState.value.name)
    }

    @Test
    fun `no template id means generic setup`() {
        val vm = viewModel()
        assertNull(vm.uiState.value.template)
        assertEquals("", vm.uiState.value.name)
    }

    @Test
    fun `save is gated on valid connect input`() {
        val vm = viewModel()
        vm.continueToConnect()
        vm.onNameChange("Name")
        vm.onUrlChange("http://not-https")
        vm.onTokenChange("tok")
        vm.saveAndContinue()
        assertTrue(gateway.calls.isEmpty())
        assertEquals(SetupStep.CONNECT, vm.uiState.value.step)
    }

    @Test
    fun `save persists the routine before verify step and before any ping`() {
        val vm = viewModel(templateId = "paper_digest")
        vm.continueToConnect()
        vm.fillValidConnect()
        vm.saveAndContinue()

        assertEquals(SetupStep.VERIFY, vm.uiState.value.step)
        assertEquals(7, vm.uiState.value.savedRoutineId)
        assertEquals(listOf("add:My Routine:https://api.anthropic.com/v1/claude_code/routines/abc123"), gateway.calls)
        assertEquals(VerificationState.Idle, vm.uiState.value.verification)
    }

    @Test
    fun `re-saving after back-edit updates in place instead of duplicating`() {
        val vm = viewModel()
        vm.continueToConnect()
        vm.fillValidConnect()
        vm.saveAndContinue()
        vm.backStep()
        vm.onUrlChange("https://api.anthropic.com/v1/claude_code/routines/other")
        vm.saveAndContinue()

        assertEquals(
            listOf(
                "add:My Routine:https://api.anthropic.com/v1/claude_code/routines/abc123",
                "update:7:My Routine:https://api.anthropic.com/v1/claude_code/routines/other",
                "replaceToken:7",
            ),
            gateway.calls,
        )
        assertEquals(7, vm.uiState.value.savedRoutineId)
    }

    @Test
    fun `verify maps sent to verified`() {
        val vm = savedViewModel()
        gateway.pingResult = DispatchSubmission.Sent(dispatchId = 9)
        vm.verify()
        assertEquals(VerificationState.Verified, vm.uiState.value.verification)
        assertTrue("ping:7" in gateway.calls)
    }

    @Test
    fun `verify maps offline queue to skipped-offline but 5xx queue to server error`() {
        val vm = savedViewModel()
        gateway.pingResult = DispatchSubmission.Queued(dispatchId = 9, httpCode = null)
        vm.verify()
        assertEquals(VerificationState.SkippedOffline, vm.uiState.value.verification)

        gateway.pingResult = DispatchSubmission.Queued(dispatchId = 9, httpCode = 503)
        vm.verify()
        assertEquals(
            VerificationState.Failed(VerificationError.ServerError(503)),
            vm.uiState.value.verification,
        )
    }

    @Test
    fun `verify maps each failure shape to its taxonomy class`() {
        val vm = savedViewModel()
        gateway.pingResult = DispatchSubmission.AuthRejected(dispatchId = 9)
        vm.verify()
        assertEquals(VerificationState.Failed(VerificationError.BadToken), vm.uiState.value.verification)

        gateway.pingResult = DispatchSubmission.Failed(dispatchId = 9, reason = "HTTP 404", httpCode = 404)
        vm.verify()
        assertEquals(VerificationState.Failed(VerificationError.WrongUrl), vm.uiState.value.verification)

        gateway.pingResult = DispatchSubmission.Failed(dispatchId = 9, reason = "HTTP 400", httpCode = 400)
        vm.verify()
        assertEquals(VerificationState.Failed(VerificationError.BadRequest), vm.uiState.value.verification)
    }

    @Test
    fun `verify without a saved routine is a no-op`() {
        val vm = viewModel()
        vm.verify()
        assertTrue(gateway.calls.isEmpty())
        assertEquals(VerificationState.Idle, vm.uiState.value.verification)
    }

    private fun savedViewModel(): RoutineSetupViewModel {
        val vm = viewModel()
        vm.continueToConnect()
        vm.fillValidConnect()
        vm.saveAndContinue()
        gateway.calls.clear()
        return vm
    }
}
