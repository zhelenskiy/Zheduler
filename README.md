Zheduler is a task manager built as a Kotlin Multiplatform project, targeting Android, iOS, Web
(Wasm and JS), Desktop (JVM) and a Ktor server. Tasks live in spaces, can depend on one another,
carry recurrence rules and notifications, and are grouped and ordered by user-defined view modes.

* [/composeApp](./composeApp/src) holds the entire user interface, as a Compose Multiplatform
  library shared by every client. It contains several subfolders:
    - [commonMain](./composeApp/src/commonMain/kotlin) is for code that's common for all targets.
    - Other folders are for Kotlin code compiled only for the platform their name indicates —
      [iosMain](./composeApp/src/iosMain/kotlin) for iOS, [jvmMain](./composeApp/src/jvmMain/kotlin)
      for the desktop app, and so on. `cascadeMain` is shared by the targets whose rich-text editor
      is available; the rest fall back to a raw Markdown field.

* [/androidApp](./androidApp/src) is the Android application itself: the manifest, the launcher
  activity and the platform wiring around the shared UI. Android build and run tasks live here,
  not in `/composeApp`.

* [/iosApp](./iosApp/iosApp) contains the iOS application. Even with the UI shared through Compose
  Multiplatform, this entry point is needed, and it is where SwiftUI code belongs.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

* [/shared](./shared/src) holds the model, the repositories and the Room database shared by every
  target. The most important subfolder is [commonMain](./shared/src/commonMain/kotlin).

* [/sqliteWasmWorker](./sqliteWasmWorker/src) wraps SQLite compiled to WebAssembly in a web worker,
  which is how the browser targets get a database off the main thread.

### Scheduled events

Due dates, reminders and recurrence rules are acted on by
[`ScheduledEventEngine`](./shared/src/commonMain/kotlin/com/zhelenskiy/zheduler/zheduler/events/ScheduledEventEngine.kt).
Nothing about a schedule is stored: every run recomputes the whole thing from the tasks, so editing
a due date changes what is coming without anything having to be cancelled. The only thing written
down is a watermark of the last moment swept, which is what stops a restarted process from either
repeating an alert or losing one.

A run delivers reminders and deadlines through a platform `EventNotifier` — the Android
notification shade, the desktop notification service, `UNUserNotificationCenter`, the browser's
notification API — and lets recurrence rules come round: the rule fires, its schedule is wound
forward past the present, and the task's due date follows it. A device that was off for a week
comes back to one task to do today rather than seven notifications.

Nothing is dropped for being old. A deadline missed over a holiday is still missed, and a warning
asked for a month ahead of a deadline that is now tomorrow is still the warning that was asked
for — so a warning is judged by the deadline it warns of, not by how long ago it fell due. What
stops any of it being said twice is a record of what has already been said, not a window. Only a
first run holds its tongue, so that meeting a database full of old tasks for the first time is not
a pile of notifications.

What a notification says is worked out when it is delivered rather than when it was arranged:
"Due in 1 day" while there is time, "Due now" at the moment, "Overdue by 3 days" once there is
not. Everything one task has to say in a single run is said once, in the words of whichever moment
describes where the task actually stands.

Rules that wait for a status rather than a moment — "when I mark this done, put it back on the
list" — are fired by the same run. They are not part of the plan, having no time of their own; a
sweep notices them by looking at what each task's status now is. Nothing has to be remembered to
stop them firing twice, because firing moves the task out of the status that triggered it. The
status is matched by kind and not by value, because the rule editor stores a bare `Blocked` or
`Declined` that no real task ever equals.

A sweep also says when the app has changed a status by itself — a task that became workable
because its last blocker was finished, a parent that followed its subtasks. Not a recurrence
reset: that is the schedule doing what it was told, and the deadline alert already lands on the
same moment.

