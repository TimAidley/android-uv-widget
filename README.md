# UV Widget

A small Android home-screen widget showing the predicted UV index for your current location.

The widget is a single coloured tile with the UV index in black:

- **Blue** while the sun is below the horizon.
- **Green → yellow → orange → red** through the day, as the UV index rises from low to extreme.
- **Grey** before the first forecast arrives, or if no location has been set.

Tap the widget to refresh it immediately.

## How it works

| Concern | Approach |
| --- | --- |
| UV data | [Open-Meteo air-quality API](https://open-meteo.com/en/docs/air-quality-api) — free, no API key, nothing to register |
| Sunrise / sunset | Calculated on device from the NOAA solar equations, so the colour stays correct even while showing cached data |
| Location | Platform `LocationManager` (no Google Play Services), with a manually entered fallback |
| Refresh | WorkManager, every 30 minutes, plus on tap and when the widget is added |
| Widget | Classic `AppWidgetProvider` + `RemoteViews` — no Compose, so the APK stays small |

The forecast is fetched hourly and interpolated between hours, so the colour drifts smoothly
rather than jumping on the hour.

### About location and background access

From Android 10, an app can only read your location while it is in the foreground unless it holds
the special background-location permission. Rather than ask for that, this app captures a fix
while its settings screen is open and the widget's background refresh reuses it. Open the app
again after travelling a long way, or set a fixed location by hand.

## Building

Requires the Android SDK (platform 35) and JDK 17.

```bash
./gradlew assembleDebug          # build the APK
./gradlew installDebug           # install onto a connected device or emulator
./gradlew test                   # run the unit tests
```

Then long-press the home screen, pick **UV Index** from the widget list, and choose a location
when the settings screen appears.

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
- what the widget decides to show: cached, stale, expired, wrong-location, and unconfigured.
