/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.secretstore.azure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import io.camunda.secretstore.SecretResolutionResult;
import io.camunda.zeebe.test.util.logging.LogCapturer;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Drives the check with a resolver whose probe fails as the SDK does for each cause, asserting the
 * one line the operator reads. The probes themselves, against the SDK, are covered by {@link
 * AzureKeyVaultSecretStoreTest}.
 */
final class StartupCheckTest {

  private static final String PHYSICAL_TENANT = "pt1";
  private static final String STORE_ID = "kv";
  private static final String VAULT_URL = "https://my-vault.vault.azure.net";
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final ExecutorService executor = AzureKeyVaultSecretStore.newCredentialExecutor();

  @AfterEach
  void shutDownExecutor() {
    executor.shutdownNow();
  }

  @Test
  void shouldConfirmAccessInOneInfoLine() {
    try (final var log = LogCapturer.capturing(StartupCheck.class, Level.DEBUG)) {
      // when
      run(new ProbingResolver(() -> {}), TIMEOUT);

      // then
      assertThat(log.messages())
          .singleElement()
          .asString()
          .contains(STORE_ID, PHYSICAL_TENANT, VAULT_URL, "can list its secrets");
      assertThat(log.messagesAt(Level.INFO)).hasSize(1);
    }
  }

  @Test
  void shouldWarnThatAVaultWhoseHostDoesNotResolveIsUnreachable() {
    // given the SDK wraps the I/O error of its HTTP client
    final var failure =
        new UncheckedIOException(new UnknownHostException("my-vault.vault.azure.net"));

    // then
    assertThat(warningFor(failure))
        .contains(STORE_ID, PHYSICAL_TENANT, VAULT_URL, "unreachable", "does not resolve");
  }

  @Test
  void shouldWarnThatAVaultRefusingConnectionsIsUnreachable() {
    // given
    final var failure = new RuntimeException(new ConnectException("Connection refused"));

    // then
    assertThat(warningFor(failure)).contains("unreachable", "connection failed");
  }

  @Test
  void shouldNameTheRoleAndPermissionTheIdentityLacks() {
    // given
    final var failure = new HttpResponseException("Forbidden", response(403));

    // then
    assertThat(warningFor(failure))
        .contains(
            "access denied",
            "'Key Vault Secrets User'",
            "'list' secret permission",
            "Listing is not permitted; resolution may still work");
  }

  @Test
  void shouldNameTheGetPermissionForAContainerSecret() {
    // given
    final var failure = new HttpResponseException("Forbidden", response(403));
    final var resolver = new ContainerProbingResolver(failure);

    // when
    final String warning;
    try (final var log = LogCapturer.capturing(StartupCheck.class, Level.WARN)) {
      run(resolver, TIMEOUT);
      warning = log.messagesAt(Level.WARN).getFirst();
    }

    // then
    assertThat(warning)
        .contains("read its container secret", "'get' secret permission")
        .doesNotContain("Listing");
  }

  @Test
  void shouldWarnThatTheVaultRejectedTheToken() {
    // given the identity signed in, but to a tenant the vault does not trust
    final var failure = new HttpResponseException("Unauthorized", response(401));

    // then
    assertThat(warningFor(failure)).contains("rejected the identity's token");
  }

  @Test
  void shouldLeaveAFailedSignInToTheSignInMonitor() {
    // given the monitor has already warned, naming the credential and the reason
    final var failure = new ClientAuthenticationException("AADSTS7000215", null);

    try (final var log = LogCapturer.capturing(StartupCheck.class, Level.DEBUG)) {
      // when
      run(
          new ProbingResolver(
              () -> {
                throw failure;
              }),
          TIMEOUT);

      // then
      assertThat(log.messagesAt(Level.WARN)).isEmpty();
      assertThat(log.messagesAt(Level.DEBUG)).singleElement().asString().contains("sign in");
    }
  }