**Time zones.** The zone is read again on every run rather than captured, because a process
outlives the zone it started in. A reminder is a wall-clock offset from the deadline — "a day
before 09:00" is 09:00 the previous day — so it is 25 real hours on the day the clocks go back and
23 on the day they go forward. Such a reading is not always one instant, and
[`WallClockResolution`](./shared/src/commonMain/kotlin/com/zhelenskiy/zheduler/zheduler/events/WallClockResolution.kt)
is where that is faced: `occurrencesIn` returns two instants on the day a zone falls back, none on
the day it springs forward, and one otherwise, with the policy for choosing between them stated
rather than inherited from the platform conversion.

**Surviving on Android.** The engine runs alongside the UI on every platform, but on Android that
is not enough: the process is killed as soon as the app leaves the screen. There,
[`ScheduledEventWorker`](./composeApp/src/androidMain/kotlin/com/zhelenskiy/zheduler/zheduler/events/ScheduledEventWorker.kt)
sweeps through WorkManager, whose queue is kept in its own database — so the appointment survives
the process being killed, the app being swapped out, and the device being restarted.
`ScheduleRefreshReceiver` re-plans on `BOOT_COMPLETED`, so reminders are running from the moment
the device is and without the app having been opened, and on `TIMEZONE_CHANGED` and `TIME_SET`,
which move every reminder at once.

Every sweep re-books that appointment, through the `onSwept` hook the engine is given — not just
the sweeps the worker itself makes. Leaving it to the worker meant the only thing that could move
the wake-up was the wake-up going off: a task created in the app while one was booked a day out
was simply never heard, which is what happened the first time this was run on a device.

WorkManager will not wake a dozing device to the second, so a reminder can arrive a few minutes
late. The alternative is an exact alarm, which recent Android versions grant only to apps whose
whole purpose is alarms.

### Rules that wait for a place

A recurrence rule can wait for the user to arrive somewhere or to leave it, alongside the moments
and statuses it could already wait for. The place is a point and a radius —
[`GeoArea`](./shared/src/commonMain/kotlin/com/zhelenskiy/zheduler/zheduler/geo/Geo.kt) — and the
rule is fired by a *crossing* of that edge rather than by being on one side of it.

Areas are **copied into the rule**, not pointed at. The list of places under *Places* on the space
list is an address book to pick from; deleting an entry there changes no rule, and a task exported
to a device that has never heard of that book still knows where it is waiting for. It is the same
trade a chosen sound makes by carrying its own label.

A crossing exists only in the difference between where the device is now and where it was last
seen, so the last answer is written down beside the scheduler's watermark. An area with nothing
written down has never been looked at, which is not the same as one known to be outside: a rule
about home, written at home, must not go off the moment it is saved. Nothing fires on a first
sighting.

Nothing is asked of the platform unless some rule is actually watching a place — positioning costs
battery and, on the phones, a permission prompt. And a device that cannot say where it is answers
*unknown* rather than "outside everything": on a desktop, with the permission refused, or with only
network positioning and no connectivity. Whereabouts are then left exactly as they were, so the
crossing that happened meanwhile is found the moment a real fix arrives rather than being lost or
invented. No network is needed for any of this; positioning is the device's own.

A radius can be anything from a metre to a few thousand kilometres. A metre is finer than any
consumer fix, so a fence that small will seldom be entered by positioning alone — but it is a
reasonable thing to ask for beside a condition that *is* exact, and a rule wanting a point on the
map and the office wifi is not relying on the metre.

Signals are noisier still, and have no distance to measure, so what stands in for that margin is a
grace period: a network that has gone is held as still present for a couple of minutes, and the
clock runs from the first sweep that *noticed* it missing rather than from the last that saw it — a
phone on a table runs no sweeps for hours, so "last seen" is stale the instant a router blinks, and
measured from that a hiccup at midnight is a departure. A sweep is booked for the moment the grace
runs out, since nothing else is going to happen to make the departure noticed.

