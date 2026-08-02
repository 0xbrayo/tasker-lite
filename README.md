# Tasker Lite

A focused Android app that replaces Tasker for one job: **listen to [Sleep as Android](https://sleep.urbandroid.org/) events and control a Xiaomi / Yeelight smart LED bulb** on your local Wi‑Fi.

Built for the **Xiaomi Smart LED Bulb – White and Color** (SKU **59875** / model **BHR9434GL**), and any other bulb that speaks the **Yeelight LAN** protocol.

## What it does

### Sleep as Android rules

| Sleep as Android event | Default light scene |
|---|---|
| Sleep tracking started | **If already on:** warm dim (2700K, 10%) → **off after 15 min**. If off, leave off. |
| Bedtime | Warm dim (2700K, 10%) |
| Alarm triggered (`ALARM_ALERT_START_AUTO`) | **30 min dawn** — log-lumen ramp (phase % = max lumens): 1% → 50% → 100% lm with linear CT (1700K → 6500K); Yeelight `start_cf` or miIO steps |
| Alarm dismissed (`ALARM_ALERT_DISMISS_AUTO`) | **Stop color flow + power off** (1s smooth) |

### Scheduled routines (sunset / fixed time)

| Routine | Default |
|---|---|
| **Sunset lights up** | At local **sunset** (from GPS/network location), **30 min warm ramp** 1%→70% (2000K→2700K) |

Open the **Routines** tab, grant location, and confirm today's sunset. Offset the start by ±15–60 minutes if you like. Routines use `AlarmManager` alarm-clock scheduling and re-arm after reboot.

Rules and routines are editable in the app. You can also turn the bulb on/off/dim from the Home tab.

## How it works

1. A **foreground service** keeps a **runtime** `BroadcastReceiver` registered for Sleep as Android intents (manifest-only receivers do not work for these implicit broadcasts on modern Android).
2. Matching rules control the bulb via:
   - **miIO / MIoT** (UDP **54321** + device **token**) — works with Xiaomi Smart LED Bulb `xiaomi.light.bulb` / BHR9434GL
   - **Yeelight LAN** (TCP **55443**) — when LAN control is available and no token is set
3. Add bulbs by **IP + token** (recommended for Xiaomi), or **Discover** Yeelight LAN devices.

No Tasker install is required. After setup, control is local (no cloud calls from this app).

## Prerequisites

- Android 8.0+ phone on the **same 2.4 GHz Wi‑Fi** as the bulb
- Sleep as Android installed (for real overnight use)
- Bulb paired and reachable on the LAN

### Xiaomi MIoT bulb (e.g. BHR9434GL / `xiaomi.light.bulb`)

1. Pair the bulb in **Xiaomi Home** and note its LAN IP (static DHCP recommended).
2. Extract the **32-character hex token** for each bulb (tokens are unique per device) with
   [PiotrMachowski/Xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor):

   ```bash
   curl -sL https://raw.githubusercontent.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor/master/token_extractor.py \
     -o /tmp/token_extractor.py
   python3 /tmp/token_extractor.py
   ```

   Log in with your Xiaomi account when prompted; the script prints each device’s IP and token.
3. In Tasker Lite → **Bulbs** → enter **IP** + **token** → **Add bulb** → **Test**.
4. MIoT properties used: power (2/1), brightness (2/2), CT (2/3), RGB (2/4).

Yeelight **developer mode is not available** on this model; control is miIO-only.

### Yeelight LAN bulbs

1. Enable **LAN Control** in the Yeelight app if present.
2. Use **Discover Yeelight** or add by IP without a token.

## Build & install

```bash
# From the repo root (JDK 17 + Android SDK required)
export ANDROID_HOME=$HOME/Library/Android/sdk   # or your SDK path
./gradlew :app:assembleDebug

# Install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in **Android Studio** and Run.

## First-run setup

1. Grant **notification** permission (needed for the foreground service).
2. Open **Bulbs** → **Discover**, or **Add by IP**.
3. Tap **Test** — you should see power/brightness props.
4. Open **Home** → enable **Automation service**.
5. In **Sleep as Android**: **Settings → Services → Automation** (enable Tasker/Automate integration — the app still broadcasts the same intents).
6. Optionally open **Rules** and change scenes (off / dim warm / wake / daylight).

## Simulate Sleep events (without sleeping)

With the automation service running:

```bash
# Bedtime / tracking started → dim warm
adb shell am broadcast -a com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STARTED_AUTO

# Alarm ringing → wake scene
adb shell am broadcast -a com.urbandroid.sleep.alarmclock.ALARM_ALERT_START_AUTO

# Alarm dismissed → daylight
adb shell am broadcast -a com.urbandroid.sleep.alarmclock.ALARM_ALERT_DISMISS_AUTO
```

Check the **Log** tab for results.

## Project layout

```
app/src/main/java/com/taskerlite/
  data/          # models + DataStore prefs
  yeelight/      # discovery + TCP client
  sleep/         # intent action map + receiver
  service/       # foreground service + rules engine
  ui/            # Compose screens
```

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` / Wi‑Fi | Talk to the bulb |
| `CHANGE_WIFI_MULTICAST_STATE` | Discovery |
| `FOREGROUND_SERVICE` (+ special use) | Keep receiver alive overnight |
| `POST_NOTIFICATIONS` | Service notification |
| `RECEIVE_BOOT_COMPLETED` | Restart service + reschedule routines after reboot |
| `ACCESS_COARSE/FINE_LOCATION` | Approximate location for sunset/sunrise times |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Fire routines at the computed solar time |

## License

Personal / MIT-style — use freely. Not affiliated with Xiaomi, Yeelight, or Urbandroid.
