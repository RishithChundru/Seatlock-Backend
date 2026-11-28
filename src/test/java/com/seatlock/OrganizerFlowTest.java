package com.seatlock;

import com.seatlock.api.Dtos.CreateEventRequest;
import com.seatlock.api.Dtos.EventDto;
import com.seatlock.api.Dtos.OrganizerRequest;
import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.BookingRepository;
import com.seatlock.domain.Repositories.SeatRepository;
import com.seatlock.domain.Repositories.UserRepository;
import com.seatlock.service.Access;
import com.seatlock.service.BookingService;
import com.seatlock.service.EventService;
import com.seatlock.service.OrganizerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Apply -> review -> host events -> add gate staff -> scan only your own tickets -> suspension. */
@SpringBootTest
@Import(TestcontainersConfig.class)
@org.springframework.test.context.event.RecordApplicationEvents
class OrganizerFlowTest {
    @Autowired OrganizerService organizers;
    @Autowired Access access;
    @Autowired EventService events;
    @Autowired BookingService bookings;
    @Autowired UserRepository users;
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookingRepo;
    @Autowired org.springframework.test.context.event.ApplicationEvents published;
    @org.springframework.beans.factory.annotation.Value("${test.mailpit.api}") String mailpitApi;

    private final String run = UUID.randomUUID().toString().substring(0, 8);

    private AppUser user(String name, AppUser.Role role) {
        return users.save(new AppUser(name + "-" + UUID.randomUUID() + "@test.local", name, "x", role));
    }

    private OrganizerRequest profile(String name) {
        return new OrganizerRequest(name, "We run intimate live music nights across the city.", "Pune",
                "hello@" + run + ".example", "+91 98765 43210", "https://example.com");
    }

    private EventDto event(Long organizerId) {
        return events.create(new CreateEventRequest("Gig " + UUID.randomUUID(), "Hall, Pune", "Concert",
                Instant.now().plus(Duration.ofDays(10)), 50000, 1, 3, null), organizerId);
    }

    /** The raw token from the invite email this test triggered (the API never returns it). */
    private String inviteToken(String to) {
        return published.stream(OrganizerService.Notice.class).filter(n -> n.to().equals(to))
                .map(n -> n.path().substring(n.path().indexOf("token=") + 6)).reduce((a, b) -> b).orElseThrow();
    }

    private String ticketFor(Long eventId) throws InterruptedException {
        Long fan = user("fan", AppUser.Role.USER).getId();
        Long seat = seats.findAll().stream().filter(s -> s.getEvent().getId().equals(eventId)).findFirst().orElseThrow().getId();
        Long bookingId = bookings.book(fan, eventId, List.of(seat), null).id();
        for (int i = 0; i < 100; i++) {
            String code = bookingRepo.findById(bookingId).orElseThrow().getTicketCode();
            if (code != null) return code;
            Thread.sleep(100);
        }
        throw new AssertionError("ticket never issued");
    }

