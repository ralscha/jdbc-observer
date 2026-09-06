package ch.rasc.jdbcobserver.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSmokeIT {

	@TempDir
	Path temporary;

	@Test
	void observesJdbcWithThePackagedAgentInServerMode() throws Exception {
		run(SmokeApplication.class, false, "SMOKE_OK");
	}

	@Test
	void exchangesControlsAndEventsInReverseMode() throws Exception {
		run(ReverseThrottleSmokeApplication.class, true, "REVERSE_SMOKE_OK");
	}

	@Test
	void capturesBatchReuseFailuresAndGenericExecuteCounts() throws Exception {
		run(TelemetrySmokeApplication.class, false, "TELEMETRY_SMOKE_OK");
	}

	private void run(Class<?> application, boolean reverse, String marker) throws Exception {
		int port;
		try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = server.getLocalPort();
		}
		var agent = Path.of(System.getProperty("observer.agentJar")).toAbsolutePath();
		assertTrue(Files.isRegularFile(agent), "Packaged agent missing: " + agent);
		String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
		var java = Path.of(System.getProperty("java.home"), "bin", executable);
		String options = "port=" + port + ",stackTraceThresholdMs=0";
		if (reverse) {
			options += ",mode=client,host=" + InetAddress.getLoopbackAddress().getHostAddress();
		}
		var log = this.temporary.resolve(application.getSimpleName() + ".log");
		var process = new ProcessBuilder(java.toString(), "-javaagent:" + agent + "=" + options, "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				application.getName(), Integer.toString(port))
			.redirectErrorStream(true)
			.redirectOutput(log.toFile())
			.start();
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "Agent smoke test timed out: " + read(log));
			String output = read(log);
			assertEquals(0, process.exitValue(), output);
			assertTrue(output.contains(marker), output);
		}
		finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (java.io.IOException ex) {
			return ex.toString();
		}
	}

}
