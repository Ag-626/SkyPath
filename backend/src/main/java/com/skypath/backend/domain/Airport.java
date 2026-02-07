package com.skypath.backend.domain;

import java.time.ZoneId;
import java.util.Objects;

public class Airport {

  private final String code;
  private final String name;
  private final String city;
  private final String country;
  private final ZoneId timezone;

  public Airport(String code, String name, String city, String country, ZoneId timezone) {
    this.code = Objects.requireNonNull(code, "code must not be null");
    this.name = Objects.requireNonNull(name, "name must not be null");
    this.city = Objects.requireNonNull(city, "city must not be null");
    this.country = Objects.requireNonNull(country, "country must not be null");
    this.timezone = Objects.requireNonNull(timezone, "timezone must not be null");
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getCity() {
    return city;
  }

  public String getCountry() {
    return country;
  }

  public ZoneId getTimezone() {
    return timezone;
  }

}