const SPREADSHEET_ID = '1W0Y8KxoAZJvhuWriyEyUT5wATtXTM33NDtUYQ_rv_rAZhd9ixoLBKYhU';
const EXPECTED_API_KEY = 'BotGetLog-2026-Secret';
const TIME_ZONE = 'Asia/Bangkok';
const EVENTS_SHEET_NAME = 'analytics_events';
const DAILY_SUMMARY_SHEET_NAME = 'daily_summary';
const MONTHLY_SUMMARY_SHEET_NAME = 'monthly_summary';
const VERSION_SUMMARY_SHEET_NAME = 'version_summary';

const EVENTS_HEADERS = [
  'received_at',
  'source',
  'event_id',
  'event_type',
  'tool_name',
  'app_version',
  'machine_id',
  'started_at',
  'queued_at',
  'event_date',
  'event_month'
];

function doGet() {
  return jsonOutput_({
    ok: true,
    service: 'BotGetLog usage analytics',
    time: isoNow_()
  });
}

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonOutput_({ ok: false, error: 'Missing request body.' });
    }

    const payload = JSON.parse(e.postData.contents);
    const providedApiKey = stringValue_(payload.api_key);
    if (EXPECTED_API_KEY && providedApiKey !== EXPECTED_API_KEY) {
      return jsonOutput_({ ok: false, error: 'Invalid api_key.' });
    }

    const events = Array.isArray(payload.events) ? payload.events : [];
    const spreadsheet = SpreadsheetApp.openById(SPREADSHEET_ID);
    const eventsSheet = ensureSheet_(spreadsheet, EVENTS_SHEET_NAME, EVENTS_HEADERS);
    const existingIds = getExistingEventIds_(eventsSheet);
    const receivedAt = isoNow_();
    const source = stringValue_(payload.source);
    const rows = [];

    events.forEach(function (event) {
      const eventId = stringValue_(event.event_id);
      if (!eventId || existingIds[eventId]) {
        return;
      }

      const startedAt = stringValue_(event.started_at);
      const eventDate = startedAt ? startedAt.substring(0, 10) : receivedAt.substring(0, 10);
      const eventMonth = eventDate ? eventDate.substring(0, 7) : '';

      rows.push([
        receivedAt,
        source,
        eventId,
        stringValue_(event.event_type),
        stringValue_(event.tool_name),
        stringValue_(event.app_version),
        stringValue_(event.machine_id),
        startedAt,
        stringValue_(event.queued_at),
        eventDate,
        eventMonth
      ]);

      existingIds[eventId] = true;
    });

    if (rows.length > 0) {
      appendRows_(eventsSheet, rows);
      rebuildSummaries_(spreadsheet, eventsSheet);
    }

    return jsonOutput_({
      ok: true,
      appended: rows.length,
      skipped: events.length - rows.length
    });
  } catch (error) {
    return jsonOutput_({
      ok: false,
      error: String(error && error.message ? error.message : error)
    });
  }
}

function ensureSheet_(spreadsheet, sheetName, headers) {
  let sheet = spreadsheet.getSheetByName(sheetName);
  if (!sheet) {
    sheet = spreadsheet.insertSheet(sheetName);
  }
  if (sheet.getLastRow() === 0 && headers && headers.length > 0) {
    sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
  }
  return sheet;
}

function getExistingEventIds_(sheet) {
  const result = {};
  const lastRow = sheet.getLastRow();
  if (lastRow <= 1) {
    return result;
  }
  const values = sheet.getRange(2, 3, lastRow - 1, 1).getValues();
  values.forEach(function (row) {
    const eventId = stringValue_(row[0]);
    if (eventId) {
      result[eventId] = true;
    }
  });
  return result;
}

function appendRows_(sheet, rows) {
  const startRow = sheet.getLastRow() + 1;
  sheet.getRange(startRow, 1, rows.length, rows[0].length).setValues(rows);
}

function rebuildSummaries_(spreadsheet, eventsSheet) {
  const allRows = eventsSheet.getDataRange().getValues();
  if (allRows.length <= 1) {
    writeSummarySheet_(spreadsheet, DAILY_SUMMARY_SHEET_NAME, ['event_date', 'launches', 'unique_users'], []);
    writeSummarySheet_(spreadsheet, MONTHLY_SUMMARY_SHEET_NAME, ['event_month', 'launches', 'unique_users'], []);
    writeSummarySheet_(spreadsheet, VERSION_SUMMARY_SHEET_NAME, ['app_version', 'unique_users'], []);
    return;
  }

  const daily = {};
  const monthly = {};
  const versions = {};

  for (let i = 1; i < allRows.length; i++) {
    const row = allRows[i];
    const eventDate = stringValue_(row[9]);
    const eventMonth = stringValue_(row[10]);
    const appVersion = stringValue_(row[5]);
    const machineId = stringValue_(row[6]);

    if (eventDate) {
      if (!daily[eventDate]) {
        daily[eventDate] = { launches: 0, users: {} };
      }
      daily[eventDate].launches++;
      if (machineId) {
        daily[eventDate].users[machineId] = true;
      }
    }

    if (eventMonth) {
      if (!monthly[eventMonth]) {
        monthly[eventMonth] = { launches: 0, users: {} };
      }
      monthly[eventMonth].launches++;
      if (machineId) {
        monthly[eventMonth].users[machineId] = true;
      }
    }

    if (appVersion && machineId) {
      if (!versions[appVersion]) {
        versions[appVersion] = {};
      }
      versions[appVersion][machineId] = true;
    }
  }

  writeSummarySheet_(
    spreadsheet,
    DAILY_SUMMARY_SHEET_NAME,
    ['event_date', 'launches', 'unique_users'],
    Object.keys(daily).sort().map(function (eventDate) {
      return [eventDate, daily[eventDate].launches, Object.keys(daily[eventDate].users).length];
    })
  );

  writeSummarySheet_(
    spreadsheet,
    MONTHLY_SUMMARY_SHEET_NAME,
    ['event_month', 'launches', 'unique_users'],
    Object.keys(monthly).sort().map(function (eventMonth) {
      return [eventMonth, monthly[eventMonth].launches, Object.keys(monthly[eventMonth].users).length];
    })
  );

  writeSummarySheet_(
    spreadsheet,
    VERSION_SUMMARY_SHEET_NAME,
    ['app_version', 'unique_users'],
    Object.keys(versions).sort().map(function (appVersion) {
      return [appVersion, Object.keys(versions[appVersion]).length];
    })
  );
}

function writeSummarySheet_(spreadsheet, sheetName, headers, rows) {
  const sheet = ensureSheet_(spreadsheet, sheetName, headers);
  sheet.clearContents();
  sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
  if (rows.length > 0) {
    sheet.getRange(2, 1, rows.length, headers.length).setValues(rows);
  }
}

function jsonOutput_(payload) {
  return ContentService
    .createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}

function isoNow_() {
  return Utilities.formatDate(new Date(), 'UTC', "yyyy-MM-dd'T'HH:mm:ss'Z'");
}

function stringValue_(value) {
  return value === null || value === undefined ? '' : String(value).trim();
}
