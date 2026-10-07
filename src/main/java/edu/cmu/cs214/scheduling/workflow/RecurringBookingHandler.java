package edu.cmu.cs214.scheduling.workflow;

import edu.cmu.cs214.scheduling.domain.Booking;
import edu.cmu.cs214.scheduling.domain.BookingOutcome;
import edu.cmu.cs214.scheduling.domain.BookingRequest;
import edu.cmu.cs214.scheduling.domain.BookingStore;
import edu.cmu.cs214.scheduling.domain.BookingType;
import edu.cmu.cs214.scheduling.domain.Member;
import edu.cmu.cs214.scheduling.domain.Room;
import edu.cmu.cs214.scheduling.domain.TimeSlot;
import edu.cmu.cs214.scheduling.notify.NotificationHub;
import edu.cmu.cs214.scheduling.pricing.PriceCalculator;

import java.util.ArrayList;
import java.util.List;

/** Rules for weekly series, including partial submission and forward cancellation. */
final class RecurringBookingHandler extends BookingHandler {

    private static final int MAX_SERIES_WEEKS = 26;

    RecurringBookingHandler(BookingStore store, PriceCalculator calculator, NotificationHub hub) {
        super(store, calculator, hub);
    }

    @Override
    BookingOutcome submit(BookingRequest request, Room room) {
        Member member = store.findMember(request.memberId());
        BookingOutcome rejection = validateMemberAndCapacity(request, room, member);
        if (rejection != null) {
            return rejection;
        }
        if (request.occurrences() < 1) {
            return BookingOutcome.rejected("a series needs at least one occurrence");
        }
        if (request.occurrences() > MAX_SERIES_WEEKS) {
            return BookingOutcome.rejected("a series runs at most " + MAX_SERIES_WEEKS + " weeks");
        }

        String seriesId = store.nextSeriesId();
        List<Booking> booked = new ArrayList<>();
        List<TimeSlot> skipped = new ArrayList<>();
        for (int week = 0; week < request.occurrences(); week++) {
            TimeSlot slot = request.slot().plusWeeks(week);
            if (roomHasRecurringConflict(room.getId(), slot)) {
                skipped.add(slot);
                continue;
            }

            Booking occurrence = new Booking(store.nextBookingId(), room.getId(), member.getId(),
                    slot, BookingType.RECURRING, seriesId, week + 1);
            store.save(occurrence);
            booked.add(occurrence);
            publish(occurrence, member.getEmail(), "Occurrence confirmed",
                    "Room " + room.getName() + " on " + slot.start().toLocalDate()
                            + " in series " + seriesId);
        }
        return BookingOutcome.series(booked, skipped, "series " + seriesId + ": "
                + booked.size() + " booked, " + skipped.size() + " skipped");
    }

    @Override
    boolean cancel(Booking booking, String roomName, boolean adminOverride) {
        Member member = store.findMember(booking.getMemberId());
        for (Booking occurrence : store.seriesOccurrences(booking.getSeriesId())) {
            if (occurrence.isCancelled() || occurrence.getStart().compareTo(booking.getStart()) < 0) {
                continue;
            }
            occurrence.cancel();
            publish(occurrence, recipientFor(member), "Occurrence cancelled",
                    "Room " + roomName + " on " + occurrence.getStart().toLocalDate()
                            + " in series " + occurrence.getSeriesId() + " is free again");
        }
        return true;
    }

    @Override
    double priceOf(Booking booking) {
        Member member = store.findMember(booking.getMemberId());
        double total = 0.0;
        for (Booking occurrence : store.seriesOccurrences(booking.getSeriesId())) {
            if (!occurrence.isCancelled()) {
                total += calculator.price(occurrence, member);
            }
        }
        return total;
    }

    @Override
    String describe(Booking booking, String roomName) {
        return "Recurring booking #" + booking.getId() + " in " + roomName
                + ", occurrence " + booking.getOccurrenceIndex() + " of series "
                + booking.getSeriesId() + ", " + booking.getStart() + " to " + booking.getEnd();
    }

    /** Preserve the recurring rule: even touching endpoints conflict. */
    private boolean roomHasRecurringConflict(String roomId, TimeSlot slot) {
        for (Booking existing : store.activeInRoom(roomId)) {
            if (existing.getStart().compareTo(slot.end()) <= 0
                    && slot.start().compareTo(existing.getEnd()) <= 0) {
                return true;
            }
        }
        return false;
    }
}
