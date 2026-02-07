package com.skypath.backend.infrastructure.dataset;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.lang.NonNull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FlightsDatasetLoader}.
 * These tests do not rely on a Spring ApplicationContext or any mocking
 * framework. Instead, they use small in-memory ResourceLoader implementations
 * to simulate existing and non-existing resources and to verify that the
 * loader behaves correctly in each case.
 */
class FlightsDatasetLoaderTest {

  /**
   * Happy-path test:
   * Verifies that when the underlying Resource exists, the constructor
   * completes successfully and openDatasetStream() returns the expected content.
   */
  @Test
  void constructor_shouldSucceedWhenResourceExists_andOpenStreamReturnsContent() throws IOException {
    // given
    String location = "classpath:test/flights.json";
    String expectedContent = """
        {
          "airports": [],
          "flights": []
        }
        """;

    ResourceLoader resourceLoader = new ExistingResourceLoader(expectedContent);

    // when
    FlightsDatasetLoader loader = new FlightsDatasetLoader(location, resourceLoader);

    // then: constructor should not throw, and stream content should match
    try (InputStream is = loader.openDatasetStream()) {
      byte[] bytes = is.readAllBytes();
      String actualContent = new String(bytes, StandardCharsets.UTF_8);
      assertEquals(expectedContent, actualContent);
    }
  }

  /**
   * Failure case:
   * Verifies that when the underlying Resource does not exist,
   * the constructor fails fast with an IllegalStateException.
   */
  @Test
  void constructor_shouldFailWhenResourceDoesNotExist() {
    // given
    String location = "classpath:nonexistent/flights.json";
    ResourceLoader resourceLoader = new NonExistingResourceLoader();

    // when / then
    IllegalStateException ex = assertThrows(
        IllegalStateException.class,
        () -> new FlightsDatasetLoader(location, resourceLoader),
        "Expected IllegalStateException when resource does not exist"
    );

    assertTrue(
        ex.getMessage().contains(location),
        "Exception message should mention the missing location"
    );
  }

  /**
   * Edge case:
   * Verifies that if the Resource exists but throws an IOException when
   * openDatasetStream() is called, that IOException is propagated to the caller.
   */
  @Test
  void openDatasetStream_shouldPropagateIOExceptionFromResource() {
    // given
    String location = "classpath:test/flights.json";
    ResourceLoader resourceLoader = new FailingOnReadResourceLoader();

    FlightsDatasetLoader loader = new FlightsDatasetLoader(location, resourceLoader);

    // when / then
    assertThrows(
        IOException.class,
        loader::openDatasetStream,
        "Expected IOException to be propagated from underlying Resource"
    );
  }

  // --------------------------------------------------------------------------
  // Test helpers: simple ResourceLoader implementations for different scenarios
  // --------------------------------------------------------------------------

  /**
   * ResourceLoader that always returns a Resource which exists and exposes
   * the given JSON content.
   */
  private static class ExistingResourceLoader implements ResourceLoader {

    private final Resource resource;

    ExistingResourceLoader(String content) {
      this.resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
        @Override
        public boolean exists() {
          return true;
        }
      };
    }

    @Override
    @NonNull
    public Resource getResource(@NonNull String location) {
      return resource;
    }

    @Override
    public ClassLoader getClassLoader() {
      return getClass().getClassLoader();
    }
  }

  /**
   * ResourceLoader that always returns a Resource which does not exist.
   * Used to verify the constructor's fail-fast behavior when the dataset
   * cannot be found.
   */
  private static class NonExistingResourceLoader implements ResourceLoader {

    @Override
    @NonNull
    public Resource getResource(@NonNull String location) {
      return new ByteArrayResource(new byte[0]) {
        @Override
        public boolean exists() {
          return false;
        }
      };
    }

    @Override
    public ClassLoader getClassLoader() {
      return getClass().getClassLoader();
    }
  }

  /**
   * ResourceLoader that returns a Resource which exists but throws an
   * IOException when getInputStream() is called. This allows us to verify
   * that FlightsDatasetLoader.openDatasetStream() correctly propagates
   * IOExceptions from the underlying Resource.
   */
  private static class FailingOnReadResourceLoader implements ResourceLoader {

    @Override
    @NonNull
    public Resource getResource(@NonNull String location) {
      return new ByteArrayResource(new byte[0]) {
        @Override
        public boolean exists() {
          return true;
        }

        @Override
        @NonNull
        public InputStream getInputStream() throws IOException {
          throw new IOException("Simulated read failure");
        }
      };
    }

    @Override
    public ClassLoader getClassLoader() {
      return getClass().getClassLoader();
    }
  }
}