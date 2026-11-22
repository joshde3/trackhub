package io.github.joshde3.trackhub.app.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data queries. Package-private: the rest of the app uses the domain's port instead. */
interface ShipmentJpaRepository extends JpaRepository<ShipmentEntity, UUID> {

    /** Loads the timeline in the same query, so reading a parcel is one round trip, not N+1. */
    @EntityGraph(attributePaths = "events")
    Optional<ShipmentEntity> findByIdAndUserId(UUID id, UUID userId);

    @EntityGraph(attributePaths = "events")
    Optional<ShipmentEntity> findByUserIdAndCarrierCodeAndTrackingNumber(
            UUID userId, String carrierCode, String trackingNumber);

    @EntityGraph(attributePaths = "events")
    List<ShipmentEntity> findByCarrierCodeAndTrackingNumber(String carrierCode, String trackingNumber);
}
