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

/** Rules for a single member-held booking. */
final class RegularBookingHandler extends BookingHandler {

    RegularBookingHandler(BookingStore store, PriceCalculator calculator, NotificationHub hub) {
        super(store, calculator, hub);
    }

    @Override
    BookingOutcome submit(BookingRequest request, Room room) {
        Member member = store.findMember(request.memberId());
        BookingOutcome rejection = validateMemberAndCapacity(request, room, member);
        if (rejection != null) {
            return rejection;
        }

        TimeSlot slot = request.slot();
        if (roomHasOverlap(room.getId(), slot)) {
            return BookingOutcome.rejected("room " + room.getId()
                    + " is already booked at " + slot.start());
        }
        if (memberHasOverlap(member, slot)) {
            return BookingOutcome.rejected(member.getId()
                    + " already holds a booking at " + slot.start());
        }

        Booking booking = new Booking(store.nextBookingId(), room.getId(), member.getId(),
                slot, BookingType.REGULAR, null, 0);
        store.save(booking);
        publish(booking, member.getEmail(), "Booking confirmed",
                "Room " + room.getName() + " from " + slot.start() + " to " + slot.end());
        return BookingOutcome.confirmed(booking,
                "booked " + room.getId() + " for " + member.getId());
    }

    @Override
    boolean cancel(Booking booking, String roomName, boolean adminOverride) {
        Member member = store.findMember(booking.getMemberId());
        booking.cancel();
        publish(booking, recipientFor(member), "Booking cancelled",
                "Room " + roomName + " on " + booking.getStart() + " is free again");
        return true;
    }

    @Override
    double priceOf(Booking booking) {
        Member member = store.findMember(booking.getMemberId());
        return calculator.price(booking, member);
    }

    @Override
    String describe(Booking booking, String roomName) {
        return describeSlot(booking, roomName, "Regular booking");
    }

    private boolean memberHasOverlap(Member member, TimeSlot slot) {
        for (Booking held : store.allBookings()) {
            if (!held.isCancelled() && member.getId().equals(held.getMemberId())
                    && overlaps(held.getSlot(), slot)) {
                return true;
            }
        }
        return false;
    }
}
