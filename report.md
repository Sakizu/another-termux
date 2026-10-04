# another-termux — Report

Base: `termux/termux-app` at tag `v0.119.0-beta.3`. Branch: `vibecoding`.

What this fork changes: a session-drawer overhaul, a new Immersive Mode setting, and a
performance pass over the Java layer. Everything else is stock upstream: same package
(`com.termux`), same app name and icon, same paths, same bootstrap, arm64-v8a only.

## 1. Audit fixes ported

All fixes below were ported from the earlier audit of this codebase (verified there by code
reading; several with host-side harnesses). Each is one commit. No behavior change beyond the
fix described.

### Round 1 (13)
- `fix(termux-shared): bound readNative read length to remaining capacity` — `local-socket.cpp`
  `readNative()` requested the full Java array length on every loop iteration while advancing
  the write pointer: heap buffer overflow on short reads. Now reads at most the remaining capacity.
- `fix(app): contain DocumentsProvider paths under app home` — `TermuxDocumentsProvider`
  canonicalizes child paths and anchors them under the app home directory.
- `fix(app): contain bootstrap zip extraction within staging dir` — `TermuxInstaller` rejects
  zip entries escaping the staging prefix (zip-slip).
- `fix(app): route toolbar text input through bracketed-paste handling` — pasted toolbar text
  goes through the bracketed-paste path instead of raw injection.
- `fix(termux-shared): send single result frame on am command error` — `AmSocketServer`
  returned after the error frame instead of falling through and emitting a second (success) frame.
- `fix(terminal-view): handle forward-delete and lone surrogates in IME input` — `TerminalView`
  input connection handles `KEYCODE_FORWARD_DEL` and unpaired surrogates without crashing.
- `fix(app): anchor open-receiver path allow-list on directory separator` — `TermuxOpenReceiver`
  prefix check anchored so `/allowed-evil` no longer matches `/allowed`.
- `fix(app): sanitize received attachment filenames` — `FileReceiverActivity` strips path
  separators from received filenames.
- `fix(termux-shared): never let the crash reporter crash on notify` — crash-report path is
  fully exception-contained.
- `fix(termux-shared): honor per-key ctrl/alt/shift/fn in extra keys` — extra-keys definitions
  respect per-key modifier overrides.
- `fix(app): only consume Ctrl+Alt key events on matched shortcuts` — unmatched Ctrl+Alt
  combos are no longer swallowed.
- `test(terminal-emulator): add parser chunk-boundary regression tests` — escape-sequence
  parser tests split across chunk boundaries.
- `fix(terminal-emulator): correct JNI string handling in createSubprocess` — `termux.c`
  JNI string/error handling hardened.

### Round 2 — HIGH/CRITICAL only
- `fix(termux-shared): ioctl the client fd, not the server fd, in available()` — one-line fd fix.
- `fix(termux-shared): grant permissions owner-only in FileUtils` — permission grants no
  longer widen to group/other.
- `fix(terminal-view): input robustness fixes` — null/bounds guards on the input path.
- `fix(termux-shared): local socket robustness fixes` — monotonic deadlines on both sides of
  JNI, EINTR retries on socket syscalls.
- `fix(terminal-emulator): ignore out-of-range OSC 104 color indices` — a crafted OSC 104
  sequence with an out-of-range palette index crashed the emulator; now guarded, with tests.
- `fix(app): anchor isChildDocument prefix on path separator` — same anchoring class of bug
  as the open-receiver fix, in the documents provider.
