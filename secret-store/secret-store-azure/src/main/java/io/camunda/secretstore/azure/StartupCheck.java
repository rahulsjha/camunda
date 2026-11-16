/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.secretstore.azure;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks once, when a store is built, that it can reach its vault, sign in and read what its mode
 * needs, so a wrong vault URL, network rule or role shows in the log before the first secret is
 * resolved.
 *
 * <p>Best effort: the outcome is only logged, and the store is built either way, so a vault that is
 * down cannot stop the cluster from starting. Its secrets then fail to resolve until the cause is
 * fixed. The check never takes longer than the store's call timeout: past it, the call is
 * interrupted and left to the SDK's own timeouts.
 *
 * <p>Logs one INFO line on success, or one WARN line telling the cause apart, with the stack trace
 * at DEBUG. A failed sign-in is the exception: {@link SignInMonitor} has already logged its WARN,
 * naming the credential and the reason, so it is not repeated here. That includes a check that runs
 * out of time while sign-in keeps failing, as the SDK retries a failed sign-in with backoff.
 */
final class StartupCheck {

  private static final Logger LOG = LoggerFactory.getLogger(StartupCheck.class);

  private StartupCheck() {}

  /**
   * @param executor runs the probe, so the caller can stop waiting for it
   * @param signInFailing whether the store's sign-in is failing, see {@link
   *     SignInMonitor#isFailing()}
   */
  static void run(
      final AzureSecretResolver resolver,
      final ExecutorService executor,
      final Duration timeout,
      final BooleanSupplier signInFailing,
      final String physicalTenantId,
      final String storeId,
      final String vaultUrl) {
    final Future<?> probe = executor.submit(resolver::validateConnectivity);
    try {
      probe.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      LOG.info(
          "Azure Key Vault store '{}' of physical tenant '{}' can {} at '{}'",
          storeId,
          physicalTenantId,
          resolver.probeDescription(),
          vaultUrl);
      return;
    } catch (final InterruptedException e) {
      probe.cancel(true);
      Thread.currentThread().interrupt();
      return;
    } catch (final TimeoutException e) {
      probe.cancel(true);
      if (signInFailing.getAsBoolean()) {
        LOG.debug(
            "Azure Key Vault store '{}' of physical tenant '{}' cannot {} at '{}' within {}, as it"
                + " cannot sign in",
            storeId,
            physicalTenantId,
            resolver.probeDescription(),
            vaultUrl,
            timeout);
        return;
      }
      warn(
          resolver,
          physicalTenantId,
          storeId,
          vaultUrl,
          "the vault is unreachable: it did not answer within the call timeout of " + timeout,
          e);
    } catch (final ExecutionException e) {
      final var failure = e.getCause() != null ? e.getCause() : e;
      final var cause = describe(failure, resolver);
      if (cause == null) {
        LOG.debug(
            "Azure Key Vault store '{}' of physical tenant '{}' cannot {} at '{}' as it cannot sign"
                + " in",
            storeId,
            physicalTenantId,
            resolver.probeDescription(),
            vaultUrl,
            failure);
      } else {
        warn(resolver, physicalTenantId, storeId, vaultUrl, cause, failure);
      }
    }
  }

  private static void warn(
      final AzureSecretResolver resolver,
      final String physicalTenantId,
      final String storeId,
      final String vaultUrl,
      final String cause,
      final Throwable failure) {
    LOG.warn(
        "Azure Key Vault store '{}' of physical tenant '{}' cannot {} at '{}': {}. The cluster"
            + " starts anyway, and secrets of this store fail to resolve until this is fixed",
        storeId,
        physicalTenantId,
        resolver.probeDescription(),
        vaultUrl,
        cause);
    LOG.debug("Startup check of Azure Key Vault store '{}' failed", storeId, failure);
  }

  /**
   * The cause, as the operator should read it, or {@code null} for a failed sign-in, which {@link
   * SignInMonitor} reports.
   */
  static @Nullable String describe(final Throwable failure, final AzureSecretResolver resolver) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof ClientAuthenticationException) {
        return null;
      }
      if (t instanceof final HttpResponseException http && http.getResponse() != null) {
        return switch (http.getResponse().getStatusCode()) {
          case 401 ->
              "the vault rejected the identity's token, for example because the vault belongs to"
                  + " a different Microsoft Entra tenant";
          case 403 -> "access denied: " + resolver.accessDeniedHint();
          case 404 -> "not found: " + firstLine(http.getMessage());
          default ->
              "the vault answered HTTP "
                  + http.getResponse().getStatusCode()
                  + ": "
                  + firstLine(http.getMessage());
        };
      }
      if (t instanceof UnknownHostException) {
        return "the vault is unreachable: its host does not resolve (" + t.getMessage() + ")";
      }
      if (t instanceof ConnectException) {
        return "the vault is unreachable: the connection failed (" + t.getMessage() + ")";
      }
      if (t instanceof IOException || t instanceof TimeoutException) {
        return "the vault is unreachable: " + t;
      }
    }
    return failure.toString();
  }

  // the SDK appends the whole JSON error body on further lines
  private static String firstLine(final @Nullable String message) {
    if (message == null) {
      return "";
    }
    final int end = message.indexOf('\n');
    return end < 0 ? message : message.substring(0, end);
  }
}