Boundaries are noisy. Getting *in* means being inside the radius; getting *out* means being clear
of it by a margin — at least a tenth of the radius, and as much as the fix's own stated error where
that is larger. Without it a fix sitting on the edge reads as arriving and leaving over and over,
and every one of those is a task being reset.

Paired with a moment or a status, the place is a condition rather than the event: "every Monday,
once I get to the office" is armed by Monday and fired by arriving. A rule whose *only* trigger is
a place has nothing else that can set it off, and waits for a crossing and for nothing else.

**Wifi and bluetooth.** A rule can also wait on a network being joined or a bluetooth device being
connected — two conditions, not one, because they are not picked the same way and may each want
their own direction ("on the office wifi, once the car has disconnected"). A network is chosen by
name: the one you are on, or one typed from memory, since a rule about the office is written at
home. A device is chosen from the ones the machine is already paired with, marked with which are
connected at this moment, because nobody knows a bluetooth address by heart.

That is often the better question to ask: being on the office network says you are *in* the
office in a way that a hundred metres of GPS never quite does, and it works in a basement. These are
matched by the same machinery as an area — present or absent, appearing or going away — and a rule
may carry both, in which case both must hold. Where an area has a margin around its edge, a signal
has a grace period instead: a router hiccups far more readily than a person walks out of a building,
so a signal that has gone is treated as still present for a couple of minutes.

A platform answers *per kind*, and "cannot tell" is not "not there" — that distinction is most of
the work. Android reads the joined network and the connected bluetooth devices: the ones already
joined and connected, never scanning or discovering, both of which are throttled, slow and rude. It
also declines to answer where it *cannot* answer — the SSID is withheld while location is switched
off, and reporting that as "on no network" would fire a rule about leaving the office wifi while the
user sat in the office.

The desktop asks its own system, in its own words, for both: the network name (`networksetup` and
`ipconfig` on macOS, `nmcli`, `netsh`) and the paired bluetooth devices with their connection state
(`system_profiler` on macOS, `bluetoothctl` on Linux; nothing on Windows, where the device manager
makes a paired-but-idle device look much like one that is not there and this could not be checked).