- `fix(termux-shared): forward-declare checkJniException in local-socket.cpp` — compile fix.
- `fix(terminal-emulator): harden subprocess creation and parser edge cases` —
  APC terminated by BEL (an unterminated APC could swallow all subsequent output);
  `kill()` requires a positive pid (pid 0 would signal the app's own process group);
  `termux.c` child resets signal dispositions to `SIG_DFL` before `execvp` (no inherited
  `SIG_IGN`, notably `SIGPIPE`), uses `_exit()` after fork, checks `tcgetattr()`;
  JNI rejects null `argv`/`envp` elements and checks `strdup()`.
- `fix(app): round-2 app module findings` — styling broadcast scoped to our own package;
  `SYMLINKS.txt` link targets validated inside the staging prefix; intent extras redacted
  from the received-intent log line; null guards in `TermuxOpenReceiver.getType()` and
  `FileReceiverActivity`; `SIZE` reported as long; canonical-path search in the documents provider.

Deferred (documented, not fixed): NUL-as-EOF socket framing (may be intentional protocol),
abstract-socket bind length, pid-reuse race (inherent), accessibility/UX tradeoffs.

## 2. Session drawer overhaul

The drawer is a compact monochrome `RecyclerView`. Each row has a drag handle on the left
and a `⋮` menu on the right.

- Dragging the handle reorders sessions through `ItemTouchHelper`. The drop swaps entries
  in the live service session list, so session switching follows the new order.
- Tapping a row switches to that session and closes the drawer.
- The `⋮` menu offers Rename and Kill session. Rename uses the stock `TextInputDialog`
  (an earlier inline-rename field was removed because keystrokes went to the terminal
  behind the drawer). Kill is shown in red, asks for confirmation, and uses the stock
  `finishIfRunning()` semantics; a session killed this way removes its own row automatically.
- The adapter uses targeted notifications: rows are inserted, removed, or rebound
  individually, and the active-row highlight updates with `notifyItemChanged` on the old
  and new positions only. The delayed smooth-scroll to the active session runs only while
  the drawer is open. Row backgrounds are cached drawables and the dark/light theme is
  resolved once per bind.
- Session numbering was removed from row labels and from the session-switch toast.
  Sessions without a name show a display-only "New session" label in the drawer and in
  toasts; the rename dialog still opens empty and nothing is persisted.

Commits: [`e17bd66`](https://github.com/Sakizu/another-termux/commit/e17bd66ef5f67204504b2761422baf0172720178), [`dc76ea8`](https://github.com/Sakizu/another-termux/commit/dc76ea85e4c899099b4b7e59b022245dd7d6dcf1), [`58da896`](https://github.com/Sakizu/another-termux/commit/58da8961dddaea8e38ddaeb222d3638bd068a476), [`41d4e57`](https://github.com/Sakizu/another-termux/commit/41d4e57fbffaaa1740c4eb84b5cefabf11ad16e8), [`3662e8c`](https://github.com/Sakizu/another-termux/commit/3662e8c57c0c65ce5c9c47bcc5a7a44811b0a371) (on top of the earlier
drawer groundwork: [`a95268c`](https://github.com/Sakizu/another-termux/commit/a95268c40919eeb16aecce4b228a550a4b959c73), [`d2197d9`](https://github.com/Sakizu/another-termux/commit/d2197d9fb51477a36880965409673c0f6275dace), [`854947f`](https://github.com/Sakizu/another-termux/commit/854947f9c7745e328dd9d73a9f3d12665bda3877), [`7b1a181`](https://github.com/Sakizu/another-termux/commit/7b1a18151bca02067729858cfabf53a7054a58b4), [`f58ff4a`](https://github.com/Sakizu/another-termux/commit/f58ff4a57ae07bf6ebc910ab1734bdaf46c547f0), [`edc7484`](https://github.com/Sakizu/another-termux/commit/edc74849808c5fdddcae438cc2f5b8f0c69f6f68)).

## 3. Immersive Mode

A separate toggle under Settings → Terminal View → Immersive Mode (preference key
`immersive_mode`, default off). It is independent of the legacy `fullscreen` option in
`termux.properties`, which keeps working as before. This addresses upstream issue #507
(fullscreen/immersive mode removed).

When enabled, the status and navigation bars are hidden with `WindowInsetsControllerCompat`
and the window is laid out edge to edge so the terminal fills the freed space. Getting the
layout right took several iterations:

- [`6a3c6f3`](https://github.com/Sakizu/another-termux/commit/6a3c6f365ec838809e0426fc3bd200822c62f185): hid the bars on create, resume, and focus gain. The bars hid but the terminal
  did not expand into the freed space.
- [`02001ca`](https://github.com/Sakizu/another-termux/commit/02001cac1913c854a453e658f2ede975109af9e7): set `decorFitsSystemWindows` to false while enabled. Still no expansion.
- [`e297b29`](https://github.com/Sakizu/another-termux/commit/e297b2961d4f92816b06f0554cf1a8efe81a4cde): the cause was `android:fitsSystemWindows="true"` on the root view in
  `activity_termux.xml`, which kept padding the layout for the system-bar areas.
  `setImmersiveMode()` now flips it at runtime and re-applies insets. The terminal
  expanded correctly after this.
- [`5a54a08`](https://github.com/Sakizu/another-termux/commit/5a54a08cb08f3cf160ba4b49a3facd3ba8dc47e5): on phones with a punch-hole camera, Android letterboxes the window by default
  and leaves the status-bar area empty. While immersive mode is on, the cutout mode is set
  to `SHORT_EDGES` so the terminal renders into that area. The camera may cover a character
  or two at the top center; that is the standard tradeoff of true edge-to-edge.

[`244219f`](https://github.com/Sakizu/another-termux/commit/244219f4888e5369d7cef32e6f53912f1a4ac694) later made the whole path idempotent: the insets controller is cached, the
last-applied state is tracked, and transition work (insets, cutout mode, `fitsSystemWindows`)
runs only when the toggle actually changes. While enabled, only the bar-hide is re-applied
on focus gain. Disabling restores the previous state and re-applies the legacy
`FLAG_FULLSCREEN` if the `fullscreen` property is set.

## 4. Performance work ([`244219f`](https://github.com/Sakizu/another-termux/commit/244219f4888e5369d7cef32e6f53912f1a4ac694), 19 files)

One batch, no behavior changes. Each item removes redundant work and keeps the existing
behavior.

Rendering (`terminal-view`, `terminal-emulator`):
- PTY output is coalesced: pending `MSG_NEW_INPUT` messages are removed before reposting,
  and each handled message drains the whole input queue once, so a burst of output produces
  one update instead of hundreds of append-plus-redraw cycles.
- The accessibility content description is refreshed at most every 250 ms and only when the
  text changed (it used to rebuild a full-screen string on every update).
- Box-drawing glyph (U+2500–U+257F) measurement results are cached; the cache is dropped when
  the renderer is recreated.
- Cursor blinking invalidates only the cursor cell rectangle instead of the whole view.

Startup and settings (`app`, `termux-shared`):
- Font and color files are read and parsed on a background thread; the resulting typeface
  and colors are applied on the UI thread with a lifecycle guard.
- The soft-keyboard focus listener is registered once instead of on every resume, and the
  delayed keyboard runnable is removed before reposting.
- Error toasts raised from background threads are posted to the main looper (this path
  could previously crash with no Looper prepared).

Command spawning (`terminal-emulator`, `app`, `termux-shared`):
- Environment-dump string building is skipped unless verbose logging is on.
- The app data directory is cached instead of a `PackageManager` IPC per command.
- The environment-variable validation regex is a static `Pattern`.
- The plugin run-command environment cache is invalidated on package version change.
- The haptic-feedback system setting is cached and refreshed through a `ContentObserver`
  instead of a `Settings.System` IPC per extra-key tap.
- The "runs since boot" counters use `apply()` instead of synchronous `commit()`.

Layout and native:
- The inner `RelativeLayout` in `activity_termux.xml` became a `LinearLayout` (same layout,
  single measure pass).
- `FindClass`/`GetMethodID` results are cached in `local-socket.cpp`.
- Dead resources removed (`ic_edit.xml`), plus small cleanups: unused imports, an off-by-one
  insert guard, cached `findViewById` lookups.

Deliberately not done: per-row dirty-region invalidation needs touched-range tracking inside
`TerminalEmulator`; a view-side guess would under-invalidate and corrupt the display, so it
is left for careful follow-up work. The API 24–29 half-immersive edge case needs an emulator
to verify before touching. The split-screen plus immersive interaction is left for on-device
testing.

## 5. Build / CI

- `.github/workflows/debug_build.yml`: builds `apt-android-7` + `arm64-v8a` + debug on
  `vibecoding` pushes; pins Java 11 via `setup-java` (AGP 4.2.2 / Gradle 7.2 predate the
  runner default JDK); validates and uploads only the arm64 APK.
- `app/build.gradle`: ABI splits hardcoded to `arm64-v8a`, no universal APK;
  `downloadBootstraps` downloads only the aarch64 bootstrap (~100 MB saved per build).
- `terminal-emulator/build.gradle`: `ndk.abiFilters 'arm64-v8a'`.
- Note: an earlier attempt drove the ABI subset through a `TERMUX_AUDIT_ABIS` environment
  variable with conditional Groovy in the `android {}` blocks; that broke AGP 4.2.2 project
  evaluation (`No signature of method: …android()`). The hardcoded form configures cleanly.
  Gradle parallel / build-cache flags were also tried and reverted (same evaluation failure);
  left disabled with a comment.

## 6. Verification

APK `another-termux_v0.119.0-beta.3+244219f_arm64-v8a.apk` (CI run for [`244219f`](https://github.com/Sakizu/another-termux/commit/244219f4888e5369d7cef32e6f53912f1a4ac694), green):

| Check | Result |
|---|---|
| aapt2 badging | `com.termux`, versionCode `1022`, versionName `0.119.0-beta.3+244219f`, label `Termux`, sdkVersion `24`, targetSdkVersion `28`, native-code `arm64-v8a` only |
| New-code markers in dex | `mImmersiveModeApplied` and `layoutInDisplayCutoutMode` present (all 30 dex files swept) |
| JNI symbols (readelf) | 14× `Java_com_termux_*`, no renamed symbols (checked on the [`f35820c`](https://github.com/Sakizu/another-termux/commit/f35820c96372c4345007cf7659d3f7e881c0efcb) build; build config unchanged since) |
| Signature (apksigner) | valid; signer cert is not the AOSP public testkey (checked on [`f35820c`](https://github.com/Sakizu/another-termux/commit/f35820c96372c4345007cf7659d3f7e881c0efcb)) |
| Launcher icon / bootstrap | stock upstream icon; bootstrap zip valid (checked on [`f35820c`](https://github.com/Sakizu/another-termux/commit/f35820c96372c4345007cf7659d3f7e881c0efcb)) |

## 7. L4 manual test script (on-device)

Prerequisites: an arm64 Android 7+ device with "Install unknown apps" allowed. No device
information is recorded in this repo.

1. Install the APK from the CI artifact. Launch: bootstrap installs, welcome text appears,
   prompt ready. Run `pkg update && pkg upgrade`; it must complete with no dpkg errors.
2. Drawer: open the session drawer. Create 3 sessions. Drag one above another with the drag
   handle; switching follows the new order. Open the `⋮` menu on a session: Rename opens the
   stock dialog and the name sticks; Kill asks for confirmation and the row removes itself.
   An unnamed session shows "New session" in the drawer.
3. Immersive Mode: enable it under Settings → Terminal View. Status and navigation bars hide
   and the terminal fills the screen, including the area around the camera cutout. Disable it:
   bars return and the layout returns to normal. Optional: with `fullscreen=true` in
   `termux.properties`, disabling Immersive Mode keeps the legacy fullscreen behavior.
4. Performance: `cat` a large file or generate heavy output; output stays smooth without
   stutter. Typing feels the same as stock; no input lag.
5. Extra keys, IME input, and plugins behave as stock.

Known UNVERIFIED: split-screen with Immersive Mode on; API 24–29 fullscreen edge cases;
the deferred round-2 items listed in section 1.
