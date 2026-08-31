package app.trimgallery.core.domain.platform

import kotlin.test.Test
import kotlin.test.assertFalse

class FeatureFlagsTest {

    /**
     * This test exists to be deleted, deliberately.
     *
     * Flipping [FeatureFlags.IOS_REPLACE_ENABLED] to true fails the build here, which forces
     * whoever does it to open this file and read why it was off. The gate is the hardware
     * procedure in PROJECT.md's device-required list — PhotoKit change-block atomicity — and
     * a constant is far too easy to flip in passing for something whose failure mode is
     * deleting the only copy of a user's photograph.
     *
     * When the procedure has been run and recorded, this test goes with it.
     */
    @Test
    fun `iOS replace stays off until PhotoKit atomicity is confirmed on hardware`() {
        assertFalse(
            FeatureFlags.IOS_REPLACE_ENABLED,
            "Run the PhotoKit change-block atomicity procedure in PROJECT.md and record the " +
                "result before enabling this. The sequence's rollback is tested; PhotoKit's is not.",
        )
    }
}
