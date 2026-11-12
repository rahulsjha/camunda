/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.broker.partitioning.topology;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.Member;
import io.atomix.cluster.MemberId;
import io.camunda.zeebe.broker.system.configuration.RocksdbCfg;
import io.camunda.zeebe.dynamic.config.api.ClusterConfigurationChangeValidator;
import io.camunda.zeebe.dynamic.config.state.CurrentClusterConfiguration;
import io.camunda.zeebe.dynamic.config.util.ConfigurationUtil;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Rejects a change that would give a broker more partitions than its RocksDB memory configuration
 * allows, since that broker could no longer restart.
 *
 * <p>Brokers can be configured differently, e.g. per region, so each broker advertises its own
 * limit through its member properties (see {@link #advertiseMaxPartitions(Member, RocksdbCfg)}).
 * Brokers which are unreachable, or which run a version that does not advertise a limit, are not
 * checked.
 */
@NullMarked
public final class RocksDbMemoryChangeValidator implements ClusterConfigurationChangeValidator {

  static final String MAX_PARTITIONS_PROPERTY = "rocksDbMaxPartitionsPerBroker";

  private final ClusterMembershipService membershipService;

  public RocksDbMemoryChangeValidator(final ClusterMembershipService membershipService) {
    this.membershipService = membershipService;
  }

  /** Publishes how many partitions the local broker can start with to the other members. */
  public static void advertiseMaxPartitions(final Member localMember, final RocksdbCfg rocksdbCfg) {
    final var maxPartitions = rocksdbCfg.getMaxPartitionsPerBroker();
    if (maxPartitions.isPresent()) {
      localMember
          .properties()
          .setProperty(MAX_PARTITIONS_PROPERTY, Integer.toString(maxPartitions.getAsInt()));
    } else {
      localMember.properties().remove(MAX_PARTITIONS_PROPERTY);
    }
  }

  @Override
  public Optional<String> validate(
      final CurrentClusterConfiguration current, final CurrentClusterConfiguration proposed) {
    final var partitionsBefore = partitionsPerMember(current);
    final var rejections =
        partitionsPerMember(proposed).entrySet().stream()
            .filter(e -> e.getValue() > partitionsBefore.getOrDefault(e.getKey(), 0))
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(MemberId::id)))
            .flatMap(e -> validatePartitionCount(e.getKey(), e.getValue()).stream())
            .toList();

    if (rejections.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        String.join(" ", rejections)
            + " Increase the broker memory or the RocksDB memory configuration before applying"
            + " this change.");
  }

  private Optional<String> validatePartitionCount(
      final MemberId memberId, final int partitionCount) {
    final var maxPartitions = advertisedMaxPartitions(membershipService.getMember(memberId));
    if (maxPartitions.isEmpty() || partitionCount <= maxPartitions.getAsInt()) {
      return Optional.empty();
    }
    return Optional.of(
        String.format(
            "Expected broker %s to be able to restart with %d partitions after this change, but"
                + " its RocksDB memory configuration allows at most %d partitions.",
            memberId.id(), partitionCount, maxPartitions.getAsInt()));
  }

  private static OptionalInt advertisedMaxPartitions(final @Nullable Member member) {
    final var value =
        member == null ? null : member.properties().getProperty(MAX_PARTITIONS_PROPERTY);
    if (value == null) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(value));
    } catch (final NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  /** Counts partitions the same way a broker does when it restarts. */
  private static Map<MemberId, Integer> partitionsPerMember(
      final CurrentClusterConfiguration configuration) {
    return ConfigurationUtil.getPartitionDistributionPerPhysicalTenant(configuration)
        .values()
        .stream()
        .flatMap(partitions -> partitions.stream())
        .flatMap(partition -> partition.members().stream())
        .collect(Collectors.toMap(member -> member, member -> 1, Integer::sum));
  }
}
