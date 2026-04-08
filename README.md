# DoorCam

A Kotlin / Android application that repurposes an old phone — even one with a
**broken touchscreen** — into a Ring-style doorbell viewer. The phone stays
mounted on a wall near the front door, runs a continuous camera preview with
**on-device motion detection**, and auto-records short clips (with pre-roll) to
the phone's Gallery whenever motion is detected. The live feed is viewed and
controlled remotely from a Mac (or any computer) using [`scrcpy`][scrcpy], so
the phone's physical panel can stay blanked while the OS stays awake — which
is essential when the touchscreen glass is cracked and firing ghost taps.

> **Origin story**: I had a Huawei Honor 20 Lite with a shattered touchscreen
> and a perfectly working rear camera. Instead of throwing it out I stuck a
> fish-eye clip-on lens over the back camera, mounted the phone by my front
> door, and wrote this app so I could watch the door from my Mac.

## Features

- **Continuous live preview** mirrored to your desktop via `scrcpy`
  (`--turn-screen-off --stay-awake`) — the phone's backlight stays off so it
  draws less power and, crucially, doesn't generate ghost-touch events from a
  damaged touchscreen.
- **On-device motion detection** (no cloud, no Google Photos upload).
  Lightweight Y-plane frame-difference scoring with a configurable threshold
  and N-frame confirmation.
- **Rolling pre-roll ring buffer**. The camera feeds a continuously running
  `MediaCodec` H.264 encoder whose output (encoded NAL units + timestamps) is
  kept in memory for the last 5 seconds. When motion fires, a `MediaMuxer` is
  opened, the ring is flushed starting at the most recent keyframe, and live
  frames continue to be appended until ~8 seconds after motion has stopped —
  so every saved clip naturally includes the moments **before** the person
  approached, not just after.
- **Clips saved to `/sdcard/Movies/DoorCam/`** which is a default Android
  Gallery scan path, so clips appear instantly in the phone's Gallery app
  without a reboot or manual refresh.
- **Manual record button** on the on-screen overlay, using the same ring
  buffer pipeline so manual captures also include pre-roll.
- **Aspect-correct, rotation/flip-adjustable preview**. The TextureView is
  resized to a letterbox rectangle preserving the camera's 4:3 aspect (no
  stretching), and on-screen **ROT** / **FLIP** buttons let you cycle
  rotation (0°/90°/180°/270°) and toggle vertical mirror to match however the
  phone ends up physically mounted.
- **Hardware digital zoom** into the fish-eye circle using Camera2's
  `SCALER_CROP_REGION` (ISP crop, no software scaling) so the fish-eye view
  fills the frame instead of being a tiny bright circle surrounded by black
  vignette.
- **Camera ID selector.** Most phones have multiple rear cameras (main,
  ultrawide, depth, macro) at different focal lengths. DoorCam enumerates
  every back-facing camera on startup and lets you pick which one to use.
- **Status overlay** with live battery %/charging indicator, last-motion age,
  current motion score, recording state, and current adjustment config.
- **Foreground `Service`** so Camera2 stays usable while the physical display
  is blanked (Android 10+ camera background-access restriction).
- **Persistent config** via SharedPreferences — every adjustment (rotation,
  flip, zoom, crop center, motion threshold, camera ID) survives app
  restarts, reboots, and reinstalls (though a fresh `pm clear` will reset).
- **No audio recorded** and **no sound played**, by design. Strictly silent.

## Not yet implemented (see [Roadmap](#roadmap))

- Mac-side push notifications when motion fires (ntfy / Telegram)
- `BOOT_COMPLETED` receiver so the app auto-starts on phone reboot
- One-shot EMUI setup script (disable `com.huawei.powergenie`, deviceidle
  whitelist, stay-on-while-plugged)
- ML-based person/vehicle filter (e.g. MLKit object detection) to suppress
  trivial motion (shadows, wind, pets)
- Live HTTP preview endpoint inside the service so a browser on the LAN can
  view the feed without needing `scrcpy`
- ffmpeg post-processing to pre-flip saved clips so playback matches the
  flipped preview

## Hardware requirements

- An Android 10+ phone with a working rear camera. The touchscreen can be
  **completely broken** — that was the whole point.
- A clip-on fish-eye or wide-angle lens (optional but helpful for door
  coverage).
- A USB cable (or Wi-Fi reachability) between the phone and the computer you
  want to control it from.
- Somewhere to mount the phone that has an outlet nearby — running Camera2
  at 20 fps + H.264 encoding + Wi-Fi burns through a battery in ~5 hours.

## Software requirements

On the host (Mac, Linux, or Windows — this project was developed on macOS):

| Tool              | Used for                                              |
| ----------------- | ----------------------------------------------------- |
| [`adb`][adb]      | Installing the APK, driving the device, logcat       |
| [`scrcpy`][scrcpy] 3.x | Mirroring the phone's display with the panel blanked |
| JDK 17            | Building with AGP 8.5                                 |
| Android SDK 34    | compileSdk / targetSdk                                |
| Gradle Wrapper    | Included — just run `./gradlew`                       |

