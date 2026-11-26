package com.seatlock.service;

import com.seatlock.api.Dtos.HoldResponse;
import com.seatlock.domain.Repositories.SeatRepository;
import com.seatlock.domain.Seat;
import com.seatlock.service.BookingService.SeatUnavailableException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Checkout holds in Redis: SET hold:seat:{id} {userId} NX EX 300.
 * NX makes the hold atomic (one winner per seat); EX frees abandoned holds without a cleanup job.
 * The DB optimistic lock stays the final guarantee if Redis is ever wrong (e.g. a hold expires mid-payment).
 */
@Service
public class SeatHoldService {
    public static final Duration TTL = Duration.ofMinutes(5);
    public static final int MAX_HELD_PER_EVENT = 10;

    // Delete only if the hold is still ours, so we never release someone else's newer hold.
    private static final RedisScript<Long> RELEASE_IF_OWNER = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    private final SeatRepository seats;

    public SeatHoldService(StringRedisTemplate redis, SeatRepository seats) {
        this.redis = redis;
        this.seats = seats;
    }

    static String key(Long seatId) { return "hold:seat:" + seatId; }

    public HoldResponse hold(Long userId, Long eventId, List<Long> seatIds) {
        List<Seat> eventSeats = seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId);
        Map<Long, Seat> byId = new HashMap<>();
        eventSeats.forEach(s -> byId.put(s.getId(), s));
        if (new HashSet<>(seatIds).size() != seatIds.size() || !byId.keySet().containsAll(seatIds))
            throw new IllegalArgumentException("Seats do not belong to this event");
        if (seatIds.stream().anyMatch(id -> byId.get(id).getStatus() != Seat.Status.AVAILABLE))
            throw new SeatUnavailableException();

        Map<Long, Long> holders = holders(eventSeats.stream().map(Seat::getId).toList());
        String me = userId.toString();
        long alreadyMine = holders.values().stream().filter(userId::equals).count();
        long fresh = seatIds.stream().filter(id -> !userId.equals(holders.get(id))).count();
        if (alreadyMine + fresh > MAX_HELD_PER_EVENT)
            throw new IllegalArgumentException("You can hold at most " + MAX_HELD_PER_EVENT + " seats per event");

        List<Long> acquired = new ArrayList<>();
        for (Long id : seatIds) {
            boolean won = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(id), me, TTL));
            if (!won && me.equals(redis.opsForValue().get(key(id)))) {
                redis.expire(key(id), TTL); // re-holding our own seat refreshes the timer
                won = true;
            }
            if (!won) {
                acquired.forEach(a -> release(userId, a)); // all-or-nothing
                throw new SeatUnavailableException();
            }
            acquired.add(id);
        }
        return new HoldResponse(acquired, Instant.now().plus(TTL));
    }

    public void release(Long userId, Long seatId) {
        redis.execute(RELEASE_IF_OWNER, List.of(key(seatId)), userId.toString());
    }

    /** seatId -> userId holding it, for seats with a live hold. */
    public Map<Long, Long> holders(List<Long> seatIds) {
        if (seatIds.isEmpty()) return Map.of();
        List<String> values = redis.opsForValue().multiGet(seatIds.stream().map(SeatHoldService::key).toList());
        Map<Long, Long> out = new HashMap<>();
        for (int i = 0; i < seatIds.size(); i++)
            if (values != null && values.get(i) != null) out.put(seatIds.get(i), Long.valueOf(values.get(i)));
        return out;
    }

    /** Booking is allowed only for seats that are free or held by this user. */
    public void assertBookable(Long userId, List<Long> seatIds) {
        if (holders(seatIds).values().stream().anyMatch(h -> !h.equals(userId)))
            throw new SeatUnavailableException();
    }
}
