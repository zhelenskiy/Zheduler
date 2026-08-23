@file:OptIn(ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.components.map

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import kotlin.test.Test

/**
 * A fence smaller than a phone can resolve is allowed, and said to be.
 *
 * It is worth allowing: beside a wifi or bluetooth condition — which are exact — a metre is a
 * reasonable thing to ask for. On its own it is not, and the way it fails is the worst kind:
 * silently and intermittently. The rule does not refuse to work, it works now and then, late,
 * which reads as a bug in the app rather than the limit of the hardware it is.
 */
class RadiusWarningTest {

    @Test
    fun aFenceTooSmallToResolveSaysSo() = runComposeUiTest {
        setContent { RadiusSlider(radiusMeters = 3.0, onRadiusChange = {}) }
        waitForIdle()

        onNodeWithText("Smaller than a phone can reliably tell", substring = true).assertIsDisplayed()
    }

    @Test
    fun theWarningNamesTheWayItFails() = runComposeUiTest {
        // "Will not work" would be wrong — it does work, sometimes — and a user who saw that and
        // then watched it fire once would trust it. What has to be said is the *shape* of the
        // failure, which is what makes it recognisable when it happens.
        setContent { RadiusSlider(radiusMeters = 1.0, onRadiusChange = {}) }
        waitForIdle()

        onNodeWithText("fire late", substring = true).assertIsDisplayed()
        onNodeWithText("Wi-Fi or Bluetooth", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFenceBigEnoughIsNotNagged() = runComposeUiTest {
        setContent {
            RadiusSlider(radiusMeters = GeoArea.RELIABLE_RADIUS_METERS, onRadiusChange = {})
        }
        waitForIdle()

        onNodeWithText("Smaller than a phone can reliably tell", substring = true).assertDoesNotExist()
    }

    @Test
    fun theOrdinaryFenceIsNotNagged() = runComposeUiTest {
        setContent { RadiusSlider(radiusMeters = 200.0, onRadiusChange = {}) }
        waitForIdle()

        onNodeWithText("Smaller than a phone can reliably tell", substring = true).assertDoesNotExist()
        onNodeWithText("Within", substring = true).assertIsDisplayed()
    }
}
