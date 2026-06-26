package com.cezar.newmiddleware.entity;

import java.time.OffsetDateTime;

public class SensorReading {

    private OffsetDateTime timestamp;

    private String device;
    private String geohash;
    private String installation;

    private Double charge;

    private Boolean sun;

    private Double pm1;
    private Double pm25;
    private Double pm4;
    private Double pm10;
    private Double temp;
    private Double hum;
    private Double voc_index;
    private Double nox_index;
    private Double voc;
    private Double nox;
    private Double co2;

    protected SensorReading() {}

    public SensorReading(OffsetDateTime timestamp, String device, String geohash, String installation, Double charge,
                         Boolean sun, Double pm1, Double pm25, Double pm4, Double pm10,
                         Double temp, Double hum, Double voc_index, Double nox_index,
                         Double voc, Double nox, Double co2) {
        this.timestamp = timestamp;
        this.device = device;
        this.geohash = geohash;
        this.installation = installation;
        this.charge = charge;
        this.sun = sun;
        this.pm1 = pm1;
        this.pm25 = pm25;
        this.pm4 = pm4;
        this.pm10 = pm10;
        this.temp = temp;
        this.hum = hum;
        this.voc_index = voc_index;
        this.nox_index = nox_index;
        this.voc = voc;
        this.nox = nox;
        this.co2 = co2;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public String getGeohash() {
        return geohash;
    }

    public void setGeohash(String geohash) {
        this.geohash = geohash;
    }

    public String getInstallation() {
        return installation;
    }

    public void setInstallation(String installation) {
        this.installation = installation;
    }

    public Double getCharge() {
        return charge;
    }

    public void setCharge(Double charge) {
        this.charge = charge;
    }

    public Boolean getSun() {
        return sun;
    }

    public void setSun(Boolean sun) {
        this.sun = sun;
    }

    public Double getPm1() {
        return pm1;
    }

    public void setPm1(Double pm1) {
        this.pm1 = pm1;
    }

    public Double getPm25() {
        return pm25;
    }

    public void setPm25(Double pm25) {
        this.pm25 = pm25;
    }

    public Double getPm4() {
        return pm4;
    }

    public void setPm4(Double pm4) {
        this.pm4 = pm4;
    }

    public Double getPm10() {
        return pm10;
    }

    public void setPm10(Double pm10) {
        this.pm10 = pm10;
    }

    public Double getTemp() {
        return temp;
    }

    public void setTemp(Double temp) {
        this.temp = temp;
    }

    public Double getHum() {
        return hum;
    }

    public void setHum(Double hum) {
        this.hum = hum;
    }

    public Double getVoc_index() {
        return voc_index;
    }

    public void setVoc_index(Double voc_index) {
        this.voc_index = voc_index;
    }

    public Double getNox_index() {
        return nox_index;
    }

    public void setNox_index(Double nox_index) {
        this.nox_index = nox_index;
    }

    public Double getVoc() {
        return voc;
    }

    public void setVoc(Double voc) {
        this.voc = voc;
    }

    public Double getNox() {
        return nox;
    }

    public void setNox(Double nox) {
        this.nox = nox;
    }

    public Double getCo2() {
        return co2;
    }

    public void setCo2(Double co2) {
        this.co2 = co2;
    }
}