Shelling out sounds worse than the alternative and is not. The Kotlin bluetooth libraries that
publish a JVM target — [Kable](https://github.com/JuulLabs/kable),
[Blue Falcon](https://github.com/Reedyuk/blue-falcon) — are *low-energy* libraries, and a car stereo
or a pair of headphones speaks classic bluetooth, which they cannot see at all; what they offer is
scanning for nearby peripherals, which is a different question from "is my car connected". For the
wifi name there is no library at all. The systems' own tools answer the question actually being
asked. Every one of those is a small trap in the same
direction: the tools localise their output, so they are run under `LC_ALL=C`; Windows writes to a
pipe in the console's own code page, so it is asked for UTF-8 first and a name that still will not
decode is treated as unknown rather than as a name that matches nothing; and a modern Mac tells an
unauthorised process it is "not associated with an AirPort network" *while joined to one*, so two
sources have to agree before that is believed.

iOS and the browser can answer for neither kind: the wifi name is behind an entitlement Apple grants
per app, and a browser is deliberately never told. So a rule about a network never fires there, and
the screen where signals are chosen says so — along with anything else it discovers by asking, such
as a Mac that will not name its network — before the rule is written rather than leaving it to be
found out by nothing ever happening.

**Catching a crossing that happens while the app is not running.** A sweep only ever *samples*
where the device is, so someone who leaves and comes back between two of them has crossed twice and
a sweep comparing then with now sees neither. On Android
[`LocationWatchService`](./composeApp/src/androidMain/kotlin/com/zhelenskiy/zheduler/zheduler/geo/LocationWatchService.kt)
is a foreground service — the one thing the system will not kill — started only while a rule is
waiting on a place or a signal and stopped again by the first sweep that finds none. It also listens
for the radios changing, so joining a network or a car connecting is noticed as it happens rather
than at the next sweep. It is restarted from
`BOOT_COMPLETED`, which from Android 12 is the only moment a foreground service may be started from
the background at all. Reading where the device is with nothing of the app on screen also needs the
"all the time" location permission, which is a prompt of its own; the places screen says so and
offers it. Elsewhere — the browser, the desktop — there is no such thing to keep alive, and a
crossing made while the app was shut is found by comparison when it is next opened.

### The map

The map is drawn from OpenStreetMap's own raster tiles: a tile is a 256-pixel PNG named by zoom,
column and row, so a map is a fetch and some arithmetic
([`TileMath`](./composeApp/src/commonMain/kotlin/com/zhelenskiy/zheduler/zheduler/geo/TileMath.kt)
holds the projection). Searching for a place by name goes to Nominatim, OpenStreetMap's own
geocoder. Both are plain HTTPS and neither needs a key or an account.

It is driven with a drag to pan and the wheel to zoom — towards the pointer, so the street being
looked at stays under it — plus buttons, a double-tap and a pinch where there are fingers to pinch
with.

There is no map SDK behind it because no one library covers this app's targets. MapLibre Compose is
the closest — it is the OpenStreetMap-native choice, and the most complete one for Kotlin
Multiplatform — but it publishes no `wasmJs` artifact, and its desktop target needs Java 25 through
the foreign-function API. Between them that is two of the six targets here left without a map, plus
a CocoaPods dependency on iOS. Tiles and arithmetic run identically on all six.

Both services come with a usage policy that is a condition of using them rather than advice. The
app identifies itself in a `User-Agent`, asks for no more than four tiles at a time, remembers both
the tiles it has and the ones that were not there, and holds the geocoder to one request a second
on top of the search box's own debounce. Browsers refuse to let a page set `User-Agent` at all, so
there the browser's own is what arrives — the ordinary case for a web app.

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :androidApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :androidApp:assembleDebug
  ```

### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

### Build and Run Server

To build and run the development version of the server, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :server:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :server:run
  ```

### Build and Run Web Application

To build and run the development version of the web app, use the run configuration from the run widget
in your IDE's toolbar or run it directly from the terminal:

- for the Wasm target (faster, modern browsers):
    - on macOS/Linux
      ```shell
      ./gradlew :composeApp:wasmJsBrowserDevelopmentRun
      ```
    - on Windows
      ```shell
      .\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun
      ```
- for the JS target (slower, supports older browsers):
    - on macOS/Linux
      ```shell
      ./gradlew :composeApp:jsBrowserDevelopmentRun
      ```
    - on Windows
      ```shell
      .\gradlew.bat :composeApp:jsBrowserDevelopmentRun
      ```

#### Deploying the web app

The web build keeps its data in the browser's Origin Private File System, which SQLite reaches
through a `SharedArrayBuffer`. Browsers only hand one to a **cross-origin isolated** page, so
whatever serves the files has to send:

```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
```

Without them the app loads and runs with no database at all — nothing is kept between reloads,
and the only sign is a message from the worker in the browser console. The dev server sets them
already ([`composeApp/webpack.config.d/opfs-headers.js`](./composeApp/webpack.config.d/opfs-headers.js));
they matter for the deployed copy. [`composeApp/src/webMain/resources/_headers`](./composeApp/src/webMain/resources/_headers)
carries them for hosts that read that file (Netlify, Cloudflare Pages) and is copied into the
distribution; anywhere else — GitHub Pages among them, which cannot set response headers at all —
needs its own configuration, or a service worker that installs the isolation.

The same file marks the entry point and its bundles uncacheable, because their names do not change
between releases: a browser holding an old `composeApp.js` would otherwise pair it with the new
WebAssembly and fail in ways that look like nothing in particular.

### Build and Run iOS Application

To build and run the development version of the iOS app, use the run configuration from the run widget
in your IDE’s toolbar or open the [/iosApp](./iosApp) directory in Xcode and run it from there.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).