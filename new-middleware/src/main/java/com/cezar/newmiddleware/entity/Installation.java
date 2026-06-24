package com.cezar.newmiddleware.entity;

import java.time.OffsetDateTime;

public class Installation {

    private String deviceId;
    private String apiKey;
    private String ownerEmail;
    private String coOwner1Email;
    private String coOwner2Email;
    private String installation;
    private String notes;

    private OffsetDateTime createdAt;

    public Installation() {}

    public Installation(String deviceId, String apiKey,
                        String ownerEmail, String coOwner1Email,
                        String coOwner2Email, String installation,
                        String notes, OffsetDateTime createdAt) {
        this.deviceId = deviceId;
        this.apiKey = apiKey;
        this.ownerEmail = ownerEmail;
        this.coOwner1Email = coOwner1Email;
        this.coOwner2Email = coOwner2Email;
        this.installation = installation;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public String getCoOwner1Email() {
        return coOwner1Email;
    }

    public void setCoOwner1Email(String coOwner1Email) {
        this.coOwner1Email = coOwner1Email;
    }

    public String getCoOwner2Email() {
        return coOwner2Email;
    }

    public void setCoOwner2Email(String coOwner2Email) {
        this.coOwner2Email = coOwner2Email;
    }

    public String getInstallation() {
        return installation;
    }

    public void setInstallation(String installation) {
        this.installation = installation;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
