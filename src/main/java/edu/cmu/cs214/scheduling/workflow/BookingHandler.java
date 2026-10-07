package edu.cmu.cs214.scheduling.workflow;

import edu.cmu.cs214.scheduling.domain.Booking;
import edu.cmu.cs214.scheduling.domain.BookingOutcome;
import edu.cmu.cs214.scheduling.domain.BookingRequest;
import edu.cmu.cs214.scheduling.domain.BookingStore;
import edu.cmu.cs214.scheduling.domain.Member;
import edu.cmu.cs214.scheduling.domain.Room;
import edu.cmu.cs214.scheduling.domain.TimeSlot;
import edu.cmu.cs214.scheduling.notify.NotificationHub;
import edu.cmu.cs214.scheduling.notify.NotificationMessage;
import edu.cmu.cs214.scheduling.pricing.PriceCalculator;

/** Internal booking-type operations and helpers shared by their implementations. */
abstract class BookingHandler {

    protected static final String FACILITIES_CONTACT = "facilities@rooms.example.edu";

    protected final BookingStore store;
    protected final PriceCalculator calculator;
    private final NotificationHub hub;

    BookingHandler(BookingStore store, PriceCalculator calculator, NotificationHub hub) {
        this.store = store;
        this.calculator = calculator;
        this.hub = hub;
    }

    abstract BookingOutcome submit(BookingRequest request, Room room);

    abstract boolean cancel(Booking booking, String roomName, boolean adminOverride);

    abstract double priceOf(Booking booking);

    abstract String describe(Booking booking, String roomName);

    /** Returns the first rejection, or null when member and capacity checks pass. */
    protected final BookingOutcome validateMemberAndCapacity(
            BookingRequest request, Room room, Member member) {
        if (member == null) {
            return BookingOutcome.rejected("unknown member " + request.memberId());
        }
        if (request.attendees() > room.getCapacity()) {
            return BookingOutcome.rejected("room " + room.getId() + " seats "
                    + room.getCapacity() + ", request wants " + request.attendees());
        }
        return null;
    }

    /** Regular bookings and blocks allow touching endpoints. */
    protected final boolean roomHasOverlap(String roomId, TimeSlot slot) {
        for (Booking existing : store.activeInRoom(roomId)) {
            if (overlaps(existing.getSlot(), slot)) {
                return true;
            }
        }
        return false;
    }

    protected static boolean overlaps(TimeSlot first, TimeSlot second) {
        return first.start().compareTo(second.end()) < 0
                && second.start().compareTo(first.end()) < 0;
    }

    protected final void publish(Booking booking, String recipient, String subject, String body) {
        hub.publish(new NotificationMessage(recipient, subject, body, booking.getStart()));
    }

    protected static String recipientFor(Member member) {
        return member == null ? FACILITIES_CONTACT : member.getEmail();
    }

    protected static String describeSlot(Booking booking, String roomName, String label) {
        return label + " #" + booking.getId() + " in " + roomName
                + " from " + booking.getStart() + " to " + booking.getEnd();
    }
}
