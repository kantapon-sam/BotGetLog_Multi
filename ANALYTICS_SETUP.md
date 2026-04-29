# Analytics Setup

This project can batch usage events locally and send them to GitHub as `repository_dispatch` events. GitHub Actions then stores each batch in an issue comment and rebuilds the visible summaries on a schedule.

## 1. Create an analytics issue

Create one GitHub issue in the same repository, for example:

- Title: `Usage Analytics Inbox`

Copy the issue number and save it as a repository variable:

- Name: `BOTGETLOG_ANALYTICS_ISSUE_NUMBER`
- Value: the issue number, for example `123`

## 2. Create a token for the desktop app

Create a fine-grained personal access token with:

- Repository access to this repository
- `Contents: Read and write`

Store it on each client machine instead of hard-coding it in Java.

## 3. Set client configuration

The simplest setup is a plain file named `analytics.properties` in the same folder as the application JAR.

1. Copy `analytics.properties.template` to `analytics.properties`
2. Fill in the real values
3. Keep that file next to `BotGetLog_Multi.jar`

Example:

```properties
enabled=true
repoOwner=OWNER
repoName=REPO
token=github_pat_...
eventType=app_launch_batch
maxBatchSize=20
debug=false
```

After that, users can open the program normally. No `.bat` file is required.

Advanced override options still work if you need them:

- Environment variables such as `BOTGETLOG_ANALYTICS_ENABLED=true`
- JVM properties such as `-Dbotgetlog.analytics.enabled=true`

Priority order is:

1. JVM properties
2. Environment variables
3. `analytics.properties`

## 4. Where results appear

After the scheduled workflow runs, GitHub will refresh:

- `analytics/events.csv`
- `analytics/daily_summary.csv`
- `analytics/monthly_summary.csv`
- `analytics/version_summary.csv`
- `analytics/README.md`

You can open those files directly in the repository to inspect usage.
