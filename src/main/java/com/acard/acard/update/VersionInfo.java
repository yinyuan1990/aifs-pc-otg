package com.acard.acard.update;

import com.google.gson.annotations.SerializedName;

/**
 * 版本信息
 */
public class VersionInfo {
    
    @SerializedName("version")
    public String version;
    
    @SerializedName("versionCode")
    public int versionCode;
    
    @SerializedName("downloadUrl")
    public String downloadUrl;
    
    @SerializedName("changelog")
    public String changelog;
    
    @SerializedName("forceUpdate")
    public boolean forceUpdate;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public int getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(int versionCode) {
        this.versionCode = versionCode;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public boolean isForceUpdate() {
        return forceUpdate;
    }

    public void setForceUpdate(boolean forceUpdate) {
        this.forceUpdate = forceUpdate;
    }

    @Override
    public String toString() {
        return "VersionInfo{" +
                "version='" + version + '\'' +
                ", versionCode=" + versionCode +
                ", downloadUrl='" + downloadUrl + '\'' +
                '}';
    }
}

