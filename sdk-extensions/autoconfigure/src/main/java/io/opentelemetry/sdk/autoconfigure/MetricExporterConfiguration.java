/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.autoconfigure;

import static io.opentelemetry.sdk.autoconfigure.OtlpConfigUtil.DATA_TYPE_METRICS;
import static io.opentelemetry.sdk.autoconfigure.OtlpConfigUtil.PROTOCOL_GRPC;
import static io.opentelemetry.sdk.autoconfigure.OtlpConfigUtil.PROTOCOL_HTTP_PROTOBUF;

import io.opentelemetry.exporter.internal.retry.RetryUtil;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServerBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.time.Duration;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

final class MetricExporterConfiguration {
  static MetricReader configureExporter(
      String name,
      ConfigProperties config,
      ClassLoader serviceClassLoader,
      BiFunction<? super MetricExporter, ConfigProperties, ? extends MetricExporter>
          metricExporterCustomizer) {
    if (name.equals("prometheus")) {
      return configurePrometheusMetricReader(config);
    }

    MetricExporter metricExporter;
    switch (name) {
      case "otlp":
        metricExporter = configureOtlpMetrics(config);
        break;
      case "logging":
        metricExporter = configureLoggingExporter();
        break;
      default:
        MetricExporter spiExporter = configureSpiExporter(name, config, serviceClassLoader);
        if (spiExporter == null) {
          throw new ConfigurationException("Unrecognized value for otel.metrics.exporter: " + name);
        }
        metricExporter = spiExporter;
    }

    metricExporter = metricExporterCustomizer.apply(metricExporter, config);
    return configurePeriodicMetricReader(config, metricExporter);
  }

  private static MetricExporter configureLoggingExporter() {
    ClasspathUtil.checkClassExists(
        "io.opentelemetry.exporter.logging.LoggingMetricExporter",
        "Logging Metrics Exporter",
        "opentelemetry-exporter-logging");
    return LoggingMetricExporter.create();
  }

  // Visible for testing.
  @Nullable
  static MetricExporter configureSpiExporter(
      String name, ConfigProperties config, ClassLoader serviceClassLoader) {
    NamedSpiManager<MetricExporter> spiExportersManager =
        SpiUtil.loadConfigurable(
            ConfigurableMetricExporterProvider.class,
            ConfigurableMetricExporterProvider::getName,
            ConfigurableMetricExporterProvider::createExporter,
            config,
            serviceClassLoader);
    return spiExportersManager.getByName(name);
  }

  // Visible for testing
  static MetricExporter configureOtlpMetrics(ConfigProperties config) {
    String protocol = OtlpConfigUtil.getOtlpProtocol(DATA_TYPE_METRICS, config);

    if (protocol.equals(PROTOCOL_HTTP_PROTOBUF)) {
      ClasspathUtil.checkClassExists(
          "io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter",
          "OTLP HTTP Metrics Exporter",
          "opentelemetry-exporter-otlp-http-metrics");
      OtlpHttpMetricExporterBuilder builder = OtlpHttpMetricExporter.builder();

      OtlpConfigUtil.configureOtlpExporterBuilder(
          DATA_TYPE_METRICS,
          config,
          builder::setEndpoint,
          builder::addHeader,
          builder::setCompression,
          builder::setTimeout,
          builder::setTrustedCertificates,
          builder::setClientTls,
          retryPolicy -> RetryUtil.setRetryPolicyOnDelegate(builder, retryPolicy));
      OtlpConfigUtil.configureOtlpAggregationTemporality(
          config, builder::setAggregationTemporalitySelector);

      return builder.build();
    } else if (protocol.equals(PROTOCOL_GRPC)) {
      ClasspathUtil.checkClassExists(
          "io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter",
          "OTLP gRPC Metrics Exporter",
          "opentelemetry-exporter-otlp");
      OtlpGrpcMetricExporterBuilder builder = OtlpGrpcMetricExporter.builder();

      OtlpConfigUtil.configureOtlpExporterBuilder(
          DATA_TYPE_METRICS,
          config,
          builder::setEndpoint,
          builder::addHeader,
          builder::setCompression,
          builder::setTimeout,
          builder::setTrustedCertificates,
          builder::setClientTls,
          retryPolicy -> RetryUtil.setRetryPolicyOnDelegate(builder, retryPolicy));
      OtlpConfigUtil.configureOtlpAggregationTemporality(
          config, builder::setAggregationTemporalitySelector);

      return builder.build();
    } else {
      throw new ConfigurationException("Unsupported OTLP metrics protocol: " + protocol);
    }
  }

  private static PeriodicMetricReader configurePeriodicMetricReader(
      ConfigProperties config, MetricExporter exporter) {

    Duration exportInterval = config.getDuration("otel.metric.export.interval");
    if (exportInterval == null) {
      exportInterval = Duration.ofMinutes(1);
    }

    return PeriodicMetricReader.builder(exporter).setInterval(exportInterval).build();
  }

  private static PrometheusHttpServer configurePrometheusMetricReader(ConfigProperties config) {
    ClasspathUtil.checkClassExists(
        "io.opentelemetry.exporter.prometheus.PrometheusHttpServer",
        "Prometheus Metrics Server",
        "opentelemetry-exporter-prometheus");
    PrometheusHttpServerBuilder prom = PrometheusHttpServer.builder();

    Integer port = config.getInt("otel.exporter.prometheus.port");
    if (port != null) {
      prom.setPort(port);
    }
    String host = config.getString("otel.exporter.prometheus.host");
    if (host != null) {
      prom.setHost(host);
    }
    return prom.build();
  }

  private MetricExporterConfiguration() {}
}