    @Test
    void anOrganizerIsReviewedThenHostsAndScansOnlyTheirOwnEvents() throws Exception {
        String name = "Night Owls " + run;

        // Organizer sign-up is its own account type, pending (read-only) until reviewed; names are unique.
        AppUser host = organizers.signup("host-" + run + "@test.local", "Host", "x", profile(name));
        assertThat(host.getRole()).isEqualTo(AppUser.Role.ORGANIZER);
        assertThat(organizers.userDto(host).organizerStatus()).isEqualTo("PENDING");
        var applied = organizers.mine(host.getId()).orElseThrow();
        assertThatThrownBy(() -> organizers.signup("other-" + run + "@test.local", "O", "x", profile(name.toUpperCase())))
                .hasMessageContaining("already uses that name");
        assertThatThrownBy(() -> organizers.signup(host.getEmail(), "Dup", "x", profile("Fresh " + run)))
                .isInstanceOf(OrganizerService.EmailTakenException.class);
        assertThatThrownBy(() -> access.organizerForNewEvent(host.getId())).isInstanceOf(Access.ForbiddenException.class);
        assertThat(access.visibleEvents(host.getId())).isEmpty();

        // Rejected with a reason, fixed and resubmitted, then approved.
        assertThat(organizers.reject(applied.id(), "Please add a real website").status()).isEqualTo("REJECTED");
        assertThat(organizers.apply(host.getId(), profile(name)).status()).isEqualTo("PENDING");
        assertThat(organizers.approve(applied.id()).status()).isEqualTo("APPROVED");
        assertThat(organizers.userDto(users.findById(host.getId()).orElseThrow()).organizerStatus()).isEqualTo("APPROVED");

        // Their event shows who hosts it; a Seatlock event has no host.
        Long orgId = access.organizerForNewEvent(host.getId());
        EventDto mine = event(orgId);
        EventDto platform = event(null);
        assertThat(events.get(mine.id()).host().name()).isEqualTo(name);
        assertThat(events.get(mine.id()).host().contactPhone()).isEqualTo("+91 98765 43210");
        assertThat(platform.host()).isNull();

        // Ownership: the organizer manages only their own events; the super admin manages all.
        AppUser superAdmin = user("super", AppUser.Role.ADMIN);
        access.requireManage(host.getId(), mine.id());
        assertThatThrownBy(() -> access.requireManage(host.getId(), platform.id())).isInstanceOf(NoSuchElementException.class);
        access.requireManage(superAdmin.getId(), mine.id());
        assertThat(access.visibleEvents(host.getId())).extracting(e -> e.getId()).containsExactly(mine.id());
        assertThat(access.visibleEvents(superAdmin.getId())).extracting(e -> e.getId()).contains(mine.id(), platform.id());

        // Gate staff: only emails with no account can be invited; the invite (not an account) is emailed, and the
        // person creates their staff account from it.
        String gateEmail = "gate-" + run + "@test.local";
        organizers.addStaff(host.getId(), "Gate Keeper", gateEmail.toUpperCase());
        assertThat(users.findByEmail(gateEmail)).as("no account until they accept").isEmpty();
        assertThat(organizers.staff(host.getId())).singleElement().satisfies(s -> assertThat(s.status()).isEqualTo("INVITED"));
        assertThatThrownBy(() -> organizers.addStaff(host.getId(), "Taken", user("cust", AppUser.Role.USER).getEmail()))
                .hasMessageContaining("already has a Seatlock account");
        assertThatThrownBy(() -> organizers.addStaff(host.getId(), "Taken", superAdmin.getEmail()))
                .hasMessageContaining("already has a Seatlock account");

        String token = inviteToken(gateEmail);
        assertThat(organizers.inviteInfo(token).organizerName()).isEqualTo(name);
        AppUser gate = organizers.acceptInvite(token, "Gate Keeper", "gate-password-1");
        assertThat(gate.getRole()).isEqualTo(AppUser.Role.STAFF);
        assertThat(gate.getStaffOf()).isEqualTo(applied.id());
        assertThat(organizers.staff(host.getId())).singleElement().satisfies(s -> assertThat(s.status()).isEqualTo("ACTIVE"));
        assertThatThrownBy(() -> organizers.acceptInvite(token, "Again", "another-pass")).hasMessageContaining("invalid or has expired");

        String own = ticketFor(mine.id());
        String other = ticketFor(platform.id());
        assertThat(bookings.checkIn(own, null, gate.getId()).result()).isEqualTo("ADMITTED");
        assertThatThrownBy(() -> bookings.checkIn(other, null, gate.getId())).hasMessageContaining("isn't for one of your events");
        assertThatThrownBy(() -> bookings.checkIn(other, null, host.getId())).hasMessageContaining("isn't for one of your events");
        // The super admin scans Seatlock's own events only, not an organizer's (that's their staff's job).
        assertThatThrownBy(() -> bookings.checkIn(own, null, superAdmin.getId())).hasMessageContaining("isn't for one of your events");
        assertThat(bookings.checkIn(other, null, superAdmin.getId()).result()).isEqualTo("ADMITTED");

        // Dashboard chart: 8 weeks, this week holds the booking; latest bookings show who booked what.
        var insights = bookings.insights(access.visibleEvents(host.getId()));
        assertThat(insights.weeks()).hasSize(8);
        assertThat(insights.weeks().getLast().bookings()).isEqualTo(1);
        assertThat(insights.weeks().getLast().revenueCents()).isEqualTo(50000);
        assertThat(insights.recent()).singleElement().satisfies(r -> {
            assertThat(r.eventTitle()).isEqualTo(mine.title());
            assertThat(r.customer()).isEqualTo("fan");
            assertThat(r.checkedIn()).isTrue();
        });

        // Dashboard numbers count confirmed bookings and check-ins.
        var row = events.manage(access.visibleEvents(host.getId())).getFirst();
        assertThat(row.bookings()).isEqualTo(1);
        assertThat(row.revenueCents()).isEqualTo(50000);
        assertThat(row.checkedIn()).isEqualTo(1);

        // Suspension stops the organizer and their staff at once; reinstating restores both.
        organizers.suspend(applied.id(), "Unpaid invoices");
        assertThatThrownBy(() -> access.organizerForNewEvent(host.getId())).isInstanceOf(Access.ForbiddenException.class);
        assertThatThrownBy(() -> access.requireManage(host.getId(), mine.id())).isInstanceOf(NoSuchElementException.class);
        assertThat(access.canScan(gate.getId(), seats.findAll().stream().filter(s -> s.getEvent().getId().equals(mine.id()))
                .findFirst().orElseThrow().getEvent())).isFalse();
        organizers.reinstate(applied.id());
        access.requireManage(host.getId(), mine.id());

        // Removing staff disables the account; re-adding the same email sends a fresh invite to that account.
        organizers.removeStaff(host.getId(), gate.getId());
        AppUser removed = users.findById(gate.getId()).orElseThrow();
        assertThat(removed.getStaffOf()).isNull();
        assertThat(removed.hasPassword()).as("can't sign in").isFalse();
        organizers.addStaff(host.getId(), "Gate Keeper", gateEmail);
        organizers.acceptInvite(inviteToken(gateEmail), "Gate Keeper", "gate-password-2");
        assertThat(users.findById(gate.getId()).orElseThrow().getStaffOf()).as("same account, reactivated").isEqualTo(applied.id());
    }

