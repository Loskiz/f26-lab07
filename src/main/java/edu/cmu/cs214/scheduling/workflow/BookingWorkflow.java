package edu.cmu.cs214.scheduling.workflow;

import edu.cmu.cs214.scheduling.domain.Booking;
import edu.cmu.cs214.scheduling.domain.BookingOutcome;
import edu.cmu.cs214.scheduling.domain.BookingRequest;
import edu.cmu.cs214.scheduling.domain.BookingStore;
import edu.cmu.cs214.scheduling.domain.BookingType;
import edu.cmu.cs214.scheduling.domain.Room;
import edu.cmu.cs214.scheduling.notify.NotificationHub;
import edu.cmu.cs214.scheduling.pricing.PriceCalculator;

import java.util.Map;

/**
 * The front door of the scheduler. Every booking that reaches the store goes
 * through here, and every notification the scheduler sends is published from
 * here or its booking-type handlers.
 */
public class BookingWorkflow {

    private final BookingStore store;
    private final Map<BookingType, BookingHandler> handlers;

    public BookingWorkflow(BookingStore store, PriceCalculator calculator, NotificationHub hub) {
        if (store == null || calculator == null || hub == null) {
            throw new IllegalArgumentException("workflow collaborators must not be null");
        }
        this.store = store;
        this.handlers = Map.of(
                BookingType.REGULAR, new RegularBookingHandler(store, calculator, hub),
                BookingType.RECURRING, new RecurringBookingHandler(store, calculator, hub),
                BookingType.BLOCKED, new BlockedBookingHandler(store, calculator, hub));
    }

    /**
     * Validates a request, writes what it can, and reports what it did.
     *
     * @return an outcome naming every booking written and every slot passed over
     */
    public BookingOutcome submit(BookingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Room room = store.findRoom(request.roomId());
        if (room == null) {
            return BookingOutcome.rejected("unknown room " + request.roomId());
        }
        return handlerFor(request.type()).submit(request, room);
    }

    /**
     * Releases a booking.
     *
     * @param adminOverride set by callers acting with facilities authority
     * @return true when something was released
     */
    public boolean cancel(long bookingId, boolean adminOverride) {
        Booking booking = store.findBooking(bookingId);
        if (booking == null || booking.isCancelled()) {
            return false;
        }
        return handlerFor(booking.getType()).cancel(booking, roomName(booking), adminOverride);
    }

    /** What the holder owes for a booking, in dollars. */
    public double priceOf(long bookingId) {
        Booking booking = store.findBooking(bookingId);
        if (booking == null) {
            throw new IllegalArgumentException("unknown booking " + bookingId);
        }
        return handlerFor(booking.getType()).priceOf(booking);
    }

    /** A one-line summary for schedules and confirmation screens. */
    public String describe(long bookingId) {
        Booking booking = store.findBooking(bookingId);
        if (booking == null) {
            return "Unknown booking #" + bookingId;
        }
        return handlerFor(booking.getType()).describe(booking, roomName(booking));
    }

    private BookingHandler handlerFor(BookingType type) {
        return handlers.get(type);
    }

    private String roomName(Booking booking) {
        Room room = store.findRoom(booking.getRoomId());
        return room == null ? booking.getRoomId() : room.getName();
    }
}
