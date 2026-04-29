# Analytics Setup

This project now sends usage batches to a Google Apps Script web app instead of GitHub Actions. End users do not need to configure anything if you include a ready `analytics.properties` file in the release package.

## Files in this repo

- `analytics.properties.template`
- `google_apps_script/UsageAnalyticsWebApp.gs`

## 1. Create the Google Sheet

1. Create a new Google Sheet.
2. Give it a clear name such as `BotGetLog Usage Analytics`.
3. Copy the spreadsheet ID from the URL.

Example URL:

```text
https://docs.google.com/spreadsheets/d/SPREADSHEET_ID_HERE/edit
```

The part between `/d/` and `/edit` is the spreadsheet ID.

## 2. Create the Apps Script web app

1. Open the Google Sheet.
2. Click `Extensions > Apps Script`.
3. Replace the default code with the contents of `google_apps_script/UsageAnalyticsWebApp.gs`.
4. Update these constants in the script:
   - `SPREADSHEET_ID`
   - `EXPECTED_API_KEY`
   - `TIME_ZONE`
5. Click `Deploy > New deployment`.
6. Deployment type: `Web app`.
7. Execute as: `Me`.
8. Who has access: `Anyone`.
9. Copy the deployed web app URL. It ends with `/exec`.

Google's official deployment guide:

- [Apps Script Web Apps](https://developers.google.com/apps-script/guides/web?hl=en)

## 3. Configure the desktop app one time before release

1. Copy `analytics.properties.template` to `analytics.properties`.
2. Fill in your real values.

Example:

```properties
enabled=true
endpointUrl=https://script.google.com/macros/s/DEPLOYMENT_ID/exec
apiKey=change-me
maxBatchSize=20
debug=false
```

3. Keep `analytics.properties` in the project root.
4. Build or release the project normally.

`build.xml` copies the real `analytics.properties` file into `dist` automatically, so end users do not need to create or edit it themselves.

## 4. Where you will see the results

The Apps Script writes to these tabs in the Google Sheet:

- `analytics_events`
- `daily_summary`
- `monthly_summary`
- `version_summary`

## 5. Quick test

1. Put a real `analytics.properties` in the project root.
2. Build the project.
3. Open the generated app from `dist`.
4. Confirm rows appear in `analytics_events`.
5. Confirm the summary tabs update.

## 6. Debugging

If you want local debug logs during testing:

```properties
debug=true
```

The desktop app then writes analytics debug logs to:

```text
_output\Analytics\analytics.log
```
