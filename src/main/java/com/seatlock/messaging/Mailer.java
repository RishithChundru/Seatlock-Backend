package com.seatlock.messaging;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

/**
 * Transactional email over SMTP (Mailpit locally, any SMTP provider such as Resend in production).
 * Best-effort on purpose: a mail outage is logged, never allowed to fail ticket issuing or a booking.
 * Every user- or admin-supplied value is HTML-escaped before it goes into a template.
 */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final JavaMailSender sender;
    private final String from;
    private final String frontendUrl;

    public Mailer(JavaMailSender sender, @Value("${app.mail.from}") String from, @Value("${app.frontend-url}") String frontendUrl) {
        this.sender = sender;
        this.from = from;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    public void ticketIssued(String to, String name, String eventTitle, Instant startsAt, String venue, List<String> seats, String code) {
        send(to, "Your tickets for " + eventTitle, layout("You're in, " + htmlEscape(name) + ".",
                row("Event", eventTitle) + row("When", WHEN.format(startsAt)) + row("Where", venue)
                        + row("Seats", String.join(", ", seats))
                        + "<p style=\"margin:24px 0 4px;color:#5A6577;font-size:13px\">Ticket code</p>"
                        + "<p style=\"margin:0;font:600 22px/1.2 monospace;letter-spacing:2px\">" + htmlEscape(code) + "</p>"
                        + "<p style=\"margin:16px 0 0;color:#5A6577;font-size:13px\">Show the QR code on your Tickets page at the gate.</p>",
                "View tickets", frontendUrl + "/bookings"));
    }

    public void waitlistOffer(String to, String eventTitle, Long eventId, String seat, Instant expiresAt) {
        send(to, "A seat opened up for " + eventTitle, layout("Good news: seat " + htmlEscape(seat) + " is yours to book.",
                row("Event", eventTitle) + row("Held until", WHEN.format(expiresAt))
                        + "<p style=\"margin:16px 0 0;color:#5A6577;font-size:13px\">After that it goes back on general sale.</p>",
                "Book it now", frontendUrl + "/events/" + eventId));
    }

    /** refunded: a real (Razorpay) payment was refunded. eventCancelled: the organiser called the event off. */
    public void bookingCancelled(String to, String eventTitle, List<String> seats, int refundPaise, boolean refunded, boolean eventCancelled) {
        String amount = "₹" + String.format(Locale.ENGLISH, "%,d", refundPaise / 100);
        String p = "<p style=\"margin:16px 0 0;color:#334155;font-size:14px;line-height:1.5\">";
        String note = (eventCancelled ? p + "We're sorry: the organiser has cancelled this event, so these tickets are no longer valid.</p>" : "")
                + (refunded ? p + "Your refund of " + amount + " has been started to your original payment method. "
                        + "Banks usually show it within 5 to 7 working days.</p>"
                            : p + "No payment was taken for this booking, so there is nothing to refund.</p>");
        send(to, (eventCancelled ? "Event cancelled: " : "Booking cancelled: ") + eventTitle + (refunded ? " (refund started)" : ""),
                layout(eventCancelled ? "This event has been cancelled." : "Your booking is cancelled.",
                        row("Event", eventTitle) + row("Seats", String.join(", ", seats)) + (refunded ? row("Refund", amount) : "") + note,
                        "Find another event", frontendUrl + "/"));
    }

    /** One-paragraph notice (organizer review, staff access). Every value is escaped. */
    public void notice(String to, String subject, String headline, String text, String cta, String path) {
        send(to, subject, layout(htmlEscape(headline),
                "<p style=\"margin:0;color:#334155;font-size:15px;line-height:1.55\">" + htmlEscape(text) + "</p>",
                htmlEscape(cta), frontendUrl + path));
    }

    private void send(String to, String subject, String html) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("Email '{}' sent to {}", subject, to);
        } catch (MailException | MessagingException e) {
            log.warn("Email '{}' to {} failed: {}", subject, to, e.getMessage());
        }
    }

    private static String row(String label, String value) {
        return "<tr><td style=\"padding:6px 16px 6px 0;color:#5A6577;font-size:13px\">" + label + "</td>"
                + "<td style=\"padding:6px 0;font-size:15px\">" + htmlEscape(value) + "</td></tr>";
    }

    /** body = table rows first, then optional paragraphs; the rows get wrapped in a table. */
    private static String layout(String headline, String body, String cta, String href) {
        int split = body.indexOf("<p");
        String table = split < 0 ? body : body.substring(0, split);
        String rest = split < 0 ? "" : body.substring(split);
        return "<!doctype html><html><body style=\"margin:0;background:#E0F7FA;font-family:Arial,Helvetica,sans-serif;color:#212121\">"
                + "<div style=\"max-width:520px;margin:0 auto;padding:32px 20px\">"
                + "<p style=\"margin:0 0 24px;font:800 20px Arial,Helvetica,sans-serif;letter-spacing:-0.02em\">Seat<span style=\"color:#FF6F61\">lock</span></p>"
                + "<div style=\"background:#fff;border:1px solid #E2E8F0;border-radius:12px;padding:28px\">"
                + "<h1 style=\"margin:0 0 20px;font:700 22px/1.3 Arial,Helvetica,sans-serif;letter-spacing:-0.01em\">" + headline + "</h1>"
                + "<table style=\"border-collapse:collapse\">" + table + "</table>" + rest
                + "<a href=\"" + htmlEscape(href) + "\" style=\"display:inline-block;margin-top:24px;background:#FF6F61;color:#212121;"
                + "text-decoration:none;padding:12px 20px;border-radius:8px;font-weight:600\">" + cta + "</a>"
                + "</div><p style=\"margin:20px 0 0;color:#64748B;font-size:12px\">You're getting this because you booked on Seatlock.</p>"
                + "</div></body></html>";
    }
}
