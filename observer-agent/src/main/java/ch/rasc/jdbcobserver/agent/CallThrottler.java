package ch.rasc.jdbcobserver.agent;

import ch.rasc.jdbcobserver.core.ControlCodec;

final class CallThrottler {

	private final Sleeper sleeper;

	private volatile int delayMillis;

	CallThrottler() {
		this(Thread::sleep);
	}

	CallThrottler(Sleeper sleeper) {
		this.sleeper = sleeper;
	}

	void configure(int milliseconds) {
		if (milliseconds < 0 || milliseconds > ControlCodec.MAX_THROTTLE_MILLIS) {
			throw new IllegalArgumentException(
					"Throttle must be between 0 and " + ControlCodec.MAX_THROTTLE_MILLIS + " milliseconds");
		}
		this.delayMillis = milliseconds;
	}

	int delayMillis() {
		return this.delayMillis;
	}

	void throttle() {
		int delay = this.delayMillis;
		if (delay == 0) {
			return;
		}
		try {
			this.sleeper.sleep(delay);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@FunctionalInterface
	interface Sleeper {

		void sleep(long milliseconds) throws InterruptedException;

	}

}
