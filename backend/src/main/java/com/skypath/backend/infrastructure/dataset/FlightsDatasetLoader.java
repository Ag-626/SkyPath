package com.skypath.backend.infrastructure.dataset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class FlightsDatasetLoader {

  private static final Logger log = LoggerFactory.getLogger(FlightsDatasetLoader.class);

  private final Resource datasetResource;

  public FlightsDatasetLoader(
      @Value("${skypath.dataset.location:classpath:static/flights.json}") String location,
      ResourceLoader resourceLoader
  ) {
    this.datasetResource = resourceLoader.getResource(location);

    if (!this.datasetResource.exists()) {
      log.error("Flights dataset resource does not exist at location: {}", location);
      throw new IllegalStateException("Flights dataset not found at: " + location);
    }

    log.info("Configured flights dataset location: {}", location);
  }

  public InputStream openDatasetStream() throws IOException {
    return datasetResource.getInputStream();
  }
}