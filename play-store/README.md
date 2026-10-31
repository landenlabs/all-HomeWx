# Google Play assets for All HomeWx

| File | Play Console slot | Spec |
|---|---|---|
| `icon-512.png` | App icon | 512x512 PNG, no alpha, < 1 MB |
| `feature-graphic-1024x500.png` | Feature graphic | 1024x500, no alpha |
| `screenshots/tablet-10/*.png` | 10-inch tablet screenshots | 2304x1296, 16:9, no alpha |
| `screenshots/tablet-7/*.png` | 7-inch tablet screenshots | 1920x1080, 16:9, no alpha |
| `listing.md` | Title, short + full description | within Play length limits |
| `privacy-policy.md` | Privacy policy URL | must be hosted at a public URL |

Sources: `icon.svg` (geometry copied from `ic_launcher.xml`) and `feature-graphic.html`. Re-render with headless Chrome at 512x512 and 1024x500 (`--force-device-scale-factor=1`), then strip alpha (`magick in.png -alpha off out.png`).

Screenshots were captured from a Galaxy Tab S9 FE (SM-X510) and cropped to remove the status and nav bars. Play wants 2-8 per slot; there are 7. Sensor names (Attic, Basement, ...) are visible in them.

## Still needed before you can publish (not generated)
1. **Host the privacy policy** (e.g. GitHub Pages) and fill in the contact email placeholder.
2. **Release signing**: `app/build.gradle.kts` has no `signingConfigs`. Create an upload keystore (`*.jks` is gitignored) and build with `./gradlew bundleRelease`. Play requires an `.aab`. Enrol in Play App Signing.
3. **Release build secrets**: Govee and Wyze credentials are now empty in release builds (users enter them in Settings > Accounts & Feeds); only debug builds read them from `local.properties`. `build.gradle.kts` still bakes the `NEST_*` values (client secret, refresh token) and `SUN_API_KEY` into every build, so those are extractable from a published bundle. `NestApiClient` is not used anywhere yet - blank the `NEST_*` fields for release before shipping.
4. **Tablet only**: in Play Console go to Release > Setup > Device catalog (or Device exclusions) and exclude phones. The manifest has no screen-size restriction today. Also consider `isMinifyEnabled = true`, which is currently off.
5. **Console declarations**: Data safety form (location, Firebase analytics and crash data), foreground service declaration for `dataSync`, content rating, target audience (not children), and a justification for `ACCESS_FINE_LOCATION` (Wi-Fi name only) and `RECEIVE_BOOT_COMPLETED`.
6. **Package name**: the `applicationId` is `com.landenlabs.all_homewx`. It is permanent once uploaded.
7. New personal developer accounts must run a closed test (12 testers for 14 days) before applying for production.
