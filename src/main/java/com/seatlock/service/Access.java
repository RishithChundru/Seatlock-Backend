package com.seatlock.service;

import com.seatlock.domain.AppUser;
import com.seatlock.domain.Event;
import com.seatlock.domain.Organizer;
import com.seatlock.domain.Repositories.EventRepository;
import com.seatlock.domain.Repositories.OrganizerRepository;
import com.seatlock.domain.Repositories.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Who may do what with which event. Decided from the database, not the JWT's role claim, so a suspension
 * takes effect at once even while the user's access token still says ORGANIZER.
 *   ADMIN (super admin): every event. ORGANIZER: their own events, while approved.
 *   STAFF: scan tickets for their organizer's events, while that organizer is approved.
 */
@Service
public class Access {
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) { super(message); }
    }

    private final UserRepository users;
    private final OrganizerRepository organizers;
    private final EventRepository events;

    public Access(UserRepository users, OrganizerRepository organizers, EventRepository events) {
        this.users = users;
        this.organizers = organizers;
        this.events = events;
    }

    /** The approved organizer this user acts for (their own, or their employer's for staff), if any. */
    private Organizer activeOrganizer(AppUser u) {
        Organizer o = switch (u.getRole()) {
            case ORGANIZER -> organizers.findByUserId(u.getId()).orElse(null);
            case STAFF -> u.getStaffOf() == null ? null : organizers.findById(u.getStaffOf()).orElse(null);
            default -> null;
        };
        return o != null && o.isApproved() ? o : null;
    }

    private AppUser user(Long userId) { return users.findById(userId).orElseThrow(() -> new ForbiddenException("Not allowed")); }

    /** For a new event: null = hosted by Seatlock (super admin); otherwise the organizer's id. */
    public Long organizerForNewEvent(Long userId) {
        AppUser u = user(userId);
        if (u.getRole() == AppUser.Role.ADMIN) return null;
        Organizer o = u.getRole() == AppUser.Role.ORGANIZER ? activeOrganizer(u) : null;
        if (o == null) throw new ForbiddenException("Your organizer account isn't active");
        return o.getId();
    }

    /** Create/edit/cancel. Someone else's event looks exactly like a missing one. */
    public void requireManage(Long userId, Long eventId) {
        Event e = events.live(eventId);
        AppUser u = user(userId);
        if (u.getRole() == AppUser.Role.ADMIN) return;
        Organizer o = u.getRole() == AppUser.Role.ORGANIZER ? activeOrganizer(u) : null;
        if (o == null || !Objects.equals(o.getId(), e.getOrganizerId())) throw new NoSuchElementException("Event not found");
    }

    /** The super admin scans only Seatlock's own events; organizer events are their staff's job (and revenue). */
    public boolean canScan(Long userId, Event e) {
        AppUser u = user(userId);
        if (u.getRole() == AppUser.Role.ADMIN) return e.getOrganizerId() == null;
        Organizer o = activeOrganizer(u);
        return o != null && Objects.equals(o.getId(), e.getOrganizerId());
    }

    /** The events this user's dashboard and scanner show. */
    public List<Event> visibleEvents(Long userId) {
        AppUser u = user(userId);
        if (u.getRole() == AppUser.Role.ADMIN) return events.findAllByCancelledAtIsNullOrderByStartsAtAsc();
        Organizer o = activeOrganizer(u);
        return o == null ? List.of() : events.findAllByOrganizerIdAndCancelledAtIsNullOrderByStartsAtAsc(o.getId());
    }
}
