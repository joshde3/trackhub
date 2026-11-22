package io.github.joshde3.trackhub.domain.shipment;

import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.CREATED;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.DELIVERED;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.EXCEPTION;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.IN_TRANSIT;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.OUT_FOR_DELIVERY;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.RETURNED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks every one of the 36 (from, to) pairs against the state machine written out by hand, so a
 * change to the rules has to change this table too.
 */
class ShipmentStatusTest {

    private static final Map<ShipmentStatus, Set<ShipmentStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, RETURNED),
            IN_TRANSIT, EnumSet.of(OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, RETURNED),
            OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, EXCEPTION, RETURNED),
            EXCEPTION, EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, RETURNED),
            DELIVERED, EnumSet.noneOf(ShipmentStatus.class),
            RETURNED, EnumSet.noneOf(ShipmentStatus.class));

    static Stream<Arguments> everyPair() {
        return Stream.of(ShipmentStatus.values())
                .flatMap(from -> Stream.of(ShipmentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void followsTheStateMachine(ShipmentStatus from, ShipmentStatus to) {
        assertThat(from.canMoveTo(to)).isEqualTo(ALLOWED.get(from).contains(to));
    }

    @Test
    void onlyDeliveredAndReturnedAreTerminal() {
        assertThat(EnumSet.allOf(ShipmentStatus.class))
                .filteredOn(ShipmentStatus::isTerminal)
                .containsExactlyInAnyOrder(DELIVERED, RETURNED);
    }

    @Test
    void exceptionIsTheOnlyWayBackwards() {
        assertThat(EXCEPTION.canMoveTo(IN_TRANSIT)).isTrue();
        assertThat(OUT_FOR_DELIVERY.canMoveTo(IN_TRANSIT)).isFalse();
        assertThat(IN_TRANSIT.canMoveTo(CREATED)).isFalse();
    }
}