On the phone:

- Developer options → USB debugging enabled
- Huawei devices: disable `com.huawei.powergenie` (see [EMUI quirks](#emui-quirks))

## Quick start

```bash
# 1. Clone
git clone https://github.com/seanito14/doorcam.git
cd doorcam

# 2. Point at your Android SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
# or: echo "sdk.dir=/home/you/Android/Sdk" > local.properties   # Linux

# 3. Build
source scripts/env.sh     # exports JAVA_HOME=openjdk@17
./gradlew :app:assembleDebug

# 4. Install on a connected phone
adb install -r -g app/build/outputs/apk/debug/app-debug.apk

# 5. Launch
adb shell am start -n com.z.doorcam/.ViewerActivity

# 6. Mirror with scrcpy, with the panel blanked and stay-awake on
scrcpy --turn-screen-off --stay-awake
```

That's enough to see the live camera feed in a window on your desktop, with
motion detection running and auto-recording to the phone's Gallery.

## Wireless adb (optional, recommended for permanent mounting)

Once the phone is mounted by the door you typically don't want a USB cable
running across the room. Android 10's classic TCP-mode flow:

```bash
# With USB plugged in, bootstrap TCP mode
adb tcpip 5555

# Get the phone's IP
PHONE_IP=$(adb shell ip -f inet addr show wlan0 | awk '/inet /{print $2}' | cut -d/ -f1)

# Connect over Wi-Fi
adb connect $PHONE_IP:5555

# Now unplug USB — adb still works over Wi-Fi
scrcpy -s $PHONE_IP:5555 --turn-screen-off --stay-awake &
```

⚠️ **Caveat**: on non-rooted devices (including every stock Huawei), TCP mode
resets to USB on every reboot. After a reboot, plug USB in once to re-issue
`adb tcpip 5555`, then unplug. The DoorCam app itself runs continuously, so
this only matters if you want `scrcpy` access after a power cycle.

## Runtime configuration

Every adjustment is controlled by **Intent extras** that are also persisted
to SharedPreferences, so a setting applied once sticks. The helper script
`scripts/cfg.sh` wraps the common ones:

```bash
# View orientation (rotation and flip)
./scripts/cfg.sh rot 90             # rotate 0 / 90 / 180 / 270
./scripts/cfg.sh flipv on           # mirror top↔bottom
./scripts/cfg.sh fliph on           # mirror left↔right

# Hardware digital zoom on the fish-eye circle
./scripts/cfg.sh zoom 2.5           # 1.0 = no zoom, up to SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
./scripts/cfg.sh crop 0.5 0.5       # crop center (x, y) as fractions of sensor active array

# Motion detection sensitivity
./scripts/cfg.sh thresh 18          # 1 = hair-trigger, 40+ = only obvious movement

# Switch physical camera (useful when the fish-eye is over the ultrawide)
./scripts/cfg.sh cam 3

# Grab a quick PNG screenshot of the current preview
./scripts/cfg.sh shot               # → /tmp/doorcam_now.png

# Nuclear reset (clears SharedPreferences)
./scripts/cfg.sh reset
```

The activity also has three tappable on-screen buttons stacked at the
bottom-right of the view (over the letterbox black bar, so they don't cover
the fish-eye):

| Button     | Action                                      |
| ---------- | ------------------------------------------- |
| `↻ <deg>°` | Cycle rotation 0° → 90° → 180° → 270° → 0° |
| `↕ FLIP`   | Toggle vertical flip (✓ appears when active) |
| `● REC`    | Manually start / stop a pre-roll-included recording |

Taps are delivered via `scrcpy`'s touch-forwarding (or `adb shell input tap`
for headless control) so the broken physical panel is irrelevant.

## Architecture

```
┌─────────────────────────── Phone: com.z.doorcam ─────────────────────────┐
│                                                                           │
│  DoorCamService (foreground, sticky notification)                         │
│    └─ elevates process state so Camera2 is allowed with screen blanked   │
│                                                                           │
│  ViewerActivity  (sensorLandscape, singleTask)                            │
│    ├─ TextureView  ◄── Camera2 session  ── preview surface                │
│    │                                     └ motion surface (ImageReader)  │
│    │                                     └ encoder input (MediaCodec)    │
│    │                                                                     │
│    ├─ MotionDetector: downsample Y plane → 80×60 grid → sum-abs-diff     │
│    │    → threshold + N-frame confirm → emit onMotionStart/onMotionEnd    │
│    │                                                                     │
│    └─ RecordingController:                                                │
│         IDLE ── onMotionStart ── open MediaMuxer → flush ring preroll     │
│                       │                         → write live frames      │
│                       ▼                                                   │
│                  RECORDING ── onMotionEnd ── start 8s hold timer          │
│                       ▲                          │                        │
│                       └───── new motion during hold                       │
│                                                  │                        │
│                                                  ▼                        │
│                                             close muxer                   │
│                                             MediaScanner.scanFile         │
│                                             /sdcard/Movies/DoorCam/       │
│                                                                           │
│  VideoRingBuffer: MediaCodec H264 encoder with input Surface              │
│    ├─ drain thread dequeues output NAL units                              │
│    ├─ keeps last PREROLL_SECONDS of frames in an ArrayDeque               │
│    ├─ publishes outputFormat (with CSD SPS/PPS) once available            │
│    └─ snapshotFromLastKeyframeInRange() for preroll flush                 │
└───────────────────────────────────────────────────────────────────────────┘
              ▲                                     │
              │ scrcpy mirrors the rendered surface │ motion events
              │ (physical panel off)                │ (future M3)
              │                                     ▼
         ┌──────────────┐                    ┌──────────────┐
         │  Your Mac    │                    │  ntfy/Mac    │
         │  (scrcpy)    │                    │  (not yet)   │
         └──────────────┘                    └──────────────┘
```

Four Kotlin files make up the app:

| File | Purpose |
|---|---|
| `ViewerActivity.kt` | The only activity. Owns Camera2, the TextureView, the motion detector, and the recording controller. Handles lifecycle, Intent-extra config, SharedPreferences persistence, on-screen buttons. |
| `DoorCamService.kt` | Minimal foreground service that holds a persistent notification. Exists solely to elevate the app's process state past Android 10's camera-background-access restriction so the camera still works while the physical display is blanked. |
| `MotionDetector.kt` | Downsamples the Y plane of each `Image` into a coarse grid, computes sum-of-absolute-differences vs the previous grid, normalises to 0–255 average per cell, thresholds with N-frame confirmation. |
| `VideoRingBuffer.kt` | `MediaCodec` H.264 encoder that runs continuously. Input is a Surface handed to Camera2; output is a background thread that dequeues encoded NAL units into a time-bounded `ArrayDeque`. Exposes the output `MediaFormat` (with CSD SPS/PPS blobs) and `snapshotFromLastKeyframeInRange()` for preroll flushing. |
| `RecordingController.kt` | State machine (IDLE / RECORDING / HOLDOFF) that owns `MediaMuxer` lifecycles. Receives frames from the ring buffer, writes them to the muxer during active recording, schedules the 8 s hold timer, runs `MediaScannerConnection.scanFile` on close. |

## EMUI quirks

If your phone is a Huawei running EMUI, two extra setup steps are required to
stop Huawei's aggressive power management from freezing the service.
Neither requires root.

```bash
# Disable PowerGenie — Huawei's daemon that aggressively freezes background
# apps when the screen is off. This is the single biggest cause of
# "the app was running a minute ago and now it's gone" on EMUI.
adb shell pm disable-user --user 0 com.huawei.powergenie

# Add DoorCam to the Doze / battery-optimization whitelist.
adb shell dumpsys deviceidle whitelist +com.z.doorcam

# Keep the device awake while plugged in (all charger types)
adb shell settings put global stay_on_while_plugged_in 7
```

These persist across reboots. Without them, you'll see the app survive for a
few minutes and then get zapped with a `PG_ash ... F_Z com.z.doorcam OK !` in
logcat.

## Roadmap

- [x] **M1** Camera2 with `screen-off` proven (TextureView + ImageReader + encoder input Surface on a single session)
- [x] **M2** MotionDetector + ring-buffer recording + MediaMuxer + Gallery scan
- [x] **M2.5** UI polish: aspect-correct letterbox, landscape support, ROT/FLIP/REC on-screen buttons, config persistence, status overlay
- [ ] **M3** ntfy.sh push notification on motion, Mac `launchd` agent → `osascript display notification`
- [ ] **M4** `BOOT_COMPLETED` receiver + setup-emui.sh + 24 h soak test
- [ ] **M5** ML person/vehicle filter (MLKit), live HTTP preview, `adb shell am broadcast` control surface, ffmpeg post-flip helper

## Credits

- **[scrcpy][scrcpy]** by [Genymobile][genymobile] — the screen-mirroring tool
  that makes remote-controlling a broken-screen phone feel completely natural.
  DoorCam itself doesn't bundle or modify scrcpy; it just assumes you have
  scrcpy 3.x installed on your host (`brew install scrcpy` on macOS) and uses
  its `--turn-screen-off` and `--stay-awake` flags as the blanking mechanism.
  Released under the Apache 2.0 license.
  Repository: <https://github.com/Genymobile/scrcpy>
- **[adb][adb]** / `platform-tools` — part of the Android SDK.
- **[Camera2 Basic sample][camera2basic]** by Google Android — reference for
  the Camera2 + TextureView + ImageReader + MediaCodec pipeline patterns.

## License

[MIT](./LICENSE) — © 2026 Sean Ito ([@seanito14](https://github.com/seanito14))

[scrcpy]: https://github.com/Genymobile/scrcpy
[genymobile]: https://github.com/Genymobile
[adb]: https://developer.android.com/tools/adb
[camera2basic]: https://github.com/android/camera-samples
