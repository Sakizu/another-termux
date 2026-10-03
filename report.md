# another-termux — Report

Base: `termux/termux-app` at tag `v0.119.0-beta.3`. Branch: `vibecoding`.
Scope v1: session-drawer overhaul + ported security fixes. Everything else is stock upstream.

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

## 2. Drawer overhaul

The session drawer is now reorderable and renameable, compact monochrome.

- `app/build.gradle`: added `androidx.recyclerview:recyclerview:1.2.1`.
- `app/src/main/res/layout/activity_termux.xml`: drawer's `ListView` replaced with `RecyclerView`.
- `app/src/main/res/layout/item_terminal_sessions_list.xml`: new compact row — drag handle
  (left), session label (`[N] name` + title, as before), pencil rename button (right);
  inline rename `EditText` shown while renaming. Grayscale only, honors the app's dark/light theme.
- `app/src/main/res/drawable/ic_drag_handle.xml`, `ic_edit.xml`: monochrome icons.
- `TermuxSessionsListViewController`: rewritten as a `RecyclerView.Adapter`.
  - Drag handle starts an `ItemTouchHelper` up/down drag; the drop swaps entries in the live
    service session list, so numbering and session switching follow the new order.
  - Tapping the label or pencil swaps in the inline rename field; IME Done / Enter commits
    through the existing `renameSession` path (`TerminalSession.mSessionName`); empty input
    or Back cancels. Long-press still opens the rename dialog as a fallback.
  - Row tap switches session and closes the drawer (unchanged); dead sessions keep the
    strikethrough style (unchanged).
- `TermuxActivity.setTermuxSessionsListView()`: wires `LinearLayoutManager`, adapter, and
  the touch helper.
- `TermuxTerminalSessionActivityClient.renameSessionToName()`: dialog-free rename entry point
  used by the inline field, followed by a drawer refresh.

## 3. Build / CI

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

## 4. L3 verification (APK `termux-app_v0.119.0-beta.3+f35820c-apt-android-7-github-debug_arm64-v8a.apk`, 35.8 MB)

| Check | Result |
|---|---|
| aapt2 badging | `com.termux`, versionCode `1022`, label `Termux`, sdkVersion `24`, targetSdkVersion `28`, native-code `arm64-v8a` only |
| `lib/` contents | `arm64-v8a` only: `libtermux.so`, `libtermux-bootstrap.so`, `liblocal-socket.so` |
| JNI symbols (readelf) | 14× `Java_com_termux_*` (original set), 0× `Java_com_sakizu_*` |
| Signature (apksigner) | valid; signer cert is not the AOSP public testkey |
| Launcher icon | stock upstream `ic_launcher` |
| Bootstrap | `libtermux-bootstrap.so` is a valid zip with 3490 files |
| Drawer feature | `TermuxSessionsListViewController$SessionViewHolder`, `renameSessionToName` present in dex |

## 5. L4 manual test script (on-device)

Prerequisites: an arm64 Android 7+ test device with "Install unknown apps" allowed.

1. `adb install` (or file transfer + tap) the APK from the CI artifact.
2. Launch: bootstrap installs, welcome text appears, prompt ready. Run `pkg update && pkg upgrade` — must complete with no dpkg errors.
3. Drawer: open the session drawer (hamburger / edge swipe). Create 3 sessions. Drag session 3 above session 1 via the drag handle — order and `[N]` numbers follow. Tap the pencil on a session, rename inline, press Done — name sticks. Long-press a session — rename dialog still appears.
4. Kill the app, relaunch — sessions persist per normal Termux behavior.
5. Extra keys, IME input, and terminal rendering behave as stock (no changes in v1 scope).
6. Optional: `adb logcat` while renaming/reordering — no exceptions from the drawer code.

Known UNVERIFIED: on-device behavior of the drawer (needs the L4 run above); the deferred
round-2 items listed in section 1.
