package ch.rasc.jdbcobserver.agent;

import java.lang.instrument.Instrumentation;

public final class JdbcObserverAgent {

	private static boolean installed;

	private JdbcObserverAgent() {
	}

	public static void premain(String args, Instrumentation instrumentation) {
		install(args, instrumentation, false);
	}

	public static void agentmain(String args, Instrumentation instrumentation) {
		install(args, instrumentation, true);
	}

	private static synchronized void install(String args, Instrumentation instrumentation,
			boolean retransformLoadedClasses) {
		if (installed) {
			System.err.println("[jdbc-observer] agent is already active");
			return;
		}
		try {
			AgentRuntime.start(args);
			var transformer = new JdbcClassFileTransformer(instrumentation);
			instrumentation.addTransformer(transformer, instrumentation.isRetransformClassesSupported());
			installed = true;
			if (retransformLoadedClasses && instrumentation.isRetransformClassesSupported()) {
				for (Class<?> type : instrumentation.getAllLoadedClasses()) {
					try {
						if (instrumentation.isModifiableClass(type) && JdbcClassFileTransformer.isJdbcFactory(type)) {
							instrumentation.retransformClasses(type);
						}
					}
					catch (Exception | LinkageError ex) {
						System.err.println("[jdbc-observer] could not retransform " + type.getName() + ": " + ex);
					}
				}
			}
			System.err.println(AgentRuntime.enabled() ? "[jdbc-observer] agent active; UI port " + AgentRuntime.port()
					: "[jdbc-observer] instrumentation installed; telemetry transport is disabled");
		}
		catch (Exception | LinkageError ex) {
			System.err.println("[jdbc-observer] agent installation failed; application will continue: " + ex);
		}
	}

}
