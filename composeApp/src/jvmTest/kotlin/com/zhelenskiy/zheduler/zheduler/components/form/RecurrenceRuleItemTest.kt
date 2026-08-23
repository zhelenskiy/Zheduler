@file:OptIn(ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import com.zhelenskiy.zheduler.zheduler.geo.GeoPoint
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.SignalDirection
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test

/**
 * What a rule says it is waiting for, where the task's own screens show it.
 *
 * A rule that waits for a place or a network showed nothing here but the status it resets to,
 * which reads as a rule with no trigger at all — and is exactly the rule whose trigger is hardest
 * to guess from anywhere else.
 */
class RecurrenceRuleItemTest {

    private fun rule(
        location: RecurrenceTrigger.LocationChange? = null,
        wifi: RecurrenceTrigger.NearbyChange? = null,
        bluetooth: RecurrenceTrigger.NearbyChange? = null,
    ) = RecurrenceRule(
        timeRecurrenceTrigger = null,
        statusChangeTrigger = null,
        resetToStatus = TaskStatus.Open,
        locationTrigger = location,
        wifiTrigger = wifi,
        bluetoothTrigger = bluetooth,
    )

    private val office = RecurrenceTrigger.LocationChange(
        areas = persistentSetOf(GeoArea("the office", GeoPoint(51.5, -0.12), 200.0)),
    )

    private val officeWifi = RecurrenceTrigger.NearbyChange(
        signals = persistentSetOf(NearbySignal.Wifi("acme-corp-5G")),
        direction = SignalDirection.Appearing,
    )

    private val car = RecurrenceTrigger.NearbyChange(
        signals = persistentSetOf(NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Car audio")),
        direction = SignalDirection.Disappearing,
    )

    @Test
    fun aRuleThatWaitsForAPlaceSaysSo() = runComposeUiTest {
        setContent { RecurrenceRuleItem(rule = rule(location = office), index = 0, onEdit = null, onDelete = null, onTaskClick = null) }
        waitForIdle()

        onNodeWithText("the office", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRuleThatWaitsForANetworkSaysWhichOne() = runComposeUiTest {
        setContent { RecurrenceRuleItem(rule = rule(wifi = officeWifi), index = 0, onEdit = null, onDelete = null, onTaskClick = null) }
        waitForIdle()

        onNodeWithText("acme-corp-5G", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRuleWatchingAPlaceAndTwoRadiosSaysAllThree() = runComposeUiTest {
        // All of them have to hold at once, so leaving any out of the summary describes a rule
        // that fires more readily than the real one does.
        setContent {
            RecurrenceRuleItem(rule = rule(location = office, wifi = officeWifi, bluetooth = car), index = 0, onEdit = null, onDelete = null, onTaskClick = null)
        }
        waitForIdle()

        onNodeWithText("the office", substring = true).assertIsDisplayed()
        onNodeWithText("acme-corp-5G", substring = true).assertIsDisplayed()
        onNodeWithText("Car audio", substring = true).assertIsDisplayed()
    }

    @Test
    fun theDirectionIsPartOfWhatItSays() = runComposeUiTest {
        // "When the car disconnects" and "when the car connects" are opposite rules, and the
        // names alone do not tell them apart.
        setContent { RecurrenceRuleItem(rule = rule(bluetooth = car), index = 0, onEdit = null, onDelete = null, onTaskClick = null) }
        waitForIdle()

        onNodeWithText("out of reach", substring = true).assertIsDisplayed()
    }
}
