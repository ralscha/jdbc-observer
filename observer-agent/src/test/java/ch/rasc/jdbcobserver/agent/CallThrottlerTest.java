package ch.rasc.jdbcobserver.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.rasc.jdbcobserver.core.ControlCodec;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CallThrottlerTest {

	@Test
	void appliesConfiguredDelayAndClearsIt() {
		var slept = new AtomicLong();
		var throttler = new CallThrottler(slept::addAndGet);

		throttler.configure(125);
		throttler.throttle();
		assertEquals(125, slept.get());
		assertEquals(125, throttler.delayMillis());

		throttler.configure(0);
		throttler.throttle();
		assertEquals(125, slept.get());
		assertEquals(0, throttler.delayMillis());
	}

	@Test
	void rejectsOutOfRangeDelays() {
		var throttler = new CallThrottler(milliseconds -> {
		});

		assertThrows(IllegalArgumentException.class, () -> throttler.configure(-1));
		assertThrows(IllegalArgumentException.class, () -> throttler.configure(ControlCodec.MAX_THROTTLE_MILLIS + 1));
	}

	@Test
	void preservesInterruptStatusWhenDelayIsInterrupted() {
		var throttler = new CallThrottler(milliseconds -> {
			throw new InterruptedException();
		});
		throttler.configure(1);

		try {
			throttler.throttle();
			assertTrue(Thread.currentThread().isInterrupted());
		}
		finally {
			Thread.interrupted();
		}
	}

}
