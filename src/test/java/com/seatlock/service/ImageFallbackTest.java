package com.seatlock.service;

import com.seatlock.domain.Event;
import com.seatlock.domain.EventImage;
import com.seatlock.domain.Repositories.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Object storage down (e.g. a paused free project): the upload still succeeds, into Postgres. */
class ImageFallbackTest {
    @Test
    void failedObjectStorageUploadFallsBackToPostgres() {
        var events = mock(EventRepository.class);
        var images = mock(EventImageRepository.class);
        var store = mock(S3ImageStore.class);
        var event = new Event("Show", "Hall, Pune", "Concert", Instant.now().plusSeconds(86400), 50000, null);
        when(events.findById(7L)).thenReturn(Optional.of(event));
        when(store.isEnabled()).thenReturn(true);
        when(store.put(any(), any(), any())).thenThrow(new RuntimeException("503 Service Unavailable"));

        var service = new EventService(events, mock(SeatRepository.class), mock(SeatHoldService.class), images,
                mock(SeatRows.class), mock(BookingRepository.class), mock(WaitlistRepository.class), store, "https://cdn.test", mock(BookingService.class));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0};
        var dto = service.setImage(7L, png);

        var saved = ArgumentCaptor.forClass(EventImage.class);
        verify(images).save(saved.capture());
        assertThat(saved.getValue().getContentType()).isEqualTo("image/png");
        assertThat(event.getImageKey()).isNull();
        assertThat(dto.imageUrl()).startsWith("/api/events/");
    }
}
