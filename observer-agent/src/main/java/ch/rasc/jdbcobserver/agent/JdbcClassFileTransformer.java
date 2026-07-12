package ch.rasc.jdbcobserver.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.sql.ConnectionBuilder;
import java.sql.Driver;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import javax.sql.PooledConnection;

final class JdbcClassFileTransformer implements ClassFileTransformer {

	private static final String CONNECT_DESCRIPTOR = "(Ljava/lang/String;Ljava/util/Properties;)Ljava/sql/Connection;";

	private static final String BUILD_DESCRIPTOR = "()Ljava/sql/Connection;";

	private static final String CONNECTION_DESCRIPTOR = "Ljava/sql/Connection;";

	private static final Set<String> JDBC_FACTORY_TYPES = Set.of("java/sql/Driver", "java/sql/ConnectionBuilder",
			"javax/sql/DataSource", "javax/sql/PooledConnection", "javax/sql/XAConnection");

	private static final ClassDesc CONNECTION = ClassDesc.of("java.sql.Connection");

	private static final ClassDesc STRING = ClassDesc.of("java.lang.String");

	private static final ClassDesc PROPERTIES = ClassDesc.of("java.util.Properties");

	private static final ClassDesc INTERCEPTOR = ClassDesc.of("ch.rasc.jdbcobserver.agent.ConnectionInterceptor");

	private static final ClassDesc INVOCATION = ClassDesc
		.of("ch.rasc.jdbcobserver.agent.ConnectionInterceptor$Invocation");

	private static final MethodTypeDesc ENTER = MethodTypeDesc.of(INVOCATION);

	private static final MethodTypeDesc EXIT = MethodTypeDesc.of(CONNECTION, CONNECTION, INVOCATION);

	private static final MethodTypeDesc EXIT_DRIVER = MethodTypeDesc.of(CONNECTION, CONNECTION, INVOCATION, STRING,
			PROPERTIES);

	private static final MethodTypeDesc EXIT_EXCEPTION = MethodTypeDesc.of(ClassDesc.ofDescriptor("V"), INVOCATION);

	private final Instrumentation instrumentation;

	private final Set<String> bootstrapTypes = ConcurrentHashMap.newKeySet();

	private final Set<String> allKnownTypes = ConcurrentHashMap.newKeySet();

	private final Map<ClassLoader, Set<String>> loaderTypes = Collections.synchronizedMap(new WeakHashMap<>());

	JdbcClassFileTransformer(Instrumentation instrumentation) {
		this.instrumentation = instrumentation;
		this.bootstrapTypes.addAll(JDBC_FACTORY_TYPES);
		this.allKnownTypes.addAll(JDBC_FACTORY_TYPES);
	}

