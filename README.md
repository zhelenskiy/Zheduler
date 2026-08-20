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