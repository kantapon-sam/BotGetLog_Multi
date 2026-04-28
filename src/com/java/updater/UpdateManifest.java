package com.java.updater;

final class UpdateManifest {

    private final String version;
    private final String downloadUrl;
    private final String sha256;
    private final String notes;

    UpdateManifest(String version, String downloadUrl, String sha256, String notes) {
        this.version = safe(version);
        this.downloadUrl = safe(downloadUrl);
        this.sha256 = safe(sha256);
        this.notes = safe(notes);
    }

    String getVersion() {
        return version;
    }

    String getDownloadUrl() {
        return downloadUrl;
    }

    String getSha256() {
        return sha256;
    }

    String getNotes() {
        return notes;
    }

    boolean isValid() {
        return !version.isEmpty() && !downloadUrl.isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

