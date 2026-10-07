package edu.cmu.cs214.scheduling.workflow;

import edu.cmu.cs214.scheduling.domain.Booking;
import edu.cmu.cs214.scheduling.domain.BookingOutcome;
import edu.cmu.cs214.scheduling.domain.BookingRequest;
import edu.cmu.cs214.scheduling.domain.BookingStore;
import edu.cmu.cs214.scheduling.domain.BookingType;
import edu.cmu.cs214.scheduling.domain.Room;
import edu.cmu.cs214.scheduling.domain.TimeSlot;
import edu.cmu.cs214.scheduling.notify.NotificationHub;
import edu.cmu.cs214.scheduling.pricing.PriceCalculator;

/** Rules for administrative room holds. */
final class BlockedBookingHandler extends BookingHandler {

    BlockedBookingHandler(BookingStore store, PriceCalculator calculator, NotificationHub hub) {
        super(store, calculator, hub);
    }

    @Override
    BookingOutcome submit(BookingRequest request, Room room) {
        TimeSlot slot = request.slot();
        if (!slot.start().toLocalDate().equals(slot.end().toLocalDate())) {
            return BookingOutcome.rejected("a block must stay inside one day");
        }
        if (roomHasOverlap(room.getId(), slot)) {
            return BookingOutcome.rejected("room " + room.getId()
                    + " cannot be blocked at " + slot.start());
        }

        Booking block = new Booking(store.nextBookingId(), room.getId(), null, slot,
                BookingType.BLOCKED, null, 0);
        store.save(block);
        publish(block, FACILITIES_CONTACT, "Room blocked",
                "Room " + room.getName() + " held from " + slot.start() + " to " + slot.end());
        return BookingOutcome.confirmed(block, "blocked " + room.getId());
    }

    @Override
    boolean cancel(Booking booking, String roomName, boolean adminOverride) {
        if (!adminOverride) {
            return false;
        }
        booking.cancel();
        publish(booking, FACILITIES_CONTACT, "Block released",
                "Room " + roomName + " released from " + booking.getStart()
                        + " to " + booking.getEnd());
        return true;
    }

    @Override
    double priceOf(Booking booking) {
        return 0.0;
    }

    @Override
    String describe(Booking booking, String roomName) {
        return describeSlot(booking, roomName, "Blocked slot") + ", admin hold";
    }
}
