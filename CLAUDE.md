# CarStage

CarStage is an Android Automotive app for the Hyundai Motor Group in-vehicle app contest (Pleos App Market, category: Entertainment), built by team cold-box. Passengers sing along to music another app plays; the app listens on the microphone, and the cabin (ambient light, climate, seats), the screen and sound effects react. First-round deadline: 2026-11-15. Second round: a live pitch with a working app, 2026-12-03 to 12-04.

## Commands

`adb` is at `~/Library/Android/sdk/platform-tools/adb` and may not be on PATH.

### Environment

- Install: `scripts/bootstrap.sh` (SDK packages pinned in `toolchain/`, the Pleos system image, the AVD, `local.properties`; about 23 GB installed).
- Check: `scripts/doctor.sh`; with the emulator running, `--device` also checks the image build, the mic signal and gear reads.
    - IMPORTANT: when the build or the emulator misbehaves, run `scripts/doctor.sh --device` before changing code.
- One Gradle task: `scripts/gradle.sh <task>`. Builds run on the Temurin 21 that Gradle provisions (`gradle/gradle-daemon-jvm.properties`); the launching JDK only needs 17+, and the script falls back to Android Studio's JBR.

### Build and test

- All checks: `scripts/check.sh` (unit tests of all modules, debug build, lint, release compile and release lint; the release lint catches code that uses permissions the release build drops). After car or audio changes, start the emulator and run `scripts/check.sh --device`.
    - Any lint warning fails (`warningsAsErrors`). Fix it; disable a check in the module's `lint { disable }` only when it does not fit this project, with the reason in a comment.
- Emulator regression: `scripts/regress.sh --install` (installs the debug APK from `check.sh`, about a minute, PASS/FAIL per scenario from the log; name scenarios to run only those):
    - `gleo` open, duel, end song, close restores (Gleo only beeps for a new stage and an immediate duel; the MC speaks) · `drive` gear D closes and restores, gear P offers to sing on · `recovery` force-stop while open, next start restores · `probes` the Lab's stage and drive probes · `night` a test-made night keeps ambient brightness ≤ 40% and below the day floor of 20% · `replay` plays `recordings/regress.m4a` through `scripts/replay.sh`, skipped when absent (the team's test recordings are internal and not published) · `mc` with the MC off no line and no voice engine; on mid-stage the engine starts · `trip` the usual greeting without a trip, a 95-minute test trip is named · `background` Settings over the stage closes it, coming back offers to sing on · `call` (only with `--call` or by name; rings) a call closes, hanging up offers to sing on.
    - It mutes effects, resets night/trip/MC per scenario, and leaves gear P, stage closed, effects on, no call. Failures print the awaited line and the log tail and exit 1. It will not run over an installed release build.
- Score test scenes: `CARSTAGE_SCORE_CLIPS=<dir> scripts/gradle.sh :app:testDebugUnitTest --tests 'com.carstage.app.score.ScoreClipsTest' --rerun` scores each 16-bit WAV in the dir into `scores.txt` (skipped without the variable). Scenes are mixed from openly licensed songs by `tools/score-scenes/mix.py` (`docs/09` section 5).

### Emulator

