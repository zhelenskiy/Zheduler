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