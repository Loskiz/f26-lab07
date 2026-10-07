package edu.cmu.cs214.scheduling.workflow;

import edu.cmu.cs214.scheduling.domain.Booking;
import edu.cmu.cs214.scheduling.domain.BookingOutcome;
import edu.cmu.cs214.scheduling.domain.BookingRequest;
import edu.cmu.cs214.scheduling.domain.BookingStore;
import edu.cmu.cs214.scheduling.domain.BookingType;
import edu.cmu.cs214.scheduling.domain.Member;
import edu.cmu.cs214.scheduling.domain.MembershipTier;
import edu.cmu.cs214.scheduling.domain.Room;
import edu.cmu.cs214.scheduling.domain.TimeSlot;
import edu.cmu.cs214.scheduling.notify.NotificationHub;
import edu.cmu.cs214.scheduling.pricing.PriceCalculator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Records shipped behavior, including differences from regular bookings. */
class BookingWorkflowCharacterizationTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 5, 9, 0);
    private static final LocalDateTime END = START.plusHours(1);

    private BookingStore store;
    private NotificationHub hub;
    private BookingWorkflow workflow;

    @BeforeEach
    void setUp() {
        store = new BookingStore();
        store.addRoom(new Room("C-200", "Cedar Hall", 20));
        store.addRoom(new Room("W-101", "Willow Room", 8));
        store.addMember(new Member("m-1", "Ada", "ada@rooms.example.edu", MembershipTier.BASIC));
        store.addMember(new Member("m-2", "Grace", "grace@rooms.example.edu",
                MembershipTier.PREMIER));
        hub = new NotificationHub();
        workflow = new BookingWorkflow(store, new PriceCalculator(), hub);
    }

    @ParameterizedTest
    @CsvSource({
            "-60, 0, true",   // An existing booking ends at the requested start.
            "60, 120, true",  // An existing booking starts at the requested end.
            "-61, -1, false", // A one-minute gap before the requested slot.
            "61, 121, false", // A one-minute gap after the requested slot.
            "30, 90, true",   // An actual overlap.
            "0, 60, true"     // Identical slots.
    })
    void recurringConflictsIncludeTouchingEndpointsButExcludeGaps(
            int existingStartMinutes, int existingEndMinutes, boolean skipped) {
        assertTrue(workflow.submit(BookingRequest.regular("C-200", "m-2",
                START.plusMinutes(existingStartMinutes), START.plusMinutes(existingEndMinutes), 4))
                .isAccepted());
        int initialMessages = hub.getOutbox().size();

        BookingOutcome outcome = submitSeries(1);

        assertEquals(!skipped, outcome.isAccepted());
        assertEquals(skipped ? List.of(new TimeSlot(START, END)) : List.of(), outcome.getSkipped());
        assertEquals(skipped ? 0 : 1, outcome.getBooked().size());
        assertEquals(skipped ? 1 : 2, store.activeInRoom("C-200").size());
        assertEquals(initialMessages + (skipped ? 0 : 1), hub.getOutbox().size());
        assertEquals(skipped ? "series S-1: 0 booked, 1 skipped" : "series S-1: 1 booked, 0 skipped",
                outcome.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 26})
    void recurringOccurrenceLimitsAreInclusive(int occurrences) {
        BookingOutcome outcome = submitSeries(occurrences);

        assertTrue(outcome.isAccepted());
        assertTrue(outcome.getSkipped().isEmpty());
        assertEquals(occurrences, outcome.getBooked().size());
        assertEquals(occurrences, store.activeInRoom("C-200").size());
        assertEquals(occurrences, hub.getOutbox().size());
        Booking last = outcome.getBooked().get(occurrences - 1);
        assertEquals(occurrences, last.getOccurrenceIndex());
        assertEquals(START.plusWeeks(occurrences - 1), last.getStart());
        assertEquals("S-1", last.getSeriesId());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 27})
    void invalidOccurrenceCountsRejectWithoutConsumingIds(int occurrences) {
        BookingOutcome outcome = submitSeries(occurrences);

        assertFalse(outcome.isAccepted());
        assertNull(outcome.getBooking());
        assertTrue(outcome.getBooked().isEmpty());
        assertTrue(outcome.getSkipped().isEmpty());
        assertEquals(occurrences < 1 ? "a series needs at least one occurrence"
                : "a series runs at most 26 weeks", outcome.getMessage());
        assertTrue(store.allBookings().isEmpty());
        assertEquals(0, hub.getOutbox().size());

        Booking first = submitSeries(1).getBooking();
        assertEquals(1, first.getId());
        assertEquals("S-1", first.getSeriesId());
    }

    @Test
    void partiallyConflictingSeriesKeepsOriginalWeekNumbersAndNotifiesOnlyBookedSlots() {
        workflow.submit(BookingRequest.blocked("C-200", START.plusWeeks(1), END.plusWeeks(1)));
        int initialMessages = hub.getOutbox().size();

        BookingOutcome outcome = submitSeries(3);

        assertTrue(outcome.isAccepted());
        assertEquals(List.of(new TimeSlot(START.plusWeeks(1), END.plusWeeks(1))),
                outcome.getSkipped());
        assertEquals(List.of(START, START.plusWeeks(2)),
                outcome.getBooked().stream().map(Booking::getStart).toList());
        assertEquals(List.of(1, 3),
                outcome.getBooked().stream().map(Booking::getOccurrenceIndex).toList());
        assertEquals(List.of(2L, 3L), outcome.getBooked().stream().map(Booking::getId).toList());
        assertTrue(outcome.getBooked().stream().allMatch(b -> b.getType() == BookingType.RECURRING
                && "S-1".equals(b.getSeriesId())));
        assertEquals("series S-1: 2 booked, 1 skipped", outcome.getMessage());
        assertEquals(3, store.activeInRoom("C-200").size());
        assertEquals(List.of(confirmation("2026-10-05", "S-1"),
                        confirmation("2026-10-19", "S-1")),
                hub.getOutbox().getMessages().subList(initialMessages, hub.getOutbox().size()));
        assertEquals("Recurring booking #3 in Cedar Hall, occurrence 3 of series S-1, "
                + "2026-10-19T09:00 to 2026-10-19T10:00", workflow.describe(3));
    }

    @Test
    void fullySkippedSeriesConsumesASeriesIdButNoBookingIdAndSendsNothing() {
        workflow.submit(BookingRequest.blocked("C-200", START, END));
        int initialMessages = hub.getOutbox().size();

        BookingOutcome outcome = submitSeries(1);

        assertFalse(outcome.isAccepted());
        assertNull(outcome.getBooking());
        assertTrue(outcome.getBooked().isEmpty());
        assertEquals(List.of(new TimeSlot(START, END)), outcome.getSkipped());
        assertEquals("series S-1: 0 booked, 1 skipped", outcome.getMessage());
        assertEquals(1, store.allBookings().size());
        assertEquals(initialMessages, hub.getOutbox().size());

        Booking next = workflow.submit(BookingRequest.recurring("C-200", "m-1",
                START.plusWeeks(1), END.plusWeeks(1), 1, 6)).getBooking();
        assertEquals(2, next.getId());
        assertEquals("S-2", next.getSeriesId());
    }

    @Test
    void recurringSubmissionIgnoresCancelledRoomBookings() {
        Booking existing = workflow.submit(
                BookingRequest.regular("C-200", "m-2", START, END, 4)).getBooking();
        workflow.cancel(existing.getId(), false);
        int initialMessages = hub.getOutbox().size();

        BookingOutcome outcome = submitSeries(1);

        assertTrue(outcome.isAccepted());
        assertTrue(outcome.getSkipped().isEmpty());
        assertEquals(List.of(outcome.getBooking()), store.activeInRoom("C-200"));
        assertEquals(initialMessages + 1, hub.getOutbox().size());
        assertEquals(2, store.allBookings().size());
    }

    @Test
    void recurringSubmissionAllowsAMemberAlreadyBookedInAnotherRoom() {
        workflow.submit(BookingRequest.regular("W-101", "m-1", START, END, 4));

        BookingOutcome outcome = submitSeries(1);

        assertTrue(outcome.isAccepted());
        assertEquals(1, store.activeInRoom("W-101").size());
        assertEquals(1, store.activeInRoom("C-200").size());
        assertEquals(2, hub.getOutbox().size());
    }

    @Test
    void recurringValidationChecksMemberThenCapacityThenOccurrenceCount() {
        BookingOutcome missingMember = workflow.submit(
                BookingRequest.recurring("C-200", "missing", START, END, 0, 21));
        BookingOutcome tooLarge = workflow.submit(
                BookingRequest.recurring("C-200", "m-1", START, END, 0, 21));
        BookingOutcome invalidCount = workflow.submit(
                BookingRequest.recurring("C-200", "m-1", START, END, 0, 20));

        assertEquals("unknown member missing", missingMember.getMessage());
        assertEquals("room C-200 seats 20, request wants 21", tooLarge.getMessage());
        assertEquals("a series needs at least one occurrence", invalidCount.getMessage());
        assertFalse(missingMember.isAccepted());
        assertFalse(tooLarge.isAccepted());
        assertFalse(invalidCount.isAccepted());
        assertTrue(store.allBookings().isEmpty());
        assertEquals(0, hub.getOutbox().size());
    }

    @Test
    void cancellingMiddleOccurrenceCancelsItAndLaterOccurrencesOnly() {
        List<Booking> series = submitSeries(3).getBooked();
        List<Booking> otherSeries = workflow.submit(BookingRequest.recurring("C-200", "m-2",
                START.plusHours(2), END.plusHours(2), 3, 6)).getBooked();
        int initialMessages = hub.getOutbox().size();

        assertTrue(workflow.cancel(series.get(1).getId(), false));

        assertFalse(store.findBooking(series.get(0).getId()).isCancelled());
        assertTrue(store.findBooking(series.get(1).getId()).isCancelled());
        assertTrue(store.findBooking(series.get(2).getId()).isCancelled());
        assertTrue(otherSeries.stream().noneMatch(Booking::isCancelled));
        assertEquals(4, store.activeInRoom("C-200").size());
        assertEquals(List.of(cancellation("2026-10-12", "S-1"),
                        cancellation("2026-10-19", "S-1")),
                hub.getOutbox().getMessages().subList(initialMessages, hub.getOutbox().size()));
    }

    @Test
    void cancellingFirstOccurrenceCancelsTheWholeSeriesInDateOrder() {
        List<Booking> series = submitSeries(3).getBooked();
        int initialMessages = hub.getOutbox().size();

        assertTrue(workflow.cancel(series.get(0).getId(), false));

        assertTrue(series.stream().allMatch(Booking::isCancelled));
        assertTrue(store.activeInRoom("C-200").isEmpty());
        assertEquals(List.of(cancellation("2026-10-05", "S-1"),
                        cancellation("2026-10-12", "S-1"), cancellation("2026-10-19", "S-1")),
                hub.getOutbox().getMessages().subList(initialMessages, hub.getOutbox().size()));
    }

    @Test
    void cancellationSkipsCancelledOccurrencesAndRepeatedCallsDoNotNotify() {
        List<Booking> series = submitSeries(3).getBooked();
        assertTrue(workflow.cancel(series.get(2).getId(), false));
        int initialMessages = hub.getOutbox().size();

        assertTrue(workflow.cancel(series.get(1).getId(), false));
        assertFalse(workflow.cancel(series.get(1).getId(), false));

        assertFalse(series.get(0).isCancelled());
        assertTrue(series.get(1).isCancelled());
        assertTrue(series.get(2).isCancelled());
        assertEquals(List.of(cancellation("2026-10-12", "S-1")),
                hub.getOutbox().getMessages().subList(initialMessages, hub.getOutbox().size()));
    }

    @Test
    void everyOccurrenceIncludingCancelledOnesPricesTheRemainingActiveSeries() {
        List<Booking> series = submitSeries(3).getBooked();
        assertTrue(workflow.cancel(series.get(1).getId(), false));

        for (Booking occurrence : series) {
            assertEquals(40.0, workflow.priceOf(occurrence.getId()), 0.001);
        }

        assertTrue(workflow.cancel(series.get(0).getId(), false));
        for (Booking occurrence : series) {
            assertEquals(0.0, workflow.priceOf(occurrence.getId()), 0.001);
        }
    }

    private BookingOutcome submitSeries(int occurrences) {
        return workflow.submit(BookingRequest.recurring("C-200", "m-1", START, END,
                occurrences, 6));
    }

    private static String confirmation(String date, String seriesId) {
        return "To: ada@rooms.example.edu | Subject: Occurrence confirmed"
                + " | Room Cedar Hall on " + date + " in series " + seriesId;
    }

    private static String cancellation(String date, String seriesId) {
        return "To: ada@rooms.example.edu | Subject: Occurrence cancelled"
                + " | Room Cedar Hall on " + date + " in series " + seriesId + " is free again";
    }
}