  @Test
  void shouldGiveUpAfterTheTimeout() throws InterruptedException {
    // given a vault that never answers
    final var hanging = new CountDownLatch(1);
    final var interrupted = new CountDownLatch(1);
    final var resolver =
        new ProbingResolver(
            () -> {
              try {
                hanging.await();
              } catch (final InterruptedException e) {
                interrupted.countDown();
              }
            });

    try (final var log = LogCapturer.capturing(StartupCheck.class, Level.WARN)) {
      // when
      final long start = System.nanoTime();
      run(resolver, Duration.ofMillis(200));
      final var took = Duration.ofNanos(System.nanoTime() - start);

      // then the check returns at the timeout, and stops the probe
      assertThat(took).isLessThan(Duration.ofSeconds(2));
      assertThat(log.messagesAt(Level.WARN))
          .singleElement()
          .asString()
          .contains("unreachable", "did not answer within the call timeout of PT0.2S");
      assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void shouldLeaveATimeoutWhileSignInKeepsFailingToTheSignInMonitor() {
    // given the SDK keeps retrying a sign-in that fails, past the timeout
    final var resolver = new ProbingResolver(StartupCheckTest::waitUntilInterrupted);

    try (final var log = LogCapturer.capturing(StartupCheck.class, Level.DEBUG)) {
      // when
      run(resolver, Duration.ofMillis(200), true);

      // then the monitor's warning stays the only one, rather than one blaming the network
      assertThat(log.messagesAt(Level.WARN)).isEmpty();
      assertThat(log.messagesAt(Level.DEBUG)).singleElement().asString().contains("sign in");
    }
  }

  private static void waitUntilInterrupted() {
    try {
      new CountDownLatch(1).await();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String warningFor(final RuntimeException failure) {
    try (final var log = LogCapturer.capturing(StartupCheck.class, Level.DEBUG)) {
      run(
          new ProbingResolver(
              () -> {
                throw failure;
              }),
          TIMEOUT);
      assertThat(log.messagesAt(Level.WARN)).hasSize(1);
      assertThat(log.messagesAt(Level.INFO)).isEmpty();
      // the stack trace is for support, at DEBUG
      assertThat(log.messagesAt(Level.DEBUG)).hasSize(1);
      return log.messagesAt(Level.WARN).getFirst();
    }
  }

  private void run(final AzureSecretResolver resolver, final Duration timeout) {
    run(resolver, timeout, false);
  }

  private void run(
      final AzureSecretResolver resolver, final Duration timeout, final boolean signInFailing) {
    StartupCheck.run(
        resolver, executor, timeout, () -> signInFailing, PHYSICAL_TENANT, STORE_ID, VAULT_URL);
  }

  private static HttpResponse response(final int status) {
    final var request = new HttpRequest(HttpMethod.GET, VAULT_URL + "/secrets");
    return new HttpResponse(request) {
      @Override
      public int getStatusCode() {
        return status;
      }

      @Deprecated
      @Override
      public String getHeaderValue(final String name) {
        return null;
      }

      @Override
      public HttpHeaders getHeaders() {
        return new HttpHeaders();
      }

      @Override
      public Flux<ByteBuffer> getBody() {
        return Flux.empty();
      }

      @Override
      public Mono<byte[]> getBodyAsByteArray() {
        return Mono.empty();
      }

      @Override
      public Mono<String> getBodyAsString() {
        return Mono.empty();
      }

      @Override
      public Mono<String> getBodyAsString(final Charset charset) {
        return Mono.empty();
      }
    };
  }

  /** The one-by-one mode's wording, with a probe the test controls. */
  private static class ProbingResolver implements AzureSecretResolver {

    private final Runnable probe;
    // the wording needs no client
    private final OneByOneSecretResolver wording = new OneByOneSecretResolver(null);

    ProbingResolver(final Runnable probe) {
      this.probe = probe;
    }

    @Override
    public Map<String, SecretResolutionResult> resolve(final Set<String> names) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<String> list() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void validateConnectivity() {
      probe.run();
    }

    @Override
    public String probeDescription() {
      return wording.probeDescription();
    }

    @Override
    public String accessDeniedHint() {
      return wording.accessDeniedHint();
    }
  }

  private static final class ContainerProbingResolver extends ProbingResolver {

    private final ContainerSecretResolver wording = new ContainerSecretResolver(null, "camunda");

    ContainerProbingResolver(final RuntimeException failure) {
      super(
          () -> {
            throw failure;
          });
    }

    @Override
    public String probeDescription() {
      return wording.probeDescription();
    }

    @Override
    public String accessDeniedHint() {
      return wording.accessDeniedHint();
    }
  }
}
