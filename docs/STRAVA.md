# Strava upload

Aravia sends the band's workouts to the person's Strava account with no SDK and no server of
its own (`ios/Freeband/Integrations/Strava.swift`, screen in `UI/Settings/StravaView.swift`).

## The Strava app

Registered at strava.com/settings/api by the maintainer:

- Category: mobile app. Authorization callback domain: `localhost`.
- The client ID and secret are repository secrets `STRAVA_CLIENT_ID` and `STRAVA_CLIENT_SECRET`.
  The IPA workflow passes them to `tools/build-ipa.sh`, which hands them to `xcodebuild` as build
  settings; `project.yml` puts them into Info.plist (`StravaClientID`, `StravaClientSecret`). A build
  without them says so in Settings › Strava and sends nothing.
- An app with no server has to carry its secret, so it can be read out of the app. That is why
  the repository and its build artifacts stay private.
- A new Strava API app allows one connected athlete (its owner) until Strava reviews it.

## Sign-in

OAuth 2 in `ASWebAuthenticationSession`:

1. `https://www.strava.com/oauth/mobile/authorize`, scope `read,activity:write`, redirect
   `aravia://localhost/strava` (the app's own `aravia` scheme; the host must match the callback
   domain), with a random `state`.
2. The callback carries `code` and the granted `scope`. Without `activity:write` the app asks
   again, since the person can untick it on Strava's page.
3. `POST https://www.strava.com/oauth/token` with the code, client ID and secret. The answer has
   `access_token`, `expires_at` (epoch seconds), `refresh_token` and `athlete`.
4. Every refresh (`grant_type=refresh_token`) may hand back a new refresh token. The newest one is
   kept in the Keychain (`strava-refresh-token`). A refused refresh disconnects; being offline
   doesn't.
5. Disconnect calls `POST /oauth/deauthorize`.

## What is sent

One item per workout: a GPS track (activity file type 1, detail type 2), a summary from the band
(`Workout`), or both joined when they start within 5 minutes of each other on the same band.

- **With a GPS track:**
  1. `POST /api/v3/uploads` (multipart, `data_type=gpx`, `external_id=aravia-<start epoch>`).
  2. Poll `GET /uploads/{id}` every 2 s until it has an `activity_id`. An error that says
     `duplicate of …/activities/<id>` counts as sent.
  3. A GPX has no sport, so `PUT /activities/{id}` then sets `sport_type` and `trainer`.
- **Without a GPS track:** `POST /api/v3/activities` (manual): `start_date_local`, `elapsed_time`
  (end − start), `distance` when the band has it, `trainer` for indoor sports.
- **The description** has the average and maximum heart rate, active calories and steps from the
  summary, then "Enviado pelo Aravia".

Sport types: outdoor run → Run, trail run → TrailRun, treadmill → Run with trainer, walks → Walk,
hike and trek → Hike, rides → Ride (indoor with trainer), pool → Swim, elliptical, rowing, HIIT,
anything else → Workout.

## When

- **After each sync** (`AppModel.sessionEnded`, once parsing has finished), if "Enviar treinos
  novos sozinho" is on: workouts that started after Strava was connected and are not sent yet.
- **An outdoor workout without its track** waits up to a day, in case the track comes in a later
  sync, then goes as a manual activity.
- **Older workouts** go one at a time from the workout's or the route's screen.
- **What was sent** (start time and activity id) is kept on the phone (`aravia.stravaSent`), so a
  workout is never sent twice and its screen links to it on Strava.

## Android

`android/app/src/main/java/app/aravia/integrations/Strava.kt` does the same:
- **Sign-in** opens the browser, which comes back through an intent filter for
  `aravia://localhost/strava`.
- **The refresh token** is sealed with a Keystore key.
- **The keys** are `BuildConfig.STRAVA_CLIENT_ID` and `STRAVA_CLIENT_SECRET`, from the same
  repository secrets in the APK workflow.

It sends:
- the GPS routes in `RouteStore` as GPX;
- the workouts logged in Aravia (treadmill → Run with trainer, gym → WeightTraining, other →
  Workout) as manual activities, with each exercise's sets and the heart rate in the
  description.

Both apps also send the workouts logged in Aravia (treadmill, gym, other) as manual activities,
after a workout ends and with the next "send what's missing".

Not yet: heart rate per point in the GPX (the workout detail files are not decoded).
