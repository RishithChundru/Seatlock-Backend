package com.seatlock.service;

import com.seatlock.api.Dtos.OrganizerDto;
import com.seatlock.api.Dtos.OrganizerRequest;
import com.seatlock.api.Dtos.StaffDto;
import com.seatlock.api.Dtos.UserDto;
import com.seatlock.domain.AppUser;
import com.seatlock.domain.Organizer;
import com.seatlock.domain.OrganizerLogo;
import com.seatlock.domain.Repositories.OrganizerLogoRepository;
import org.springframework.beans.factory.annotation.Value;
import com.seatlock.domain.Repositories.OrganizerRepository;
import com.seatlock.domain.Repositories.UserRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Organizer onboarding (apply -> super admin approves or rejects -> organizer), suspension, and gate staff.
 * Role changes reach the user's JWT at their next token refresh; Access checks the database meanwhile.
 */
@Service
public class OrganizerService {
    /** An email to send after the transaction commits (see NoticeMailer). path is relative to the frontend. */
    public record Notice(String to, String subject, String headline, String text, String cta, String path) {}

    public static final int MAX_LOGO_BYTES = 1024 * 1024;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrganizerService.class);

    private final OrganizerRepository organizers;
    private final UserRepository users;
    private final ApplicationEventPublisher publisher;
    private final OrganizerLogoRepository logos;
    private final S3ImageStore objectStore;
    private final String objectPublicUrl;

    public OrganizerService(OrganizerRepository organizers, UserRepository users, ApplicationEventPublisher publisher,
                            OrganizerLogoRepository logos, S3ImageStore objectStore,
                            @Value("${app.images.s3.public-url}") String objectPublicUrl) {
        this.logos = logos;
        this.objectStore = objectStore;
        this.objectPublicUrl = objectPublicUrl.replaceAll("/+$", "");
        this.organizers = organizers;
        this.users = users;
        this.publisher = publisher;
    }

    /** The session's user, with their organizer status so the frontend can show "pending" (read-only) or unlock. */
    @Transactional(readOnly = true)
    public UserDto userDto(AppUser u) {
        String status = u.getRole() != AppUser.Role.ORGANIZER ? null
                : organizers.findByUserId(u.getId()).map(o -> o.getStatus().name()).orElse(null);
        return UserDto.of(u, status);
    }

    /**
     * Organizer sign-up: creates an ORGANIZER account and its PENDING application in one go. Until the super admin
     * approves it the account is read-only (Access only lets approved organizers act). Admins are emailed.
     */
    @Transactional
    public AppUser signup(String email, String name, String passwordHash, OrganizerRequest r) {
        if (users.existsByEmail(email)) throw new EmailTakenException();
        if (organizers.existsByNameIgnoreCase(r.name().trim())) throw new IllegalArgumentException("Another organizer already uses that name");
        AppUser u = users.save(new AppUser(email, name, passwordHash, AppUser.Role.ORGANIZER));
        Organizer o = new Organizer(u.getId());
        details(o, r);
        organizers.save(o);
        notice(u, "We received your organizer request", "Thanks for applying to host on Seatlock.",
                "We'll review " + o.getName() + " and email you when it's approved, usually within a day. Until then you can sign in "
                        + "and look around your dashboard.", "View status", "/organizer");
        tellAdmins(o, "New organizer request: ");
        return u;
    }

    public static class EmailTakenException extends RuntimeException {
        public EmailTakenException() { super("Email already registered"); }
    }

    private void details(Organizer o, OrganizerRequest r) {
        o.details(r.name().trim(), r.about().trim(), r.city().trim(), r.contactEmail().trim().toLowerCase(Locale.ROOT),
                indianPhone(r.contactPhone()), blankToNull(r.website()));
    }

    /** Stored and shown as "+91 98765 43210". The request was already validated as +91 plus 10 digits. */
    static String indianPhone(String raw) {
        String d = raw.replaceAll("[^0-9]", "").substring(2); // drop the 91
        return "+91 " + d.substring(0, 5) + " " + d.substring(5);
    }

    private void tellAdmins(Organizer o, String subjectPrefix) {
        for (AppUser admin : users.findAllByRole(AppUser.Role.ADMIN))
            notice(admin, subjectPrefix + o.getName(), "An organizer is waiting for review.",
                    o.getName() + " (" + o.getCity() + ", " + o.getContactEmail() + ") wants to host events on Seatlock. "
                            + "Approve it or request changes from the Organizers page.", "Review request", "/admin/organizers");
    }

    @Transactional(readOnly = true)
    public Optional<OrganizerDto> mine(Long userId) {
        return organizers.findByUserId(userId).map(o -> dto(o, users.findById(o.getUserId()).orElseThrow()));
    }

    /** Organizer accounts only: fix a rejected application, or edit the profile customers see on events. */
    @Caching(evict = {@CacheEvict(value = EventService.LIST_CACHE, allEntries = true),
                      @CacheEvict(value = EventService.ITEM_CACHE, allEntries = true)}) // "Hosted by" shows on events
    @Transactional
    public OrganizerDto apply(Long userId, OrganizerRequest r) {
        AppUser u = users.findById(userId).orElseThrow();
        if (u.getRole() == AppUser.Role.ADMIN) throw new IllegalArgumentException("You're the super admin: create events from the dashboard.");
        if (u.getRole() == AppUser.Role.STAFF)
            throw new IllegalArgumentException("Staff accounts can't host events. Ask your organizer to remove you first.");
        if (u.getRole() == AppUser.Role.USER)
            throw new IllegalArgumentException("Customer accounts can't host events. Create a separate organizer account instead.");
        String name = r.name().trim();
        if (organizers.existsByNameIgnoreCaseAndUserIdNot(name, userId))
            throw new IllegalArgumentException("Another organizer already uses that name");

        Organizer o = organizers.findByUserId(userId).orElse(null);
        boolean fresh = o == null;
        if (fresh) o = new Organizer(userId);
        else if (o.getStatus() == Organizer.Status.SUSPENDED)
            throw new IllegalArgumentException("This organizer account is suspended. Contact Seatlock support.");
        details(o, r);
        boolean resubmitted = o.getStatus() == Organizer.Status.REJECTED;
        o.resubmit();
        organizers.save(o);
        if (fresh || resubmitted) tellAdmins(o, resubmitted ? "Organizer request updated: " : "New organizer request: ");
        return dto(o, u);
    }

    @Transactional(readOnly = true)
    public List<OrganizerDto> list(String status) {
        List<Organizer> all = status == null || status.isBlank() ? organizers.findAllByOrderByCreatedAtDesc()
                : organizers.findAllByStatusOrderByCreatedAtAsc(Organizer.Status.valueOf(status.toUpperCase(Locale.ROOT)));
        return all.stream().map(o -> dto(o, users.findById(o.getUserId()).orElseThrow())).toList();
    }

    @Transactional
    public OrganizerDto approve(Long id) {
        Organizer o = find(id);
        if (o.getStatus() != Organizer.Status.PENDING) throw new IllegalArgumentException("Only pending applications can be approved");
        return activate(o, "You're approved to host events on Seatlock",
                "Create your first event from the dashboard. Add gate staff there too, so they can scan tickets.");
    }

    @Transactional
    public OrganizerDto reinstate(Long id) {
        Organizer o = find(id);
        if (o.getStatus() != Organizer.Status.SUSPENDED) throw new IllegalArgumentException("Only suspended organizers can be reinstated");
        return activate(o, "Your organizer account is active again", "You can manage your events and sell tickets again.");
    }

    private OrganizerDto activate(Organizer o, String headline, String text) {
        o.approve();
        AppUser u = users.findById(o.getUserId()).orElseThrow();
        u.setRole(AppUser.Role.ORGANIZER);
        notice(u, headline, headline + ".", text, "Open dashboard", "/admin");
        return dto(o, u);
    }

    @Transactional
    public OrganizerDto reject(Long id, String note) {
        Organizer o = find(id);
        if (o.getStatus() != Organizer.Status.PENDING) throw new IllegalArgumentException("Only pending applications can be rejected");
        o.reject(note.trim());
        AppUser u = users.findById(o.getUserId()).orElseThrow();
        notice(u, "Your organizer application needs changes", "Your application wasn't approved yet.",
                "Reason: " + note.trim() + " You can edit your application and submit it again.", "Edit application", "/organizer");
        return dto(o, u);
    }

    /** Stops the organizer (and their staff, see Access) from managing or scanning; their account turns read-only. Events stay on sale. */
    @Transactional
    public OrganizerDto suspend(Long id, String note) {
        Organizer o = find(id);
        if (o.getStatus() != Organizer.Status.APPROVED) throw new IllegalArgumentException("Only approved organizers can be suspended");
        o.suspend(note == null || note.isBlank() ? null : note.trim());
        AppUser u = users.findById(o.getUserId()).orElseThrow();
        notice(u, "Your organizer account is suspended", "Your organizer account is suspended.",
                (o.getReviewNote() == null ? "" : "Reason: " + o.getReviewNote() + " ") + "Contact Seatlock support to sort it out.",
                "Open Seatlock", "/");
        return dto(o, u);
    }

    @Transactional(readOnly = true)
    public List<StaffDto> staff(Long ownerUserId) {
        return users.findAllByStaffOfOrderByNameAsc(activeOf(ownerUserId).getId()).stream()
                .map(u -> new StaffDto(u.getId(), u.getName(), u.getEmail())).toList();
    }

    /** The person needs a Seatlock account first; they become STAFF and see only the check-in scanner. */
    @Transactional
    public StaffDto addStaff(Long ownerUserId, String email) {
        Organizer o = activeOf(ownerUserId);
        AppUser u = users.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("No Seatlock account uses that email. Ask them to sign up first."));
        if (u.getId().equals(ownerUserId)) throw new IllegalArgumentException("You can already scan your own events");
        if (u.getRole() != AppUser.Role.USER) throw new IllegalArgumentException("That account is already an organizer, staff member or admin");
        u.setRole(AppUser.Role.STAFF);
        u.setStaffOf(o.getId());
        notice(u, "You're gate staff for " + o.getName(), "You're gate staff for " + o.getName() + ".",
                "Sign out and back in, then open Check-in to scan tickets for their events.", "Open check-in", "/admin/checkin");
        return new StaffDto(u.getId(), u.getName(), u.getEmail());
    }

    @Transactional
    public void removeStaff(Long ownerUserId, Long staffUserId) {
        Organizer o = activeOf(ownerUserId);
        AppUser u = users.findById(staffUserId).filter(x -> o.getId().equals(x.getStaffOf()))
                .orElseThrow(() -> new NoSuchElementException("Staff member not found"));
        u.setRole(AppUser.Role.USER);
        u.setStaffOf(null);
    }

    /**
     * Logo upload (optional, any time except while suspended, also while pending). Not @Transactional: with
     * object storage the upload is network I/O, which must not hold a pooled DB connection.
     */
    @Caching(evict = {@CacheEvict(value = EventService.LIST_CACHE, allEntries = true),
                      @CacheEvict(value = EventService.ITEM_CACHE, allEntries = true)}) // "Hosted by" shows it
    public OrganizerDto setLogo(Long userId, byte[] data) {
        Organizer o = editable(userId);
        if (data.length == 0 || data.length > MAX_LOGO_BYTES) throw new IllegalArgumentException("Logo must be under 1 MB");
        String type = EventService.sniffImageType(data); // magic bytes, never the client's Content-Type
        if (type == null) throw new IllegalArgumentException("Only JPEG, PNG or WebP images are allowed");
        String oldKey = o.getLogoKey();
        String newKey = null;
        if (objectStore.isEnabled()) {
            try {
                newKey = objectStore.putIn("organizers/" + o.getId(), type, data);
            } catch (RuntimeException ex) { // storage down or paused: keep uploads working via Postgres (as for covers)
                log.warn("Object storage upload failed for organizer {} logo, storing it in Postgres: {}", o.getId(), ex.getMessage());
            }
        }
        if (newKey != null) {
            o.logoChanged(newKey);
            if (logos.existsById(o.getId())) logos.deleteById(o.getId());
        } else {
            logos.save(new OrganizerLogo(o.getId(), type, data));
            o.logoChanged(null);
        }
        organizers.save(o);
        if (oldKey != null && !oldKey.equals(o.getLogoKey())) objectStore.delete(oldKey);
        return dto(o, users.findById(userId).orElseThrow());
    }

    @Caching(evict = {@CacheEvict(value = EventService.LIST_CACHE, allEntries = true),
                      @CacheEvict(value = EventService.ITEM_CACHE, allEntries = true)})
    public OrganizerDto removeLogo(Long userId) {
        Organizer o = editable(userId);
        String key = o.getLogoKey();
        if (logos.existsById(o.getId())) logos.deleteById(o.getId());
        o.logoRemoved();
        organizers.save(o);
        objectStore.delete(key); // after the DB change; a leftover object is harmless
        return dto(o, users.findById(userId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public OrganizerLogo logo(Long organizerId) {
        return logos.findById(organizerId).orElseThrow(() -> new NoSuchElementException("No logo"));
    }

    /** Public object-storage URL, a versioned API URL (Postgres-stored), or null when there's no logo. */
    public static String logoUrl(Organizer o, String objectPublicUrl) {
        if (o.getLogoKey() != null) return objectPublicUrl + "/" + o.getLogoKey();
        return o.getLogoUpdatedAt() == null ? null : "/api/organizers/" + o.getId() + "/logo?v=" + o.getLogoUpdatedAt().toEpochMilli();
    }

    private Organizer editable(Long userId) {
        Organizer o = organizers.findByUserId(userId).orElseThrow(() -> new NoSuchElementException("Organizer not found"));
        if (o.getStatus() == Organizer.Status.SUSPENDED) throw new IllegalArgumentException("This organizer account is suspended");
        return o;
    }

    private Organizer activeOf(Long ownerUserId) {
        return organizers.findByUserId(ownerUserId).filter(Organizer::isApproved)
                .orElseThrow(() -> new Access.ForbiddenException("Your organizer account isn't active"));
    }

    private Organizer find(Long id) {
        return organizers.findById(id).orElseThrow(() -> new NoSuchElementException("Organizer not found"));
    }

    private void notice(AppUser to, String subject, String headline, String text, String cta, String path) {
        publisher.publishEvent(new Notice(to.getEmail(), subject, headline, text, cta, path));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private OrganizerDto dto(Organizer o, AppUser u) {
        return new OrganizerDto(o.getId(), o.getName(), o.getAbout(), o.getCity(), o.getContactEmail(), o.getContactPhone(),
                o.getWebsite(), o.getStatus().name(), o.getReviewNote(), o.getCreatedAt(), o.getReviewedAt(), u.getName(), u.getEmail(),
                logoUrl(o, objectPublicUrl));
    }
}