    @Test
    void anInvitedCustomerAcceptsOrDeclinesInTheAppAndOrganizersSeeWhoBooked() throws Exception {
        AppUser host = organizers.signup("inv-host-" + run + "@test.local", "Host", "x", profile("Inviters " + run));
        organizers.approve(organizers.mine(host.getId()).orElseThrow().id());

        // Invite goes out to unregistered emails; the people then sign up as customers and see it in the app.
        String yes = "yes-" + run + "@test.local", no = "no-" + run + "@test.local";
        organizers.addStaff(host.getId(), "Yes", yes);
        organizers.addStaff(host.getId(), "No", no);
        AppUser joiner = users.save(new AppUser(yes, "Yes", "x", AppUser.Role.USER));
        AppUser decliner = users.save(new AppUser(no, "No", "x", AppUser.Role.USER));
        assertThat(organizers.pendingInvites(joiner.getId())).singleElement()
                .satisfies(i -> assertThat(i.organizerName()).isEqualTo("Inviters " + run));

        Long noInvite = organizers.pendingInvites(decliner.getId()).getFirst().id();
        assertThatThrownBy(() -> organizers.acceptInviteInApp(joiner.getId(), noInvite)).isInstanceOf(java.util.NoSuchElementException.class);
        organizers.declineInviteInApp(decliner.getId(), noInvite);
        assertThat(users.findById(decliner.getId()).orElseThrow().getRole()).isEqualTo(AppUser.Role.USER);
        assertThat(organizers.pendingInvites(decliner.getId())).isEmpty();

        AppUser staff = organizers.acceptInviteInApp(joiner.getId(), organizers.pendingInvites(joiner.getId()).getFirst().id());
        assertThat(staff.getRole()).isEqualTo(AppUser.Role.STAFF);
        assertThat(staff.getStaffOf()).isEqualTo(access.organizerForNewEvent(host.getId()));
        assertThat(organizers.staff(host.getId())).extracting(s -> s.email() + ":" + s.status())
                .contains(yes + ":ACTIVE", no + ":DECLINED");

        // The organizer sees who booked their event, without the ticket code.
        EventDto ev = event(access.organizerForNewEvent(host.getId()));
        AppUser fan = user("fan", AppUser.Role.USER);
        bookings.book(fan.getId(), ev.id(), List.of(seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(ev.id()).getFirst().getId()), null);
        assertThat(bookings.attendees(ev.id())).singleElement().satisfies(a -> {
            assertThat(a.email()).isEqualTo(fan.getEmail());
            assertThat(a.seats()).containsExactly("A1");
        });
    }

