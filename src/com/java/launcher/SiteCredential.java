package com.java.launcher;

public final class SiteCredential {

    private final String siteId;
    private final String username;
    private final String password;
    private final boolean autoLoginEnabled;

    public SiteCredential(String siteId, String username, String password, boolean autoLoginEnabled) {
        this.siteId = siteId == null ? "" : siteId.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.autoLoginEnabled = autoLoginEnabled;
    }

    public String getSiteId() {
        return siteId;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isAutoLoginEnabled() {
        return autoLoginEnabled;
    }

    public boolean hasUsableCredential() {
        return autoLoginEnabled && !username.isEmpty() && !password.isEmpty();
    }
}
