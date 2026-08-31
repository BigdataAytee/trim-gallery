package app.trimgallery.core.pipeline.night

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightBudgetTest {

    private val minute = 60_000L

    @Test
    fun `the cap is on work, not on elapsed time`() {
        // A pass plugged in for five hours but stood down for four of them has used one
        // hour of its budget. Counting wall-clock time would let a hot night quietly
        // consume a cool one's allowance.
        val budget = NightBudget(capMinutes = 60)
        budget.resume(0)
        budget.pause(30 * minute)
        // Four hours pass with nothing happening.
        budget.resume(270 * minute)
        assertEquals(30 * minute, budget.workedMs(270 * minute))
        assertEquals(30 * minute, budget.remainingMs(270 * minute))
    }

    @Test
    fun `time still running counts towards the budget`() {
        val budget = NightBudget(capMinutes = 60)
        budget.resume(0)
        assertEquals(10 * minute, budget.workedMs(10 * minute))
        assertFalse(budget.exhausted(10 * minute))
        assertTrue(budget.exhausted(60 * minute))
    }

    @Test
    fun `resuming twice does not double count`() {
        val budget = NightBudget(capMinutes = 60)
        budget.resume(0)
        budget.resume(5 * minute)
        assertEquals(10 * minute, budget.workedMs(10 * minute))
    }

    @Test
    fun `pausing when already paused is a no-op`() {
        val budget = NightBudget(capMinutes = 60)
        budget.resume(0)
        budget.pause(10 * minute)
        budget.pause(50 * minute)
        assertEquals(10 * minute, budget.workedMs(50 * minute))
    }

    @Test
    fun `remaining never goes negative`() {
        val budget = NightBudget(capMinutes = 1)
        budget.resume(0)
        budget.pause(10 * minute)
        assertEquals(0, budget.remainingMs(10 * minute))
        assertTrue(budget.exhausted(10 * minute))
    }

    @Test
    fun `minutes worked is what the morning card reports`() {
        val budget = NightBudget(capMinutes = 60)
        budget.resume(0)
        budget.pause(90_000L)
        assertEquals(1.5, budget.minutesWorked(90_000L))
    }

    @Test
    fun `a clock that jumps backwards does not subtract from the budget`() {
        // Phones move their clocks. A negative stretch would hand back time that was
        // actually spent.
        val budget = NightBudget(capMinutes = 60)
        budget.resume(10 * minute)
        budget.pause(5 * minute)
        assertEquals(0, budget.workedMs(5 * minute))
    }

    @Test
    fun `a cap of zero minutes is refused`() {
        assertFailsWith<IllegalArgumentException> { NightBudget(capMinutes = 0) }
    }
}