    @Test
    void theSuperAdminIsEmailedAboutEachNewRequest() throws Exception {
        AppUser admin = user("boss", AppUser.Role.ADMIN);
        organizers.signup("req-" + run + "@test.local", "Req", "x", profile("Request Co " + run));
        String query = java.net.URLEncoder.encode("to:\"" + admin.getEmail() + "\"", java.nio.charset.StandardCharsets.UTF_8);
        var http = java.net.http.HttpClient.newHttpClient();
        String found = "";
        for (int i = 0; i < 50 && !found.contains("New organizer request"); i++) {
            Thread.sleep(200); // sent after commit on a worker thread
            found = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(mailpitApi + "/api/v1/search?query=" + query)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        }
        assertThat(found).contains("New organizer request: Request Co " + run);
    }

    @Test
    void phoneNumbersAreIndianMobilesOnlyAndStoredInOneFormat() {
        var v = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        java.util.function.Function<String, Boolean> ok = phone -> v.validate(new OrganizerRequest("Co", "We run intimate live music nights here.",
                "Pune", "a@b.example", phone, "")).isEmpty();
        assertThat(ok.apply("+91 98765 43210")).isTrue();
        assertThat(ok.apply("+919876543210")).isTrue();
        assertThat(ok.apply("+91-98765-43210")).isTrue();
        assertThat(ok.apply("9876543210")).as("needs +91").isFalse();
        assertThat(ok.apply("+1 98765 43210")).as("India only").isFalse();
        assertThat(ok.apply("+91 58765 43210")).as("mobiles start with 6-9").isFalse();
        assertThat(ok.apply("+91 98765 4321")).as("10 digits").isFalse();

        AppUser host = organizers.signup("ph-" + run + "@test.local", "P", "x", new OrganizerRequest("Phone Co " + run,
                "We run intimate live music nights here.", "Pune", "a@b.example", "+919876543210", ""));
        assertThat(organizers.mine(host.getId()).orElseThrow().contactPhone()).isEqualTo("+91 98765 43210");
    }

    @Test
    void anOrganizerCanAddReplaceAndRemoveALogoShownOnTheirEvents() {
        AppUser host = organizers.signup("logo-" + run + "@test.local", "L", "x", profile("Logo Co " + run));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

        // Pending organizers can add one (it's part of sign-up); only real images, at most 1 MB.
        var withLogo = organizers.setLogo(host.getId(), png);
        assertThat(withLogo.logoUrl()).startsWith("/api/organizers/" + withLogo.id() + "/logo?v=");
        assertThat(organizers.logo(withLogo.id()).getData()).isEqualTo(png);
        assertThatThrownBy(() -> organizers.setLogo(host.getId(), "<svg onload=alert(1)>".getBytes()))
                .hasMessageContaining("JPEG, PNG or WebP");
        assertThatThrownBy(() -> organizers.setLogo(host.getId(), new byte[OrganizerService.MAX_LOGO_BYTES + 1]))
                .hasMessageContaining("1 MB");

        // It appears in "Hosted by" on their events.
        organizers.approve(withLogo.id());
        EventDto ev = event(access.organizerForNewEvent(host.getId()));
        assertThat(events.get(ev.id()).host().logoUrl()).isEqualTo(organizers.mine(host.getId()).orElseThrow().logoUrl());

        assertThat(organizers.removeLogo(host.getId()).logoUrl()).isNull();
        assertThatThrownBy(() -> organizers.logo(withLogo.id())).isInstanceOf(NoSuchElementException.class);
        assertThat(events.get(ev.id()).host().logoUrl()).as("event cache evicted").isNull();
        assertThat(organizers.mine(host.getId()).orElseThrow().logoUrl()).as("stored profile").isNull();
    }

    @Test
    void customersCantTurnTheirAccountIntoAnOrganizer() {
        AppUser customer = user("customer", AppUser.Role.USER);
        assertThatThrownBy(() -> organizers.apply(customer.getId(), profile("Sneaky " + run))).hasMessageContaining("Customer accounts");
    }

    @Test
    void staffAndTheSuperAdminCantApplyToHost() {
        AppUser superAdmin = user("super", AppUser.Role.ADMIN);
        assertThatThrownBy(() -> organizers.apply(superAdmin.getId(), profile("Admin Co " + run))).hasMessageContaining("super admin");
        AppUser staff = user("staff", AppUser.Role.STAFF);
        assertThatThrownBy(() -> organizers.apply(staff.getId(), profile("Staff Co " + run))).hasMessageContaining("Staff accounts");
    }
}
