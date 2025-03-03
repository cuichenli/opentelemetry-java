/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics;

import static java.util.stream.Collectors.toList;

import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.common.Clock;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.internal.ComponentRegistry;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.internal.exemplar.ExemplarFilter;
import io.opentelemetry.sdk.metrics.internal.export.MetricProducer;
import io.opentelemetry.sdk.metrics.internal.export.RegisteredReader;
import io.opentelemetry.sdk.metrics.internal.state.MeterProviderSharedState;
import io.opentelemetry.sdk.metrics.internal.view.ViewRegistry;
import io.opentelemetry.sdk.resources.Resource;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** SDK implementation for {@link MeterProvider}. */
public final class SdkMeterProvider implements MeterProvider, Closeable {

  private static final Logger LOGGER = Logger.getLogger(SdkMeterProvider.class.getName());
  static final String DEFAULT_METER_NAME = "unknown";

  private final List<RegisteredReader> registeredReaders;
  private final MeterProviderSharedState sharedState;
  private final ComponentRegistry<SdkMeter> registry;
  private final AtomicBoolean isClosed = new AtomicBoolean(false);

  /**
   * Returns a new {@link SdkMeterProviderBuilder} for {@link SdkMeterProvider}.
   *
   * @return a new {@link SdkMeterProviderBuilder} for {@link SdkMeterProvider}.
   */
  public static SdkMeterProviderBuilder builder() {
    return new SdkMeterProviderBuilder();
  }

  SdkMeterProvider(
      List<RegisteredReader> registeredReaders,
      Clock clock,
      Resource resource,
      ViewRegistry viewRegistry,
      ExemplarFilter exemplarFilter) {
    long startEpochNanos = clock.now();
    this.registeredReaders = registeredReaders;
    this.sharedState =
        MeterProviderSharedState.create(
            clock, resource, viewRegistry, exemplarFilter, startEpochNanos);
    this.registry =
        new ComponentRegistry<>(
            instrumentationLibraryInfo ->
                new SdkMeter(sharedState, instrumentationLibraryInfo, registeredReaders));
    for (RegisteredReader registeredReader : registeredReaders) {
      MetricProducer producer = new LeasedMetricProducer(registry, sharedState, registeredReader);
      registeredReader.getReader().register(producer);
      registeredReader.setLastCollectEpochNanos(startEpochNanos);
    }
  }

  @Override
  public MeterBuilder meterBuilder(String instrumentationScopeName) {
    if (registeredReaders.isEmpty()) {
      return MeterProvider.noop().meterBuilder(instrumentationScopeName);
    }
    if (instrumentationScopeName == null || instrumentationScopeName.isEmpty()) {
      LOGGER.fine("Meter requested without instrumentation scope name.");
      instrumentationScopeName = DEFAULT_METER_NAME;
    }
    return new SdkMeterBuilder(registry, instrumentationScopeName);
  }

  /** Reset the provider, clearing all registered instruments. */
  void resetForTest() {
    registry.getComponents().forEach(SdkMeter::resetForTest);
  }

  /**
   * Call {@link MetricReader#forceFlush()} on all metric readers associated with this provider. The
   * resulting {@link CompletableResultCode} completes when all complete.
   */
  public CompletableResultCode forceFlush() {
    if (registeredReaders.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }
    List<CompletableResultCode> results = new ArrayList<>();
    for (RegisteredReader registeredReader : registeredReaders) {
      results.add(registeredReader.getReader().forceFlush());
    }
    return CompletableResultCode.ofAll(results);
  }

  /**
   * Shutdown the provider. Calls {@link MetricReader#shutdown()} on all metric readers associated
   * with this provider. The resulting {@link CompletableResultCode} completes when all complete.
   */
  public CompletableResultCode shutdown() {
    if (!isClosed.compareAndSet(false, true)) {
      LOGGER.info("Multiple close calls");
      return CompletableResultCode.ofSuccess();
    }
    if (registeredReaders.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }
    List<CompletableResultCode> results = new ArrayList<>();
    for (RegisteredReader info : registeredReaders) {
      results.add(info.getReader().shutdown());
    }
    return CompletableResultCode.ofAll(results);
  }

  /** Close the meter provider. Calls {@link #shutdown()} and blocks waiting for it to complete. */
  @Override
  public void close() {
    shutdown().join(10, TimeUnit.SECONDS);
  }

  @Override
  public String toString() {
    return "SdkMeterProvider{"
        + "clock="
        + sharedState.getClock()
        + ", resource="
        + sharedState.getResource()
        + ", metricReaders="
        + registeredReaders.stream().map(RegisteredReader::getReader).collect(toList())
        + ", views="
        + sharedState.getViewRegistry().getViews()
        + "}";
  }

  /** Helper class to expose registered metric exports. */
  private static class LeasedMetricProducer implements MetricProducer {

    private final ComponentRegistry<SdkMeter> registry;
    private final MeterProviderSharedState sharedState;
    private final RegisteredReader registeredReader;

    LeasedMetricProducer(
        ComponentRegistry<SdkMeter> registry,
        MeterProviderSharedState sharedState,
        RegisteredReader registeredReader) {
      this.registry = registry;
      this.sharedState = sharedState;
      this.registeredReader = registeredReader;
    }

    @Override
    public Collection<MetricData> collectAllMetrics() {
      Collection<SdkMeter> meters = registry.getComponents();
      List<MetricData> result = new ArrayList<>();
      long collectTime = sharedState.getClock().now();
      for (SdkMeter meter : meters) {
        result.addAll(meter.collectAll(registeredReader, collectTime));
      }
      registeredReader.setLastCollectEpochNanos(collectTime);
      return Collections.unmodifiableCollection(result);
    }
  }
}
