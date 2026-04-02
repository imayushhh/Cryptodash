package com.example.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class LocationInfo {

    private final String id;
    private final String name;
    private final String type;
    private final String city;
    private final String country;
    private final String address;
    private final String hours;
    private final String status;
    private final String phone;
    private final String rating;
    private final List<String> services;

    @JsonProperty("lat")
    private final Double latitude;

    @JsonProperty("lng")
    private final Double longitude;

    public LocationInfo(String id,
                        String name,
                        String type,
                        String city,
                        String country,
                        String address,
                        String hours,
                        String status,
                        String phone,
                        String rating,
                        List<String> services,
                        Double latitude,
                        Double longitude) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.city = city;
        this.country = country;
        this.address = address;
        this.hours = hours;
        this.status = status;
        this.phone = phone;
        this.rating = rating;
        this.services = services;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getCity() {
        return city;
    }

    public String getCountry() {
        return country;
    }

    public String getAddress() {
        return address;
    }

    public String getHours() {
        return hours;
    }

    public String getStatus() {
        return status;
    }

    public String getPhone() {
        return phone;
    }

    public String getRating() {
        return rating;
    }

    public List<String> getServices() {
        return services;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }
}
