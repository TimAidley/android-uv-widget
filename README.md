# UV Widget

A small Android home-screen widget showing the predicted UV index for your current location.

The widget is a single coloured tile with the UV index in black:

- **Blue** while the sun is below the horizon.
- **Green → yellow → orange → red** through the day, as the UV index rises from low to extreme.
- **Grey** before the first forecast arrives, or if no location has been set.

Tap the widget to open its settings, which also refreshes your location and shows the day's UV
curve.

## The graph

The settings screen opens with twelve hours of UV index drawn as a curve, filled underneath with
the same colours the widget uses, so the shape and the colour say the same thing.

- The **hourly forecast points are joined with a monotone cubic curve**, not straight lines, so
  the shape matches the interpolated value the widget actually reports between hours.
- **A vertical line marks now**, with the current UV index beside it.
- **Touch and drag** anywhere on the graph for a dotted line and the UV index at that moment. It
  disappears when you lift your finger.
- **Hours are marked every three hours**, on the clock, in your device's 12- or 24-hour format.
- The **vertical axis fits the day's peak**, but never tops out below 4 — otherwise a winter peak
  of 1 would be magnified into a mountain.

Twelve hours is less than a day, so the window has to be placed. It is chosen to contain as much
daylight as possible — the dark hours are a flat line at zero and say nothing — subject to the
"now" line never coming closer than a quarter of the width to either edge, which always leaves
room to see what is coming. Where those two conflict, the edge rule wins.

## How it works

| Concern | Approach |
| --- | --- |
| UV data | [Open-Meteo air-quality API](https://open-meteo.com/en/docs/air-quality-api) — free, no API key, nothing to register. Yesterday plus two forecast days, so the graph has hours on both sides of now wherever you are |
| Graph | A custom `View` drawn on a `Canvas`, sampled once per pixel column rather than joined point to point |
| Sunrise / sunset | Calculated on device from the NOAA solar equations, so the colour stays correct even while showing cached data |
| Location | Platform `LocationManager` (no Google Play Services), approximate only, with a manually entered fallback |
| Place name | `Geocoder`, for display in the settings screen only — the widget never depends on it |
| Refresh | WorkManager, every 30 minutes, and when the widget is added |
| Widget | Classic `AppWidgetProvider` + `RemoteViews` — no Compose, so the APK stays small |

The forecast is fetched hourly and interpolated between hours, so the colour drifts smoothly
rather than jumping on the hour.

### About location and background access

From Android 10, an app can only read your location while it is in the foreground unless it holds
the special background-location permission. Rather than ask for that, this app captures a fix
while its settings screen is open and the widget's background refresh reuses it. Open the app
again after travelling a long way, or set a fixed location by hand.

That is why the settings screen goes after a fix as soon as it opens, rather than waiting to be
asked: being open is the entire opportunity to get one. It shows the location the system already
has immediately and upgrades it when a fresh fix arrives, so there is nothing to wait for in the
common case; a fresh fix is given ten seconds before it falls back to the last known one.

### Approximate location

The app requests `ACCESS_COARSE_LOCATION` only, so the permission dialog offers **Approximate**
with no precise option. Android takes the real fix and degrades it in software — a random offset
regenerated roughly hourly, snapped to a ~2 km grid — rather than switching to a coarser way of
sensing. Nothing is lost here: UV forecasts vary over tens of kilometres, so 2 km of fuzz sits far
below the resolution of the data.

Note that this is a privacy choice, not a battery one. Power depends on which provider is asked,
not which permission is held, which is why `LocationSource` prefers `NETWORK_PROVIDER` (wifi and
cell lookup) over GPS.

## Building

Requires the Android SDK (platform 35) and JDK 17.

Targeting SDK 35 opts into Android 15's edge-to-edge enforcement, so the settings screen sets
`fitsSystemWindows` on its root: without it the window draws behind the status and action bars and
the first heading disappears underneath them.

```bash
./gradlew assembleDebug          # build the APK
./gradlew installDebug           # install onto a connected device or emulator
./gradlew test                   # run the unit tests
```

Then long-press the home screen, pick **UV Index** from the widget list, and allow location access
when the settings screen appears. The settings screen shows the detected place name alongside the
coordinates. To pin the widget to a fixed point instead, tick **Set my location manually** and
enter coordinates; Save refuses, and says why, if it has no location to use.

## Continuous integration

`.github/workflows/ci.yml` runs on every push to `main`, on every pull request, and on demand from
the Actions tab. It runs the unit tests, builds the debug APK, and uploads both the APK and the
test reports as artifacts — the reports being far more use than the log when something fails.

## Releasing

Releases are built by `.github/workflows/release.yml` when a `v*` tag is pushed.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and commit.
2. Tag and push:

   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```

The workflow then checks the tag matches `versionName` and **fails if it does not**, so a
forgotten bump cannot ship a `v1.1.0` tag containing an APK calling itself 1.1.0's predecessor. It
runs the tests, builds a signed release APK, verifies with `apksigner` that it really is signed,
and publishes a GitHub Release with the APK attached and generated notes.

The version is shown at the bottom of the settings screen, name and code both — the code is what
distinguishes two builds that call themselves the same version.

### Signing

Release signing comes from four repository secrets. The build reads them from the environment, so
no key material is ever in the repository:

| Secret | What it is |
| --- | --- |
| `KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `KEYSTORE_PASSWORD` | Password for the keystore |
| `KEY_ALIAS` | Alias of the key inside it |
| `KEY_PASSWORD` | Password for that key |

To create a keystore:

```bash
keytool -genkeypair -v -keystore uv-widget.jks -alias uv-widget \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 uv-widget.jks    # paste into the KEYSTORE_BASE64 secret
```

**Keep the keystore safe and backed up.** Android identifies an app by its signing key, so a
release signed with a different key cannot be installed over an existing one — the upgrade is
refused and the app has to be uninstalled first, losing its settings. The GitHub secret is a
working copy, not a backup; it cannot be read back out.

Without those secrets, `assembleRelease` still works locally and produces an unsigned APK rather
than failing, which keeps the release build path testable without the key.

## Colour ramp

| UV index | Colour | |
| --- | --- | --- |
| Sun below horizon | `#2196F3` | blue |
| 0 | `#4CAF50` | green |
| 3 | `#FFEB3B` | yellow |
| 6 | `#FF9800` | orange |
| 8 | `#FF5722` | deep orange |
| 11+ | `#E53935` | red |

Values in between are blended, so UV 4.5 sits between yellow and orange. Every colour in the
ramp keeps at least 4.5:1 contrast against the black text, which is checked by a unit test.

## Tests

`./gradlew test` runs JVM unit tests (JUnit + Robolectric) covering:

- the solar elevation calculation, against published sunrise/sunset times for London, New York,
  Sydney and Tromsø, including polar day and polar night;
- the colour ramp — direction, continuity, and black-text contrast;
- Open-Meteo response parsing, including hours the API returns as `null`;
- what the widget decides to show: cached, stale, expired, wrong-location, and unconfigured;
- the graph's curve — that it passes through the hourly points, stays monotone between them, and
  never dips below zero, which a plain cubic spline does around sunrise;
- the graph's twelve-hour window — that it keeps "now" out of the outer quarter at every hour of
  the day, shifts towards the daylight, and centres itself through polar day and polar night.
