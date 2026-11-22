/**
 * TrackHub's business rules, in plain Java.
 *
 * <p>Nothing in this package may import Spring, JPA, Jackson or any HTTP library. An ArchUnit
 * test enforces this, so the rules can be tested in milliseconds without a container or a
 * database (ADR 0002).
 */
package io.github.joshde3.trackhub.domain;