- Start: `scripts/run-emulator.sh` (cold boot, host mic on). From Android Studio the mic is silent until `adb emu avd hostmicon`.
    - `--silent`: `scripts/virtual-cabin.sh` feeds the mic over gRPC (reflects the device's playback with a delay, mixes in a voice file: `scene --music on --voice <file> --voice-gain-db 10`, `voice "<sentence>"`, `status`). Do not stop its daemon while the emulator runs (emulator 37.2.12 dies).
    - Mac screen off or locked: `HEADLESS=1`, and disable the crashing driving view: `adb shell pm disable-user --user 0 ai.umos.drivingview` (and `--user 10`).
- Install: `adb install -r -g app/build/outputs/apk/debug/app-debug.apk`. Pleos runs apps as user 10: `adb exec-out run-as com.carstage.app --user 10 cat <path>`.
- Car state: `scripts/vconsole.sh gear D | charging on | soc 42 | state ev_battery`. Seats: `set /vehicle/seat kind=occupied area=<driver|passenger> value=false` (on the emulator, Empty reads as occupied: `docs/05` "착석 감지 확인"). Defroster `set /vehicle/hvac front_defroster=true`, wiper `set /vehicle/body front_wiper=Low`.
- Calls: `adb emu gsm call 01012345678`, then `accept` and `cancel` with the same number.
- Gleo: `adb shell am start -a OPEN_STAGE -p com.carstage.app --es ACTION_PARAM '{}'` (also `FINISH_SONG`, `START_BATTLE`).
- Split pane (CarStage 841×1210): `adb shell input swipe 2547 717 1700 717 600`; back: `adb shell input swipe 1706 717 2555 717 600`.
- Another app's music: `tools/mediatester` (a `MediaBrowserService` + `MediaSession` music app). `scripts/gradle.sh :mediatester:assembleDebug`, install `tools/mediatester/build/outputs/apk/debug/mediatester-debug.apk`, then `adb shell am start -n com.carstage.mediatester/.TesterActivity --es cmd <play|stop>` (a song: `adb shell "am start … --es cmd play --es song 'Big Rock'"`). Load songs with `scripts/media-library.sh [files...]` (default `build/music`, `--clear`; names use letters, digits, `.`, `-`, `_`). Test songs: Kevin MacLeod, CC BY 3.0 (`docs/09`).
- Other apps' playback capture (echo reference): `scripts/playback-tap.sh start|stop|status` (`tools/playbacktap`). Echo removal is off by default; debug builds take `--es echo on|off` and `--ei echo_hold_ms 400`. The release build never asks for capture consent.
- Stop test music and tones as soon as a test ends: they play through the Mac's speakers.

### Lab probes and developer commands (debug build)

- `adb shell am start -n com.carstage.app/.MainActivity --es probe <all|audio|vehicle|sample|smoke|platform|stage|drive|sounds|voice|nowplaying|echo|micsources>`; read with `adb logcat -d -s CarStageLab:I -v raw`, wait with `adb logcat -s CarStageLab:I -e '=== <probe> end' -m 1`. One probe at a time (others answer `busy`).
    - `platform` (~35 s) battery, playback, mic CPU · `stage` a fixed cue to car and simulator, then `stage restore: PASS` (bypasses `GlowDirector`) · `sounds` the five effects, audible · `voice` MC engine state and three duel lines, audible (`voice off` when the MC switch is off) · `drive` gear, restriction, defroster, wipers · `nowplaying` music app info for 20 s, needs `adb shell cmd notification allow_listener com.carstage.app/com.carstage.app.audio.NowPlayingListener 10` · `micsources` compares five mic sources under music · `echo` capture, level, reference delay.
- `DevLab` commands: `--es effects off|on`, `--es night on|off|car` (the emulator is always day), `--es demo on|off` (hides developer UI for recordings), `--es mc on|off` (persists), `--es trip <minutes>|car`, `--es stage open|close`, `--es micsource <MIC|VOICE_RECOGNITION|…>`, `--es features all|basic`, `--es echo on|off`.

### Replay a recording (debug build)

- `scripts/replay.sh <file>...` replays any recording instead of the mic (needs a `-g` debug install, gear P, no call): converts to 16-bit WAV in `build/recordings/`, pushes it with an md5 check, opens the stage, closes at the end. The result is the `replay … off after …` line; song ends log `song ended` and `song scored <score> (<grade>) …`.
- `adb exec-in … cat >` silently truncates files: `adb push` to `/data/local/tmp`, then `run-as com.carstage.app --user 10 sh -c 'mkdir -p cache/lab && cp /data/local/tmp/<file> cache/lab/'`.
- The Lab's "5초 녹음 저장" file: `--es replay mic-sample.wav`, then `adb logcat -d -s CarStage:I -v raw | grep 'off after'`.

### Release builds

- Submission APK: `scripts/release-apk.sh [--out apk/cold-box_CarStage_release.apk]` signs with the team key and checks the team certificate is the only signer and, while `releaseDrivesCabin` is false, that no car control permission is declared. The key is `~/.android/carstage-release.jks`; key file and password are in the 1Password item named in `scripts/release-apk.sh`. The password reaches only one `--no-daemon` build through `op run`, never a long-lived daemon or the disk, so never set `CARSTAGE_RELEASE_STORE_PASSWORD` yourself, and never commit the key. A team-signed APK cannot replace a debug install; uninstalling the debug build deletes app data (including `cache/lab`).
- Local release check (debug-key signed, keeps app data): `scripts/gradle.sh assembleRelease lintRelease`; with `BT=~/Library/Android/sdk/build-tools/35.0.0`, `$BT/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk build/release-aligned.apk`, `$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out build/release-local.apk build/release-aligned.apk`, `adb install -r -g build/release-local.apk`. Expect `driving [simulator]` in the stage log and no `CONTROL_CAR_*` or `CAR_LIGHTS` in `aapt2 dump permissions`. Release builds have no `run-as` and cannot mute effects or the MC (send `--es mc off` from the debug build first). Reinstall the debug build afterwards (`docs/05` "출시 빌드 확인").

### Logs

- `adb logcat -d -s CarStage:I CarStageLab:I -v raw` (product tag `CarStage`, Lab tag `CarStageLab`).

## Architecture

### `CarStageApp.kt`: the app graph

`AppGraph` builds the app-wide objects once, wired by hand: `VehicleSession`, the simulator, `StageController`, stage open/close, Gleo handling. Role-specific pieces live in the root package:

- `NoticeBoard` (`graph.notices`): the first screen's notice line. `show` replaces it; ongoing conditions (battery, idling, night, control refused) `add`/`remove` their sentence. Each notice has a `NoticeTone` (info, warning, error); the line shows the most severe; info-only lines fold after 3 s. Notices are matched by substring, so a new notice must not be contained in another of a different tone. Shared UI strings live here.
- `Leftovers`: restores what the last stage left (app start, before opening, before a probe, after parking).
- `SongSession` (`graph.songs`): per-song score and duel state; builds each stage's `GlowDirector`. A score carries the title of the track that played at least half the song (`SongTitles`).
- `StageMc` (lines in `McLines`, `docs/12`): greets 1.2 s after opening from car facts (night, trip ≥ 30 min, charging, two front occupants, time of day), reads out scores after the effect, calls duel turns and charge completion. One line at a time, re-checked just before speaking; dropped when its stage closes; silent during the duel announcement. Gleo replies that the MC will repeat ask Gleo only to beep; replies go out within 6 s (`GLEO_REPLY_TIMEOUT_MS`). The MC switch (`McSetting`) persists, default on. Trip time comes from `TripWatch`/`vehicle/TripReader` and is used only when believable (`Trip.believableMinutes`).
- `TrackEnds`: ends the sung song when the music app changes track, repeats, or stops at the end (`StageController.endSong(EndCause.TRACK)`), from `NowPlaying.track`. Pause, seek, late metadata and idle sessions are not ends.
- IMPORTANT: screens and ViewModels never construct `VehicleSession` or `StageEngine`; they use the graph's. The stage opens and closes only through `graph.openStage()` / `closeStage()` (screen buttons through `graph.request()`), from screen or Gleo. These turn errors into notices and run in `graph.scope`, which outlives screens.
- Leaving the app (no CarStage screen visible) closes the stage (`AppGraph.leftScreen`); returning within 3 h while parked asks to sing on (`ResumeOffers.Reason.BACKGROUND`).
- Window size, density and day/night changes do not recreate the activity (manifest `configChanges`).

### First screen flow (`docs/13`)

- Stage closed: choose [기본 | 대결] (`FeatureMode`, persists), then pick a song (the one playing, or from the song list): playback and the stage start together; duel mode starts a duel, driver first.
- Stage open: "그만하기" (close and pause) and the compact player (`home/CompactPlayer`); duel mode adds "대결 시작", the duel card and the last-song question when charging ends. On the passenger's turn the music pauses and the song list opens; the turn's song counts from the pick (`GlowDirector.restartSong`).
- However the stage closes, music stops; music stopped elsewhere for over 4 s closes the stage (`AppGraph.MUSIC_STOP_GRACE_MS`). Then only the result card shows until "확인". Entering the app never stops music.
- Basic mode refuses Gleo's open and duel without music; duel mode accepts them (the regression test relies on this).

### `MainActivity.kt`

The first screen is the stage screen (`home/`). The Lab exists only in debug builds ("개발자 Lab" button, `--es probe`); `--es replay` replays a recording. `MainActivity` reaches the Lab only through `DevLab` (one per build type, `src/debug` and `src/release`; the release one ignores probes). Gleo calls go to `AppGraph.handleGleo`.

### `home/`

- Two panes when the window minus 32 dp margins is at least 1168 dp; otherwise one column (the 841 dp split pane) with the cabin picture on top, collapsing below 360 dp (`SingleColumn`, `ColumnPlan`, `docs/10`).
- Left pane: the cabin simulator (`CabinView`, values in `CabinLook`) and status pill (`CabinStatus`). Right pane: stage controls (`BasicControls`), duel card (`DuelCard`, scoreboard `BoardSide`), status line and reaction meter (`StageStatus.kt`), MC switch (`McToggle`), score reveal (`ScoreReveal`), notice line (`NoticeLine.kt`). Wrap new cards in `HomeCard`.
- The preview line "미리보기 · 차가 허용하면 실내가 이렇게 반응해요" shows when the car has refused control or the build does not drive the cabin (`AppGraph.cabinPreview`, `CabinPreviewLine`).
- Stage glow (`StageGlow`, `GlowLook`): screen edges glow in the ambient color, follow the simulator (so they glow even when the car refuses control), and ease with a critically damped spring without flicker.
- Design tokens (`docs/10` section 5): colors `StageColors` in `ui/Theme.kt` (text contrast ≥ 4.5:1, checked by `StageColorsTest`), `StageSizes`, `PleosTypography` (product text ≥ 24), buttons in `ui/StageButtons.kt` (one `PrimaryButton` per screen, all ≥ 76 dp). No raw font sizes or colors in screen code (cabin picture colors: `CabinColors`). Everything tappable gives click sound and haptics: wrap new `clickable`/`selectable`/`toggleable`/`IconButton` with `withTapFeedback` or `rememberTapFeedback` (`ui/TapFeedback`).
- Battery, night and seats on this screen: `BatteryWatch` (charging line), `ChargeFinish`, `BatterySaver` (below 20% and not charging, no effect that raises climate or seats or moves temperature further; idling warning after 30 min for engine cars, `CarKind`), `NightDimming` (ambient 0–40% at night), `DuelOffer` (both front seats occupied).

### `media/` (song list, `docs/07`)

`SongShelf` (`graph.shelf`) binds to the car's music app's `MediaBrowserService`, lists songs and plays a pick with `playFromMediaId`; no app-specific code. Source order: the player's app, the last picked app, the user's choice (`MediaSource.choose`, via the manifest's `<queries>`). Failures surface on the card, in notices and in the log. It never stops other apps' music.

### `score/` (`docs/09` section 5)

`ScoreKeeper`: per song, participation 30, heat 25, response 35, completion 10, plus a grade. Thresholds are relative to the song (the original singer is also in the mic): participation is a second 10 dB above the song's quiet side, heat a frame 15 dB above its median. Without cheers the score tops out at 65. Singing at the original's level cannot be told apart with one mic.

### `safety/`

`DriveState` (gear, driving restriction, defroster, wipers), `SafetyRules` (commands only while parked, per target), `SafetyGuard` (check before opening, watch the gear every 0.5 s and climate every 2 s while opening or open), `VehicleDriveReader`, `UxRestrictions`. `AppGraph` ties watching to the `OPENING`/`OPEN` phases.

- IMPORTANT: a new `CabinTarget` must get a safety decision in the `when` of `SafetyRules.allows`; a new `CabinCommand` must also be decided in `BatterySaver.allows`. The stage opens only with gear P confirmed and no driving restriction; when driving starts, `AppGraph` closes it and restores.

### `vehicle/`

Pleos Vehicle SDK session and coroutine bridges (`awaitVehicle`, `awaitVehicle2`, `awaitVehicle3`), `FailureLog`, `SeatOccupancyReader`, `EvBatteryReader`, `CarKind`, `NightModeReader`, `TripReader`. Rules: `.claude/rules/pleos-vehicle-sdk.md`.

### `audio/`

Mic capture and analysis (`AudioAnalyzer`, `Accompaniment`, `CheerDetector`, `MoodTracker`, `SongEndDetector`), `LiveMic` (listens only while the stage is open), `CallState`, `MusicPlayback`, `NowPlaying` (mini player: media keys without permission, track info with notification access or from the song list's session), `MicGate`, `EffectPlayer` (CC0 effects, sources in README "효과음 출처"), `Announcer` (MC voice through the Pleos TextToSpeech SDK, `ON_DEVICE`). Rules: `.claude/rules/audio.md`.

- Effects (`SoundCue`, chosen by `GlowDirector`) and MC lines take transient ducking focus as `USAGE_ASSISTANCE_SONIFICATION`, stop on focus loss and when the stage closes, and blank cheer detection while playing plus 0.5 s (`EchoWindow`). The duel drumroll pans between driver (left) and passenger (right) with the reading lights; the fanfare plays from the winner's side.
- The mic is on only when the stage is `OPEN` with no call. A call closes the stage; no stage opens during a call or without mic permission.
- If the mic cannot open or fails mid-stage, close the stage with a notice: never leave an open stage that reacts to nothing. Digital silence (another app holds the mic) for about 3 s shows a notice that clears when sound returns.

### `stage/` (effect engine)

- `StageEngine` sends `CabinCommand`s (targets `CabinTarget`) to the car (`VehicleCabin`, only in builds that drive the cabin: `app/src/cabinControl`) and the simulator (`SimulatedCabin`) separately, so a slow car never stalls the simulator. It also plays timelines (`Cue`).
- Effects are layers (`EffectLayer`) mixed by `LayeredDirector` (brightness/fan/ventilation max, temperature coolest, switches on if any, color from the first color-giving layer). At night brightness scales into 0–40%. Fan and temperature are offsets from the stage start, and the mixer returns them to the start when no layer wants them. `GlowDirector`'s layers: `ParticipationLayer` (voice mode magenta, song mode teal, from `VoiceMode`), `MoodLayer`, `CheerLayer`, `FinaleLayer`, `DuelTurnLayer`, and the exclusive `DuelAnnouncement`. Add new effects as layers and decide their color order and what happens when they withdraw (`docs/09` section 5).
- Duel (`DuelState`): waiting → driver → passenger → announcement → result, started by `GlowDirector.requestDuel` on the audio thread; a turn advances only after a song of 10 s or more (`docs/04`). A new director is made for every stage that opens.
- Crash recovery: originals go to `RestoreJournal` right before the first change; `StageRecovery` restores leftovers at app start, before opening and before probes; `ParkedRestore` retries restores refused while driving once parked. Restores run only while the stage is closed (`StageController.whileClosed`). Test: force-stop with the stage open, restart, expect `restored the values the last stage left`.
- IMPORTANT: always `engine.start(scope)` followed by `finally { engine.stop() }`. `stop()` restores even from a cancelled coroutine and returns what it could not restore as `StopResult`.
- Only values changed during the effect are restored, to the originals read at start (car values exactly as the SDK returned them). Unread targets are never changed.
- When the car refuses control (`CabinControlDenied`), `ControlDenials` stops sending that target for the stage (the next stage retries), the rest keeps reacting, and the notice "차가 허용하지 않은 기능은 화면에서만 반응해요." is added. Logic: `VehicleCabin.applyOnce`.
- Brightness is `Percent` (0–100), color `Rgb` (0xRRGGBB); mic-derived values go through `Percent.ofFraction` / `coerced`.
- `StageController` owns open/close and the `Phase`; screens, Gleo and audio go through it, never `StageEngine` directly. `endSong` only marks; the audio thread inserts `SongEnded`, so the director runs on one thread.
- The audio/effect boundary is `AudioEvent.kt`: audio sends `AudioEvent`s to `StageController.onAudio`, and a `Director` turns them into `CabinCommand`s.

### Other modules

- `app/src/debug/java/com/carstage/app/lab/`: developer screen, probes (`VehicleProbe`, `AudioProbe`, `PlatformProbe`, `StageProbe`, …) and replay (`WavReplay`, `LabReplay`). Debug only; tests in `app/src/testDebug/`. Product code referencing Lab classes breaks the release build.
- `app/src/main/java/com/carstage/app/gleo/`: `GleoActions` and `GleoRouter`. Changing a voice function means changing `app/src/main/assets/schema.json`, the manifest intent filters and the `GleoActions` constants together. Reply messages are Korean (Gleo may read them aloud).
- `tools/`: `mediatester` (test music app, not part of the submission APK), `playbacktap` (capture helper), `score-scenes` (score test scene mixer; songs and scenes are not committed).
- `scripts/`: setup, checks, emulator, `vconsole.sh`, `replay.sh`, `regress.sh`, `sound-effect.sh` (CC0 effects to mono -19 LUFS Ogg Opus in `res/raw`), `release-apk.sh`; shared code in `scripts/lib/env.sh`.
- `toolchain/`: the pinned environment (`android-sdk.lock`, `package-xml/`, `avd/config.ini`). To upgrade, change lock, XML and docs together and confirm `scripts/bootstrap.sh` and `scripts/doctor.sh --device` on a fresh setup.
- `docs/` (Korean): plan `docs/04`, emulator findings `docs/05`, convenience features `docs/07`, voice reaction and heat score `docs/09`, UI/UX `docs/10`, plan without car control `docs/11`, MC `docs/12`, basic flow `docs/13`; index `docs/README.md`.
- The team's test recordings (`recordings/`) are internal and not part of the published repository.

## Work rules

- Code, comments, commit messages, CLAUDE.md and `.claude/rules` are English. `docs/`, README.md and user-facing UI strings are Korean.
- After a change, run `scripts/check.sh`. For car or audio behavior, also run `scripts/regress.sh` and the relevant probes, and report from the logs.
- IMPORTANT: if you changed code (`app/`, `tools/`, `scripts/`, `toolchain/`, Gradle files), review with `/pr-review-toolkit:review-pr` before committing. It reads the uncommitted diff; register new files first with `git add -N <file>`.
    - Report unaddressed findings with reasons to the user and in the PR body.
    - Docs-only changes skip the code review but still get the merge judgment.
- Record new emulator facts (support, error codes, latencies) in `docs/05-emulator-findings.md` with the date.
- The submission APK is `apk/cold-box_CarStage_release.apk` (contest rule: `apk/<team>_<app>_release.apk`). No other `*.apk` is committed.
- The Pleos SDK license (42dot) forbids decompiling or reverse engineering the SDK. Learn the API only from the official docs, the public `Pleos-Playground` samples, and IDE completion.

## Branches and merging

- IMPORTANT: never commit or push to main directly. Each piece of work gets its own branch from the latest main: `git fetch <remote>`, `git switch -c <type>/<feature> <remote>/main` (type `feat`, `fix`, `docs` or `chore`; feature in lowercase English with hyphens). One feature per branch; merge branches that touch shared files early.
- Order: branch → implement → `scripts/check.sh` → (car/audio) `--device`, `regress.sh`, probes → review → fix → commit → merge judgment → push, `gh pr create --base main` → merge when the user says so.
- IMPORTANT: every review ends with a merge judgment, "머지 가능" or "머지 불가: <reason and fix>", reported to the user and written in the PR body:
    1. No conflicts: `git merge-tree --write-tree <remote>/main HEAD` is clean; `gh pr view --json mergeable,mergeStateStatus` says `MERGEABLE`.
    2. Up to date with main (otherwise rebase, rebuild, retest, `git push --force-with-lease`).
    3. `scripts/check.sh` passes.
    4. Car/audio changes verified on the emulator (`--device`, `regress.sh`, probe logs).
    5. Every Critical and Important review finding fixed, or the PR says why not.
    6. New facts and changed commands are in `docs/05`, README and CLAUDE.md.
    7. Nothing unrelated in the branch.
- Merge on the user's word: `gh pr merge <number> --squash --delete-branch` with an English subject; recheck 1 and 2 right before.
- A PR body lists what changed, how it was checked (commands, log summaries), unaddressed findings, and the merge judgment.

## Product principles

- Sing-along only: the app provides no music, accompaniment or lyrics; it reacts to songs the user plays in another app.
- Audio is processed on the device and discarded right after analysis, never stored or sent. The Lab's "5초 녹음 저장" is the only exception, for development.
- The app works only while parked (`GEAR_PARK`). When driving starts, the stage pauses, screen effects hide and the car is restored (writes refused while driving are retried once parked). No effects while driving. Exterior lights are never controlled.
- Assume car control (lights, climate, seats) is unavailable for judging and release (`docs/11` scenario D). Pleos rejects apps that declare unapproved permissions or call their APIs, so the release build declares no control permissions and contains no code that calls them: `stage/VehicleCabin` is in `app/src/cabinControl` (debug only), and the release build uses `app/src/noCabinControl` (permissions removed, `carCabin()` returns `null`). Once Pleos approves, set `releaseDrivesCabin` in `app/build.gradle.kts` to `true`. Describe car effects as turning on "without code changes once the permission agreement is done", never "without an update".
- Skip climate effects while the defroster or wipers run. Every car effect restores the pre-stage state.
- Launch suggestions read Android's playback state, never the mic; the mic turns on only after the user starts, and stops during calls. Suggestions come from the first screen and Gleo voice actions (`assets/schema.json`), not notifications.
- Pleos resource limits while parked: CPU ≤ 25%, RAM ≤ 400 MB. Effects request audio focus and stop on focus loss.
- Never claim "최초" (first). In-car karaoke and music-synced lighting already exist; the difference is that the car reacts to the passengers' voices and cheers.
- No location-based ranking, recording, saving or sharing, and no melody data for pitch grading. Results show only on the result screen.
