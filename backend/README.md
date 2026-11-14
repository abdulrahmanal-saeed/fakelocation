# WayShift service configuration

## Android build

Use the Play application ID for Google Play and the original application ID for the GitHub build. Both are registered in Firebase project `wayshift-navigation`.

Set the following Gradle properties or process environment variables before building. Download the Firebase Android SDK configuration for the selected application ID and use its Android app ID, API key and OAuth client of type 3. Register the Google Play app-signing SHA-1 and SHA-256 in Firebase for the Play application.

| Setting | Value |
| --- | --- |
| `FIREBASE_PROJECT_ID` | `wayshift-navigation` |
| `FIREBASE_APP_ID` | Android `mobilesdk_app_id` |
| `FIREBASE_API_KEY` | Android `current_key` |
| `FIREBASE_WEB_CLIENT_ID` | Web OAuth client ID |
| `WAYSHIFT_API_URL` | `https://wayshift.wayshift.workers.dev` |
| `PHOTON_URL` | HTTPS base URL of the production Photon server |
| `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_REWARDED_ID` | Play application and ad units from AdMob |

Build the debug APK with `gradlew assembleDebug -PwayshiftPlay=true`. Build the Play AAB with `gradlew bundleRelease -PwayshiftPlay=true`. Supply `PLAY_UPLOAD_STORE`, `PLAY_UPLOAD_ALIAS`, `PLAY_UPLOAD_PASSWORD` and `PLAY_UPLOAD_KEY_PASSWORD` to sign the AAB. The GitHub signing key is for that distribution only.

## Cloudflare and Firebase

Run `wrangler d1 migrations apply wayshift --remote --config backend/wrangler.toml` and `wrangler deploy --config backend/wrangler.toml` from the repository root. Set `RATE_SALT`, `RESEND_API_KEY` and `PLAY_SERVICE_ACCOUNT` as Worker secrets. The last value is the JSON for the service account authorized to verify subscriptions for the Play app.

Set `FEEDBACK_FROM` to an address at a verified Resend domain before launch. The current sender is the Resend sandbox address. Set `ADMOB_REWARDED_ID` to the AdMob rewarded unit, and enter `https://wayshift.wayshift.workers.dev/v1/admob/reward` as its server-side verification URL. The app passes the challenge as custom data. Set `MODERATOR_EMAIL` to the verified Firebase account authorized to remove reports.

In Play Console, create subscription `wayshift-plus`, with auto-renewing base plans `weekly` and `monthly`. The planned US prices are $9.99 and $33.99. The app displays the price returned by Play and uses base plans without promotional offers. Verify checkout, restore, expiry and rewarded credits in an internal track before publishing.

Deploy `firebase deploy --only auth,firestore:rules,hosting --config cloud/firebase.json --project wayshift-navigation`. The public account deletion page is at `https://wayshift-navigation.web.app/account-deletion`, and the moderation page is at `https://wayshift-navigation.web.app/moderation`.

## Verification

Run `node --test backend/worker.test.mjs` and `gradlew testDebugUnitTest`. Run `python tools/route_coverage.py` for the public route samples. With `HERE_API_KEY` set locally, the report adds the share of route distance with a numeric HERE speed limit. Field testing must compare that data to posted signs and check both directions, especially exits and parallel roads.
