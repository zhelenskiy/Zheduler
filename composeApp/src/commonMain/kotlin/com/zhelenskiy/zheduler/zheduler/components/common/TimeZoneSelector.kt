package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Comprehensive list of common timezones for the timezone selector
 */
val commonTimezones = listOf(
    "UTC",
    // North America - USA
    "America/New_York",
    "America/Detroit",
    "America/Chicago",
    "America/Indianapolis",
    "America/Denver",
    "America/Phoenix",
    "America/Los_Angeles",
    "America/Anchorage",
    "America/Juneau",
    "America/Honolulu",
    // North America - Canada
    "America/Toronto",
    "America/Vancouver",
    "America/Montreal",
    "America/Edmonton",
    "America/Calgary",
    "America/Winnipeg",
    "America/Halifax",
    "America/St_Johns",
    // Central America & Caribbean
    "America/Mexico_City",
    "America/Tijuana",
    "America/Cancun",
    "America/Guatemala",
    "America/Panama",
    "America/Havana",
    "America/Jamaica",
    "America/Puerto_Rico",
    "America/Costa_Rica",
    // South America
    "America/Bogota",
    "America/Lima",
    "America/Quito",
    "America/Santiago",
    "America/Buenos_Aires",
    "America/Sao_Paulo",
    "America/Rio_Branco",
    "America/Manaus",
    "America/Caracas",
    "America/La_Paz",
    "America/Montevideo",
    "America/Asuncion",
    // Western Europe
    "Europe/London",
    "Europe/Dublin",
    "Europe/Lisbon",
    "Europe/Paris",
    "Europe/Madrid",
    "Europe/Barcelona",
    "Europe/Berlin",
    "Europe/Amsterdam",
    "Europe/Brussels",
    "Europe/Luxembourg",
    "Europe/Zurich",
    "Europe/Vienna",
    "Europe/Rome",
    "Europe/Milan",
    "Europe/Monaco",
    // Central Europe
    "Europe/Prague",
    "Europe/Warsaw",
    "Europe/Budapest",
    "Europe/Bratislava",
    "Europe/Ljubljana",
    "Europe/Zagreb",
    "Europe/Belgrade",
    "Europe/Sarajevo",
    "Europe/Skopje",
    "Europe/Podgorica",
    "Europe/Tirana",
    // Northern Europe
    "Europe/Stockholm",
    "Europe/Oslo",
    "Europe/Copenhagen",
    "Europe/Helsinki",
    "Europe/Tallinn",
    "Europe/Riga",
    "Europe/Vilnius",
    "Europe/Reykjavik",
    // Eastern Europe
    "Europe/Athens",
    "Europe/Bucharest",
    "Europe/Sofia",
    "Europe/Kyiv",
    "Europe/Chisinau",
    "Europe/Moscow",
    "Europe/Minsk",
    "Europe/Kaliningrad",
    "Europe/Samara",
    "Europe/Istanbul",
    // Africa - North
    "Africa/Cairo",
    "Africa/Casablanca",
    "Africa/Tunis",
    "Africa/Algiers",
    "Africa/Tripoli",
    // Africa - West
    "Africa/Lagos",
    "Africa/Accra",
    "Africa/Abidjan",
    "Africa/Dakar",
    // Africa - East
    "Africa/Nairobi",
    "Africa/Addis_Ababa",
    "Africa/Dar_es_Salaam",
    "Africa/Kampala",
    "Africa/Khartoum",
    // Africa - South
    "Africa/Johannesburg",
    "Africa/Cape_Town",
    "Africa/Harare",
    "Africa/Lusaka",
    // Middle East
    "Asia/Dubai",
    "Asia/Abu_Dhabi",
    "Asia/Riyadh",
    "Asia/Jeddah",
    "Asia/Tehran",
    "Asia/Jerusalem",
    "Asia/Tel_Aviv",
    "Asia/Beirut",
    "Asia/Damascus",
    "Asia/Amman",
    "Asia/Baghdad",
    "Asia/Kuwait",
    "Asia/Qatar",
    "Asia/Bahrain",
    "Asia/Muscat",
    // Central Asia
    "Asia/Almaty",
    "Asia/Tashkent",
    "Asia/Bishkek",
    "Asia/Dushanbe",
    "Asia/Ashgabat",
    "Asia/Baku",
    "Asia/Tbilisi",
    "Asia/Yerevan",
    // South Asia
    "Asia/Kolkata",
    "Asia/Mumbai",
    "Asia/Delhi",
    "Asia/Bangalore",
    "Asia/Chennai",
    "Asia/Dhaka",
    "Asia/Karachi",
    "Asia/Lahore",
    "Asia/Colombo",
    "Asia/Kathmandu",
    "Asia/Thimphu",
    // Southeast Asia
    "Asia/Bangkok",
    "Asia/Jakarta",
    "Asia/Singapore",
    "Asia/Kuala_Lumpur",
    "Asia/Ho_Chi_Minh",
    "Asia/Hanoi",
    "Asia/Manila",
    "Asia/Phnom_Penh",
    "Asia/Vientiane",
    "Asia/Yangon",
    "Asia/Brunei",
    // East Asia
    "Asia/Hong_Kong",
    "Asia/Macau",
    "Asia/Taipei",
    "Asia/Shanghai",
    "Asia/Beijing",
    "Asia/Chongqing",
    "Asia/Seoul",
    "Asia/Pyongyang",
    "Asia/Tokyo",
    "Asia/Osaka",
    "Asia/Ulaanbaatar",
    // Russia - Asian
    "Asia/Vladivostok",
    "Asia/Yakutsk",
    "Asia/Irkutsk",
    "Asia/Krasnoyarsk",
    "Asia/Novosibirsk",
    "Asia/Omsk",
    "Asia/Yekaterinburg",
    "Asia/Magadan",
    "Asia/Kamchatka",
    // Australia
    "Australia/Perth",
    "Australia/Adelaide",
    "Australia/Darwin",
    "Australia/Brisbane",
    "Australia/Sydney",
    "Australia/Melbourne",
    "Australia/Hobart",
    "Australia/Canberra",
    // Pacific
    "Pacific/Auckland",
    "Pacific/Wellington",
    "Pacific/Fiji",
    "Pacific/Honolulu",
    "Pacific/Guam",
    "Pacific/Port_Moresby",
    "Pacific/Noumea",
    "Pacific/Tahiti",
    "Pacific/Samoa",
    "Pacific/Tongatapu",
    // Atlantic
    "Atlantic/Azores",
    "Atlantic/Canary",
    "Atlantic/Bermuda",
    "Atlantic/Reykjavik"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeZoneSelector(
    useSystemTimezone: Boolean,
    selectedTimezone: String,
    onUseSystemTimezoneChange: (Boolean) -> Unit,
    onTimezoneSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = useSystemTimezone,
                onCheckedChange = onUseSystemTimezoneChange
            )
            Text(
                "Use system timezone",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AnimatedVisibility(visible = !useSystemTimezone) {
            var timezoneExpanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = timezoneExpanded,
                onExpandedChange = { timezoneExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedTimezone,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Timezone", style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(timezoneExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                ExposedDropdownMenu(
                    expanded = timezoneExpanded,
                    onDismissRequest = { timezoneExpanded = false }
                ) {
                    commonTimezones.forEach { timezone ->
                        DropdownMenuItem(
                            text = { Text(timezone, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                onTimezoneSelected(timezone)
                                timezoneExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
