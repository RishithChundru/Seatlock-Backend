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
class OrganizerFlowTest {
    @Autowired OrganizerService organizers;
    @Autowired Access access;
    @Autowired EventService events;
    @Autowired BookingService bookings;
    @Autowired UserRepository users;
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookingRepo;
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

        // Gate staff scan this organizer's tickets, and nobody else's.
        AppUser gate = user("gate", AppUser.Role.USER);
        organizers.addStaff(host.getId(), gate.getEmail().toUpperCase());
        assertThat(users.findById(gate.getId()).orElseThrow().getRole()).isEqualTo(AppUser.Role.STAFF);
        assertThat(organizers.staff(host.getId())).extracting(s -> s.email()).containsExactly(gate.getEmail());
        assertThatThrownBy(() -> organizers.addStaff(host.getId(), "nobody-" + run + "@test.local")).hasMessageContaining("No Seatlock account");

        String own = ticketFor(mine.id());
        String other = ticketFor(platform.id());
        assertThat(bookings.checkIn(own, null, gate.getId()).result()).isEqualTo("ADMITTED");
        assertThatThrownBy(() -> bookings.checkIn(other, null, gate.getId())).hasMessageContaining("isn't for one of your events");
        assertThatThrownBy(() -> bookings.checkIn(other, null, host.getId())).hasMessageContaining("isn't for one of your events");
        assertThat(bookings.checkIn(other, null, superAdmin.getId()).result()).isEqualTo("ADMITTED");

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

        // Removing staff takes their access away.
        organizers.removeStaff(host.getId(), gate.getId());
        assertThat(users.findById(gate.getId()).orElseThrow().getRole()).isEqualTo(AppUser.Role.USER);
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
