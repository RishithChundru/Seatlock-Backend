package com.seatlock.messaging;

import com.seatlock.service.OrganizerService.Notice;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Organizer and staff emails: only after the change commits, and off the request thread (slow SMTP never blocks it). */
@Component
class NoticeMailer {
    private final Mailer mailer;

    NoticeMailer(Mailer mailer) { this.mailer = mailer; }

    @Async
    @TransactionalEventListener
    public void on(Notice n) { mailer.notice(n.to(), n.subject(), n.headline(), n.text(), n.cta(), n.path()); }
}