	@Override
	public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (isIgnored(className)) {
			return null;
		}
		try {
			var classFile = ClassFile.of();
			var model = classFile.parse(classfileBuffer);
			if (!isJdbcFactory(loader, model, classBeingRedefined) || !isHelperVisible(loader)) {
				return null;
			}
			if (model.methods().stream().noneMatch(JdbcClassFileTransformer::isConnectionFactoryMethod)) {
				return null;
			}
			ensureAgentIsReadable(module);
			return classFile.transformClass(model, (classBuilder, element) -> {
				if (element instanceof MethodModel method && isConnectionFactoryMethod(method)) {
					classBuilder.transformMethod(method, instrument(method));
				}
				else {
					classBuilder.with(element);
				}
			});
		}
		catch (Exception | LinkageError ex) {
			System.err.println("[jdbc-observer] could not instrument " + className + ": " + ex);
			return null;
		}
	}

	static boolean isJdbcFactory(Class<?> type) {
		return Driver.class.isAssignableFrom(type) || DataSource.class.isAssignableFrom(type)
				|| PooledConnection.class.isAssignableFrom(type) || ConnectionBuilder.class.isAssignableFrom(type);
	}

	private boolean isJdbcFactory(ClassLoader loader, ClassModel model, Class<?> classBeingRedefined) {
		if (classBeingRedefined != null) {
			return isJdbcFactory(classBeingRedefined);
		}
		var knownTypes = knownTypes(loader);
		String typeName = model.thisClass().asInternalName();
		boolean candidate = JDBC_FACTORY_TYPES.contains(typeName)
				|| model.superclass()
					.map(entry -> knownTypes.contains(entry.asInternalName())
							|| this.allKnownTypes.contains(entry.asInternalName()))
					.orElse(false)
				|| model.interfaces()
					.stream()
					.anyMatch(entry -> knownTypes.contains(entry.asInternalName())
							|| this.allKnownTypes.contains(entry.asInternalName()));
		if (candidate) {
			knownTypes.add(typeName);
			this.allKnownTypes.add(typeName);
		}
		return candidate;
	}

	private static boolean isHelperVisible(ClassLoader loader) {
		if (loader == null) {
			return false;
		}
		try {
			return Class.forName(ConnectionInterceptor.class.getName(), false, loader) == ConnectionInterceptor.class;
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return false;
		}
	}

	private Set<String> knownTypes(ClassLoader loader) {
		if (loader == null) {
			return this.bootstrapTypes;
		}
		synchronized (this.loaderTypes) {
			return this.loaderTypes.computeIfAbsent(loader, ignored -> {
				var types = ConcurrentHashMap.<String>newKeySet();
				types.addAll(JDBC_FACTORY_TYPES);
				return types;
			});
		}
	}

	private void ensureAgentIsReadable(Module module) {
		var agentModule = ConnectionInterceptor.class.getModule();
		if (module != null && module.isNamed() && !module.canRead(agentModule)) {
			this.instrumentation.redefineModule(module, Set.of(agentModule), Map.of(), Map.of(), Set.of(), Map.of());
		}
	}

	private static MethodTransform instrument(MethodModel method) {
		boolean driverConnect = method.methodName().equalsString("connect");
		return MethodTransform.transformingCode(CodeTransform.ofStateful(() -> {
			var state = new InstrumentationState(driverConnect);
			CodeTransform body = state::accept;
			return body.andThen(CodeTransform.endHandler(state::finish));
		}));
	}

	private static boolean isConnectionFactoryMethod(MethodModel method) {
		String name = method.methodName().stringValue();
		String descriptor = method.methodType().stringValue();
		return (name.equals("connect") && descriptor.equals(CONNECT_DESCRIPTOR))
				|| (name.equals("getConnection") && descriptor.endsWith(")" + CONNECTION_DESCRIPTOR))
				|| (name.equals("build") && descriptor.equals(BUILD_DESCRIPTOR));
	}

	private static boolean isIgnored(String className) {
		return className == null || className.startsWith("ch/rasc/jdbcobserver/agent/") || className.startsWith("java/")
				|| className.startsWith("jdk/") || className.startsWith("sun/");
	}

	private static final class InstrumentationState {

		private final boolean driverConnect;

		private int invocationSlot = -1;

		private java.lang.classfile.Label tryStart;

		private InstrumentationState(boolean driverConnect) {
			this.driverConnect = driverConnect;
		}

		private void accept(java.lang.classfile.CodeBuilder builder, java.lang.classfile.CodeElement element) {
			if (this.invocationSlot < 0) {
				this.invocationSlot = builder.allocateLocal(TypeKind.REFERENCE);
				builder.invokestatic(INTERCEPTOR, "enter", ENTER).storeLocal(TypeKind.REFERENCE, this.invocationSlot);
				this.tryStart = builder.newBoundLabel();
			}
			if (element instanceof ReturnInstruction instruction && instruction.typeKind() == TypeKind.REFERENCE) {
				builder.loadLocal(TypeKind.REFERENCE, this.invocationSlot);
				if (this.driverConnect) {
					builder.loadLocal(TypeKind.REFERENCE, builder.parameterSlot(0));
					builder.loadLocal(TypeKind.REFERENCE, builder.parameterSlot(1));
					builder.invokestatic(INTERCEPTOR, "exit", EXIT_DRIVER);
				}
				else {
					builder.invokestatic(INTERCEPTOR, "exit", EXIT);
				}
			}
			builder.with(element);
		}

		private void finish(java.lang.classfile.CodeBuilder builder) {
			if (this.invocationSlot < 0) {
				return;
			}
			var tryEnd = builder.newBoundLabel();
			var handler = builder.newBoundLabel();
			builder.loadLocal(TypeKind.REFERENCE, this.invocationSlot);
			builder.invokestatic(INTERCEPTOR, "exitException", EXIT_EXCEPTION);
			builder.athrow();
			builder.exceptionCatchAll(this.tryStart, tryEnd, handler);
		}

	}

}
