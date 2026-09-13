# System Resources Telemetry & About System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement host system telemetry sampling, 30-day SQLite historical storage with downsampled bucket aggregation, WebSocket protocol queries/broadcasts, and an Android Jetpack Compose UI with interactive charts, a 6-option time-range dropdown (`1m`, `1h`, `12h`, `24h`, `1w`, `30d`), and a comprehensive About System page (battery health, OS, hardware).

**Architecture:** A Go background collector samples host metrics every 60s into a SQLite table (`system_metrics`) auto-pruned at 30 days. When requested, SQL bucketing yields low-bandwidth aggregates for ranges up to 30 days. The Android client visualizes telemetry using pure Jetpack Compose Canvas line charts and details hardware/battery metrics on an About System page.

**Tech Stack:** Go (Windows Win32, WMI, SQLite), Kotlin (Jetpack Compose, Canvas, Coroutines, StateFlow, Serialization).

**Spec:** [`docs/superpowers/specs/2026-09-13-system-resources-telemetry-design.md`](file:///D:/VS-Code/AI%20Expermients/Switchboard/docs/superpowers/specs/2026-09-13-system-resources-telemetry-design.md)

## Global Constraints
- Commit message format must strictly follow: `<type>(<scope>): <short summary>`.
- NEVER execute `git push`. All commits must remain strictly LOCAL.
- NO AI attribution in commit messages or author fields.
- Android UI must strictly follow Tokyo Night / Expressive palette in `Theme.kt`.
- No third-party chart dependencies in Android; use Jetpack Compose `Canvas`.

---

### Task 1: Protocol Wire Definitions (Backend & Android)

**Files:**
- Create: `backend/internal/protocol/telemetry.go`
- Modify: `mobile/app/src/main/java/com/switchboard/app/net/Protocol.kt`
- Test: `backend/internal/protocol/telemetry_test.go`

**Interfaces:**
- Produces:
  - Wire actions: `Actions.SYSTEM_RESOURCES_QUERY`, `Actions.SYSTEM_RESOURCES_LIVE`, `Actions.SYSTEM_ABOUT_QUERY`
  - Types: `MetricPoint`, `ResourcesQuery`, `ResourcesResponse`, `ResourcesLivePush`, `AboutSystemResponse`, `ProcessItem`, `DriveItem`, `BatteryInfo`, `CpuInfo`, `GpuInfo`, `MemoryInfo`, `OsInfo`

- [ ] **Step 1: Write backend protocol test**
Create `backend/internal/protocol/telemetry_test.go` asserting JSON serialization/deserialization for query, response, and live push payloads.

- [ ] **Step 2: Run test to verify it fails**
Run: `go test -v ./backend/internal/protocol -run TestTelemetrySerialization`
Expected: FAIL (types not defined)

- [ ] **Step 3: Implement protocol types in Go**
Create `backend/internal/protocol/telemetry.go` with wire structs and action constants:
```go
package protocol

const (
    ActionResourcesQuery = "system.resources.query"
    ActionResourcesLive  = "system.resources.live"
    ActionAboutQuery     = "system.about.query"
)
```

- [ ] **Step 4: Implement protocol types in Kotlin**
Modify `mobile/app/src/main/java/com/switchboard/app/net/Protocol.kt` adding `Actions` and `@Serializable` data classes matching the Go wire schema.

- [ ] **Step 5: Run tests and verify**
Run: `go test -v ./backend/internal/protocol -run TestTelemetrySerialization`
Expected: PASS

- [ ] **Step 6: Commit**
```bash
git add backend/internal/protocol/ mobile/app/src/main/java/com/switchboard/app/net/Protocol.kt
git commit -m "feat(protocol): define telemetry and about system wire contracts"
```

---

### Task 2: SQLite Telemetry Storage & Downsampling Engine

**Files:**
- Create: `backend/internal/db/telemetry.go`
- Test: `backend/internal/db/telemetry_test.go`
- Modify: `backend/internal/db/db.go`

**Interfaces:**
- Produces:
  - `(*Database).RecordMetrics(ctx, point protocol.MetricPoint) error`
  - `(*Database).QueryMetrics(ctx, rangeStr string) ([]protocol.MetricPoint, error)`
  - `(*Database).PruneOldMetrics(ctx, retentionDays int) (int64, error)`

- [ ] **Step 1: Write failing DB unit tests**
Create `backend/internal/db/telemetry_test.go` testing insert, downsampled queries for `1h`, `12h`, `24h`, `1w`, `30d`, and pruning beyond 30 days.

- [ ] **Step 2: Run test to verify it fails**
Run: `go test -v ./backend/internal/db -run TestTelemetry`
Expected: FAIL (undefined methods)

- [ ] **Step 3: Implement database schema and queries**
Add `system_metrics` table to `schema` in `db.go`. Implement insert, tiered SQL bucketing expressions with `AVG()`, and retention delete in `backend/internal/db/telemetry.go`.

- [ ] **Step 4: Run tests and verify**
Run: `go test -v ./backend/internal/db -run TestTelemetry`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add backend/internal/db/
git commit -m "feat(backend): implement telemetry sqlite persistence and downsampling"
```

---

### Task 3: Host Telemetry & System Specs Collectors

**Files:**
- Create: `backend/internal/system/telemetry.go`
- Create: `backend/internal/system/telemetry_windows.go`
- Create: `backend/internal/system/telemetry_other.go`
- Create: `backend/internal/system/about_windows.go`
- Create: `backend/internal/system/about_other.go`
- Test: `backend/internal/system/telemetry_test.go`

**Interfaces:**
- Produces:
  - `(c *Controller) SampleMetrics() (protocol.MetricPoint, []protocol.ProcessItem, []protocol.DriveItem, error)`
  - `(c *Controller) AboutSystem() (protocol.AboutSystemResponse, error)`

- [ ] **Step 1: Write failing collector tests**
Create `backend/internal/system/telemetry_test.go` checking non-zero CPU/RAM sampling and AboutSystem fields.

- [ ] **Step 2: Run test to verify it fails**
Run: `go test -v ./backend/internal/system -run TestSampleMetrics`
Expected: FAIL

- [ ] **Step 3: Implement Windows telemetry & about collectors**
Implement Win32 API calls (`GetSystemTimes`, `GlobalMemoryStatusEx`, `GetIfTable2`, `GetSystemPowerStatus`, `GetDiskFreeSpaceExW`) and WMI queries for GPU, Thermals, Battery health, and HW specs in `telemetry_windows.go` and `about_windows.go`, with stub fallbacks in `*_other.go`.

- [ ] **Step 4: Run tests and verify**
Run: `go test -v ./backend/internal/system -run TestSampleMetrics`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add backend/internal/system/
git commit -m "feat(system): implement host resource and battery telemetry collection"
```

---

### Task 4: Server WebSocket Handlers & Telemetry Loop

**Files:**
- Create: `backend/internal/server/telemetry.go`
- Modify: `backend/internal/server/server.go`
- Modify: `backend/internal/server/session.go`
- Test: `backend/internal/server/telemetry_test.go`

**Interfaces:**
- Handles incoming actions:
  - `system.resources.query` -> queries DB and replies
  - `system.about.query` -> collects system specs and replies
- Background loop:
  - Ticks every 60s: records snapshot to DB and broadcasts `system.resources.live` to connected clients.

- [ ] **Step 1: Write server integration test**
Create `backend/internal/server/telemetry_test.go` verifying query handling and live push dispatch.

- [ ] **Step 2: Run test to verify it fails**
Run: `go test -v ./backend/internal/server -run TestTelemetryServer`
Expected: FAIL

- [ ] **Step 3: Implement server handlers and ticker**
Add background loop in `backend/internal/server/telemetry.go`, wire into `server.go` startup/shutdown, and dispatch responses in `session.go`.

- [ ] **Step 4: Run tests and verify**
Run: `go test -v ./backend/internal/server -run TestTelemetryServer`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add backend/internal/server/
git commit -m "feat(backend): add telemetry websocket handlers and live broadcast ticker"
```

---

### Task 5: Mobile Client Network & ViewModel Integration

**Files:**
- Modify: `mobile/app/src/main/java/com/switchboard/app/net/SwitchboardClient.kt`
- Modify: `mobile/app/src/main/java/com/switchboard/app/SwitchboardConnection.kt`
- Modify: `mobile/app/src/main/java/com/switchboard/app/SwitchboardViewModel.kt`
- Test: `mobile/app/src/test/java/com/switchboard/app/TelemetryViewModelTest.kt`

**Interfaces:**
- Consumes: Wire frames for `system.resources.query`, `system.resources.live`, `system.about.query`
- Produces:
  - `SwitchboardViewModel.telemetryState: StateFlow<TelemetryUiState>`
  - `SwitchboardViewModel.queryResources(range: String)`
  - `SwitchboardViewModel.queryAboutSystem()`

- [ ] **Step 1: Write ViewModel unit test**
Create `mobile/app/src/test/java/com/switchboard/app/TelemetryViewModelTest.kt` testing telemetry state updates upon receiving live push and query replies.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :app:testDebugUnitTest --tests com.switchboard.app.TelemetryViewModelTest`
Expected: FAIL

- [ ] **Step 3: Implement client handling and ViewModel state**
Update `SwitchboardClient.kt` to decode `system.resources.*` and `system.about.query` frames. Update `SwitchboardViewModel.kt` with state flows and dispatch functions.

- [ ] **Step 4: Run tests and verify**
Run: `./gradlew :app:testDebugUnitTest --tests com.switchboard.app.TelemetryViewModelTest`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add mobile/app/src/
git commit -m "feat(android): wire telemetry network events and viewmodel state"
```

---

### Task 6: Interactive Jetpack Compose Canvas Chart Component

**Files:**
- Create: `mobile/app/src/main/java/com/switchboard/app/ui/ResourceLineChart.kt`
- Test: `mobile/app/src/test/java/com/switchboard/app/ui/ResourceChartMathTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun ResourceLineChart(modifier: Modifier, points: List<MetricPoint>, series: List<ChartSeries>, selectedPoint: MetricPoint?, onSelectPoint: (MetricPoint?) -> Unit)`

- [ ] **Step 1: Write chart math normalization tests**
Create unit tests for X/Y coordinate normalization, timestamp formatting, and nearest-point scrubber detection in `ResourceChartMathTest.kt`.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :app:testDebugUnitTest --tests com.switchboard.app.ui.ResourceChartMathTest`
Expected: FAIL

- [ ] **Step 3: Implement ResourceLineChart with Compose Canvas**
Create `ResourceLineChart.kt` featuring smooth cubic bezier curves, gradient alpha fills, grid guides, and touch drag scrubber.

- [ ] **Step 4: Run tests and verify**
Run: `./gradlew :app:testDebugUnitTest --tests com.switchboard.app.ui.ResourceChartMathTest`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add mobile/app/src/
git commit -m "feat(android): implement custom compose canvas line chart"
```

---

### Task 7: Resources Section & Screen with Time-Range Dropdown

**Files:**
- Create: `mobile/app/src/main/java/com/switchboard/app/ui/ResourcesScreen.kt`
- Modify: `mobile/app/src/main/java/com/switchboard/app/ui/HomeScreen.kt`

**Interfaces:**
- Produces:
  - `Section.Resources` in `HomeScreen.kt`
  - Dropdown selector: `1 minute`, `1 hour`, `12 hours`, `24 hours`, `1 week`, `30 days`
  - Live glance cards (CPU, RAM, GPU, Net), Chart view, Thermals & Drives, Top Processes.

- [ ] **Step 1: Implement ResourcesScreen.kt**
Build the full screen with the time-range dropdown menu, filter tabs, live metrics, chart embedding, thermals, and top processes list.

- [ ] **Step 2: Integrate into HomeScreen.kt**
Add `Section.Resources` to `HomeScreen.kt` enum, render the summary pod with live CPU & RAM, and handle navigation to `ResourcesScreen`.

- [ ] **Step 3: Verify Android compilation**
Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**
```bash
git add mobile/app/src/main/java/com/switchboard/app/ui/
git commit -m "feat(android): add resources screen with time range dropdown and live metrics"
```

---

### Task 8: About System Screen (Battery Health & Hardware Specs)

**Files:**
- Create: `mobile/app/src/main/java/com/switchboard/app/ui/AboutSystemScreen.kt`
- Modify: `mobile/app/src/main/java/com/switchboard/app/ui/ResourcesScreen.kt`

**Interfaces:**
- Produces:
  - Battery Health Card (wear %, cycle count, design vs full capacity mWh, charging state).
  - Hardware Specs Card (CPU, GPU, RAM, Disks, Motherboard).
  - OS & Uptime Card (OS name, build, uptime).

- [ ] **Step 1: Implement AboutSystemScreen.kt**
Build the Compose screen with battery health circular progress, stats grid, and collapsible hardware cards.

- [ ] **Step 2: Add Navigation from ResourcesScreen.kt**
Add top bar action button and quick info chip to navigate seamlessly into `AboutSystemScreen`.

- [ ] **Step 3: Verify Android compilation**
Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**
```bash
git add mobile/app/src/main/java/com/switchboard/app/ui/
git commit -m "feat(android): implement about system screen with battery health and specs"
```

---

### Task 9: Verification & Documentation Updates

**Files:**
- Modify: `development/devdocs/TODO.md`
- Modify: `development/devdocs/architecture.md`
- Modify: `README.md`

- [ ] **Step 1: Run full test suites**
Run: `go test -v ./backend/...`
Run: `./gradlew :app:testDebugUnitTest`

- [ ] **Step 2: Update documentation**
Record the new telemetry protocol actions, SQLite retention rules, and mobile screens in devdocs.

- [ ] **Step 3: Submodule Dual-Commit**
Commit inside `development/` on `main`, then commit parent repo on `master`.
