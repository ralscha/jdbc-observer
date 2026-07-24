package ch.rasc.jdbcobserver.ui;

import ch.rasc.jdbcobserver.core.EventCodec;
import ch.rasc.jdbcobserver.core.SqlEvent;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

public final class ObserverApp extends JFrame {

	private static final int INCOMING_CAPACITY = 50_000;

	private static final int DRAIN_BATCH_SIZE = 1_000;

	private static final int MAX_INCOMING_CHARACTERS = 16_000_000;

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
		.withZone(ZoneId.systemDefault());

	private final EventTableModel model = new EventTableModel();

	private final JTable table = new JTable(this.model);

	private final JScrollPane tableScrollPane = new JScrollPane(this.table);

	private final TableRowSorter<EventTableModel> sorter = new TableRowSorter<>(this.model);

	private final JTextArea detail = new JTextArea();

	private final JTextField filter = new JTextField();

	private final JLabel status = new JLabel("Disconnected");

	private final JLabel metrics = new JLabel("0 events   \u2022   0 ms execute");

	private final AtomicBoolean paused = new AtomicBoolean();

	private final ArrayBlockingQueue<IncomingEvent> incoming = new ArrayBlockingQueue<>(INCOMING_CAPACITY);

	private final AtomicLong droppedIncoming = new AtomicLong();

	private int incomingCharacters;

	private final JSpinner minimumDuration = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 3_600_000.0, 1.0));

	private final JCheckBox sqlStatementsOnly = new JCheckBox("SQL statements only");

	private final JCheckBox highlightMatches = new JCheckBox("Highlight", true);

	private final JCheckBox autoScroll = new JCheckBox("Auto-scroll", true);

	private final int port;

	private final String host;

	private final boolean listen;

	private boolean darkMode;

	private final float baseFontSize;

	private float uiFontSize;

	private JLabel title;

	private UUID currentSession;

	private TransactionTimelineDialog transactionDialog;

	private ObserverApp(String host, int port, boolean listen, boolean darkMode) {
		super("JDBC Observer");
		this.host = host;
		this.port = port;
		this.listen = listen;
		this.darkMode = darkMode;
		var defaultFont = UIManager.getFont("defaultFont");
		this.baseFontSize = defaultFont != null ? defaultFont.getSize2D() : 13f;
		this.uiFontSize = this.baseFontSize;
		build();
		connect();
	}

	private void build() {
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setMinimumSize(new Dimension(1040, 680));
		setSize(1400, 860);
		setLocationByPlatform(true);
		var root = new JPanel(new BorderLayout(0, 12));
		root.setBorder(new EmptyBorder(16, 18, 14, 18));
		setContentPane(root);
		this.title = new JLabel("JDBC Observer");
		this.title.setFont(this.title.getFont().deriveFont(Font.BOLD, this.uiFontSize + 11f));
		var subtitle = new JLabel("Live SQL telemetry");
		subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
		var heading = new JPanel(new BorderLayout());
		var names = new JPanel(new GridLayout(2, 1));
		names.add(this.title);
		names.add(subtitle);
		heading.add(names);
		heading.add(this.metrics, BorderLayout.EAST);
		root.add(heading, BorderLayout.NORTH);
		this.filter.putClientProperty("JTextField.placeholderText", "Filter SQL, thread, connection, or type...");
		this.filter.getDocument().addDocumentListener((SimpleDocumentListener) event -> applyFilter());
		var pause = new JToggleButton("Pause");
		pause.addActionListener(event -> {
			this.paused.set(pause.isSelected());
			if (pause.isSelected()) {
				clearIncoming();
			}
			pause.setText(pause.isSelected() ? "Resume" : "Pause");
		});
		var clear = new JButton("Clear");
		clear.addActionListener(event -> clearEvents());
		var export = new JButton("Export CSV");
		export.addActionListener(event -> export(export));
		var analyze = new JButton("Group & analyze");
		analyze.addActionListener(event -> showAnalysis());
		var transactions = new JButton("Transactions");
		transactions.addActionListener(event -> showTransactions());
		var nPlusOneSettings = new JButton("N+1 settings");
		nPlusOneSettings.addActionListener(event -> showNPlusOneSettings());
		var theme = new JButton(this.darkMode ? "Light mode" : "Dark mode");
		theme.addActionListener(event -> toggleTheme(theme));
		this.minimumDuration.setToolTipText("Minimum observed duration in milliseconds");
		this.minimumDuration.addChangeListener(event -> applyFilter());
		this.sqlStatementsOnly.addActionListener(event -> applyFilter());
		this.highlightMatches.addActionListener(event -> this.table.repaint());
		this.autoScroll.setToolTipText("Keep the latest event visible as new events arrive");
		this.autoScroll.addActionListener(event -> {
			if (this.autoScroll.isSelected()) {
				scrollToLatestEvent();
			}
		});
		var bar = new JPanel(new BorderLayout(8, 0));
		bar.add(this.filter);
		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(new JLabel("Min ms"));
		buttons.add(this.minimumDuration);
		buttons.add(this.sqlStatementsOnly);
		buttons.add(this.highlightMatches);
		buttons.add(this.autoScroll);
		buttons.add(pause);
		buttons.add(clear);
		var zoomOut = new JButton("A-");
		zoomOut.setToolTipText("Zoom out (Ctrl+-)");
		zoomOut.addActionListener(event -> zoom(-1));
		var zoomIn = new JButton("A+");
		zoomIn.setToolTipText("Zoom in (Ctrl+=)");
		zoomIn.addActionListener(event -> zoom(1));
		buttons.add(zoomOut);
		buttons.add(zoomIn);
		buttons.add(theme);
		buttons.add(nPlusOneSettings);
		buttons.add(transactions);
		buttons.add(analyze);
		buttons.add(export);
		bar.add(buttons, BorderLayout.EAST);
		this.table.setRowSorter(this.sorter);
		this.table.setRowHeight(28);
		this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		this.table.setShowVerticalLines(false);
		this.table.getSelectionModel().addListSelectionListener(this::selected);
		this.table.setDefaultRenderer(Object.class, new EventRenderer());
		this.table.setDefaultRenderer(String.class, new EventRenderer());
		this.table.setDefaultRenderer(SqlEvent.Kind.class, new EventRenderer());
		this.table.setDefaultRenderer(Double.class, new DurationRenderer());
		this.table.setDefaultRenderer(Instant.class, new TimeRenderer());
		this.detail.setEditable(false);
		this.detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(this.uiFontSize)));
		this.detail.setLineWrap(true);
		this.detail.setWrapStyleWord(true);
		this.detail.setBorder(new EmptyBorder(12, 12, 12, 12));
		var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, this.tableScrollPane, new JScrollPane(this.detail));
		split.setResizeWeight(.72);
		split.setBorder(null);
		var center = new JPanel(new BorderLayout(0, 10));
		center.add(bar, BorderLayout.NORTH);
		center.add(split);
		root.add(center);
		this.status.setBorder(new EmptyBorder(3, 0, 0, 0));
		root.add(this.status, BorderLayout.SOUTH);
		var inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		var actionMap = getRootPane().getActionMap();
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "zoom-in");
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK), "zoom-in");
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "zoom-out");
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK), "zoom-reset");
		actionMap.put("zoom-in", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				zoom(1);
			}
		});
		actionMap.put("zoom-out", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				zoom(-1);
			}
		});
		actionMap.put("zoom-reset", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				zoom(0);
			}
		});
		new Timer(50, event -> drainIncoming()).start();
	}

	private void connect() {
		if (this.listen) {
			Thread.ofVirtual().name("observer-listener").start(this::listen);
			return;
		}
		Thread.ofVirtual().name("observer-client").start(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				try (var socket = new Socket()) {
					socket.connect(new InetSocketAddress(this.host, this.port), 2_000);
					try (var input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
						readEvents(input, "Connected to " + this.host + ":" + this.port);
					}
				}
				catch (IOException | RuntimeException ex) {
					setStatus("\u25cf Waiting for agent on " + this.host + ":" + this.port, new Color(251, 191, 36));
					try {
						Thread.sleep(1_200);
					}
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
		});
	}

	private void listen() {
		try (var server = new ServerSocket(this.port)) {
			setStatus("\u25cf Listening for agents on port " + this.port, new Color(251, 191, 36));
			while (!Thread.currentThread().isInterrupted()) {
				try (var socket = server.accept();
						var input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
					readEvents(input, "Agent connected from " + socket.getRemoteSocketAddress());
				}
				catch (IOException | RuntimeException ex) {
					setStatus("\u25cf Agent disconnected; listening on " + this.port, new Color(251, 191, 36));
				}
			}
		}
		catch (IOException ex) {
			setStatus("\u25cf Cannot listen on port " + this.port + ": " + ex.getMessage(), Color.RED);
		}
	}

	private void readEvents(DataInputStream input, String connectedText) throws IOException {
		UUID sessionId = EventCodec.readHeader(input);
		setStatus("\u25cf " + connectedText, new Color(74, 222, 128));
		while (true) {
			var event = EventCodec.read(input);
			if (!this.paused.get()) {
				enqueue(new IncomingEvent(sessionId, event, estimatedCharacters(event)));
			}
		}
	}

	private void enqueue(IncomingEvent event) {
		if (event.characters() > MAX_INCOMING_CHARACTERS) {
			this.droppedIncoming.incrementAndGet();
			return;
		}
		synchronized (this.incoming) {
			while (this.incoming.remainingCapacity() == 0
					|| this.incomingCharacters + event.characters() > MAX_INCOMING_CHARACTERS) {
				var removed = this.incoming.poll();
				if (removed == null) {
					break;
				}
				this.incomingCharacters -= removed.characters();
				this.droppedIncoming.incrementAndGet();
			}
			this.incoming.offer(event);
			this.incomingCharacters += event.characters();
		}
	}

	private void drainIncoming() {
		var additions = new ArrayList<SqlEvent>(DRAIN_BATCH_SIZE);
		for (int index = 0; index < DRAIN_BATCH_SIZE; index++) {
			IncomingEvent incomingEvent;
			synchronized (this.incoming) {
				incomingEvent = this.incoming.poll();
				if (incomingEvent != null) {
					this.incomingCharacters -= incomingEvent.characters();
				}
			}
			if (incomingEvent == null) {
				break;
			}
			if (this.currentSession == null) {
				this.currentSession = incomingEvent.sessionId();
			}
			else if (!this.currentSession.equals(incomingEvent.sessionId())) {
				this.model.clear();
				this.table.clearSelection();
				this.detail.setText("");
				additions.clear();
				this.currentSession = incomingEvent.sessionId();
				this.droppedIncoming.set(0);
			}
			additions.add(incomingEvent.event());
		}
		this.model.addAll(additions);
		if (!additions.isEmpty()) {
			scrollToLatestEvent();
			updateMetrics();
		}
	}

	private void scrollToLatestEvent() {
		if (!this.autoScroll.isSelected() || this.model.getRowCount() == 0) {
			return;
		}
		int modelRow = this.model.getRowCount() - 1;
		int viewRow = this.table.convertRowIndexToView(modelRow);
		if (viewRow < 0) {
			return;
		}
		var bounds = this.table.getCellRect(viewRow, 0, true);
		this.table.scrollRectToVisible(bounds);
	}

	private void setStatus(String text, Color color) {
		SwingUtilities.invokeLater(() -> {
			this.status.setText(text);
			this.status.setForeground(color);
		});
	}

	private void clearEvents() {
		clearIncoming();
		this.table.clearSelection();
		this.model.clear();
		this.detail.setText("");
		this.droppedIncoming.set(0);
		updateMetrics();
	}

	private void clearIncoming() {
		synchronized (this.incoming) {
			this.incoming.clear();
			this.incomingCharacters = 0;
		}
	}

	private void selected(ListSelectionEvent event) {
		if (event.getValueIsAdjusting()) {
			return;
		}
		var row = this.table.getSelectedRow();
		if (row < 0) {
			this.detail.setText("");
			return;
		}
		var selectedEvent = this.model.get(this.table.convertRowIndexToModel(row));
		this.detail.setText("Raw SQL\n" + value(selectedEvent.rawSql()) + "\n\nRendered SQL\n"
				+ value(selectedEvent.sql()) + "\n\nParameters\n"
				+ (selectedEvent.parameters().isEmpty() ? "\u2014" : selectedEvent.parameters())
				+ "\nParameter methods\n"
				+ (selectedEvent.parameterMethods().isEmpty() ? "\u2014" : selectedEvent.parameterMethods())
				+ "\n\nTiming\nExecution: " + String.format("%.3f ms", selectedEvent.durationMillis()) + "\nFetch: "
				+ String.format("%.3f ms", selectedEvent.fetchMillis()) + "\nResult-set use: "
				+ String.format("%.3f ms", selectedEvent.resultSetUseMillis()) + "\nRows: "
				+ (selectedEvent.rows() < 0 ? "\u2014" : selectedEvent.rows()) + "\nQuery timeout: "
				+ selectedEvent.queryTimeout() + " s\nAuto commit: " + selectedEvent.autoCommit()
				+ "\nTransaction isolation: " + selectedEvent.transactionIsolation() + "\nTransaction ID: "
				+ (selectedEvent.transactionId() == 0 ? "\u2014" : selectedEvent.transactionId()) + "\n\nConnection\n"
				+ value(selectedEvent.connectionUrl()) + "\n" + value(selectedEvent.connectionProperties())
				+ "\n\nAttribution\nFingerprint: " + value(selectedEvent.fingerprint()) + "\nCall site: "
				+ value(selectedEvent.callSite())
				+ (selectedEvent.stackTrace().isBlank() ? "" : "\n\nCaptured stack\n" + selectedEvent.stackTrace())
				+ (selectedEvent.error().isBlank() ? "" : "\n\nError\n" + selectedEvent.error()));
		this.detail.setCaretPosition(0);
	}

	private void applyFilter() {
		var text = this.filter.getText().toLowerCase(Locale.ROOT);
		var minimum = ((Number) this.minimumDuration.getValue()).doubleValue();
		this.sorter.setRowFilter(new RowFilter<>() {
			@Override
			public boolean include(Entry<? extends EventTableModel, ? extends Integer> entry) {
				var event = model.get(entry.getIdentifier());
				if (sqlStatementsOnly.isSelected() && !event.kind().isSqlStatement()) {
					return false;
				}
				double observedDuration = Math.max(event.durationMillis(),
						Math.max(event.fetchMillis(), event.resultSetUseMillis()));
				if (observedDuration < minimum) {
					return false;
				}
				return text.isBlank() || searchable(event).contains(text);
			}
		});
	}

	private void updateMetrics() {
		this.metrics
			.setText(String.format("%,d events   \u2022   %,.1f ms execute   \u2022   %,d rows   \u2022   %,d failed",
					this.model.getRowCount(), this.model.totalDurationMillis(), this.model.observedRowCount(),
					this.model.failedCount()));
		long dropped = this.droppedIncoming.get();
		this.metrics.setToolTipText(dropped == 0 ? null : dropped + " incoming events dropped while the UI was busy");
	}

	private void export(JButton button) {
		var chooser = new JFileChooser();
		chooser.setSelectedFile(new java.io.File("jdbc-observer.csv"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path target = chooser.getSelectedFile().toPath();
		if (Files.exists(target)
				&& JOptionPane.showConfirmDialog(this, "Overwrite " + target.getFileName() + "?", "Confirm overwrite",
						JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
			return;
		}
		var snapshot = this.model.all();
		button.setEnabled(false);
		new SwingWorker<Void, Void>() {
			@Override
			protected Void doInBackground() throws Exception {
				writeCsv(target, snapshot);
				return null;
			}

			@Override
			protected void done() {
				button.setEnabled(true);
				try {
					get();
					setStatus("\u25cf Exported " + snapshot.size() + " events to " + target.getFileName(),
							new Color(74, 222, 128));
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				catch (ExecutionException ex) {
					JOptionPane.showMessageDialog(ObserverApp.this, ex.getCause().getMessage(), "Export failed",
							JOptionPane.ERROR_MESSAGE);
				}
			}
		}.execute();
	}

	static void writeCsv(Path target, List<SqlEvent> events) throws IOException {
		try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
			writer.write("id,parent_id,transaction_id,timestamp,type,execute_ms,fetch_ms,result_use_ms,rows,timeout,"
					+ "autocommit,isolation,connection,connection_url,connection_properties,thread,success,fingerprint,"
					+ "call_site,raw_sql,sql,parameters,parameter_methods,error,stack_trace");
			writer.newLine();
			for (var event : events) {
				writer.write(event.id() + "," + event.parentId() + "," + event.transactionId() + ","
						+ csv(event.timestamp()) + "," + event.kind() + "," + event.durationMillis() + ","
						+ event.fetchMillis() + "," + event.resultSetUseMillis() + "," + event.rows() + ","
						+ event.queryTimeout() + "," + event.autoCommit() + "," + event.transactionIsolation() + ","
						+ csv(event.connection()) + "," + csv(event.connectionUrl()) + ","
						+ csv(event.connectionProperties()) + "," + csv(event.thread()) + "," + event.success() + ","
						+ csv(event.fingerprint()) + "," + csv(event.callSite()) + "," + csv(event.rawSql()) + ","
						+ csv(event.sql()) + "," + csv(event.parameters()) + "," + csv(event.parameterMethods()) + ","
						+ csv(event.error()) + "," + csv(event.stackTrace()));
				writer.newLine();
			}
		}
	}

	private static String csv(Object value) {
		String text = String.valueOf(value);
		if (!text.isEmpty() && (text.charAt(0) == '=' || text.charAt(0) == '+' || text.charAt(0) == '-'
				|| text.charAt(0) == '@' || text.charAt(0) == '\t' || text.charAt(0) == '\r')) {
			text = "'" + text;
		}
		return "\"" + text.replace("\"", "\"\"") + "\"";
	}

	private void showAnalysis() {
		var groups = this.model.all().stream().filter(event -> switch (event.kind()) {
			case QUERY, UPDATE, EXECUTE, BATCH -> true;
			default -> false;
		})
			.collect(Collectors.groupingBy(event -> event.fingerprint().isBlank()
					? event.rawSql().isBlank() ? event.sql() : event.rawSql() : event.fingerprint()));
		var data = new Object[groups.size()][6];
		int row = 0;
		for (var entry : groups.entrySet()) {
			var events = entry.getValue();
			var summary = events.stream().mapToDouble(SqlEvent::durationMillis).summaryStatistics();
			data[row++] = new Object[] { entry.getKey(), summary.getCount(), summary.getSum(), summary.getAverage(),
					summary.getMax(), events.stream().filter(event -> !event.success()).count() };
		}
		var model = new TypedTableModel(data,
				new Object[] { "SQL", "Executions", "Total ms", "Average ms", "Maximum ms", "Failures" },
				new Class<?>[] { String.class, Long.class, Double.class, Double.class, Double.class, Long.class });
		var analysis = new JTable(model);
		analysis.setAutoCreateRowSorter(true);
		analysis.setRowHeight(26);
		var pane = new JScrollPane(analysis);
		pane.setPreferredSize(new Dimension(1000, 560));
		JOptionPane.showMessageDialog(this, pane, "Grouped SQL analysis", JOptionPane.PLAIN_MESSAGE);
	}

	private void showTransactions() {
		if (this.transactionDialog == null || !this.transactionDialog.isDisplayable()) {
			this.transactionDialog = new TransactionTimelineDialog(this, this.model::all);
		}
		this.transactionDialog.setVisible(true);
		this.transactionDialog.toFront();
	}

	private void showNPlusOneSettings() {
		var threshold = new JSpinner(new SpinnerNumberModel(this.model.nPlusOneThreshold(), 2, 10_000, 1));
		var window = new JSpinner(new SpinnerNumberModel(this.model.nPlusOneWindowMillis(), 1L, 3_600_000L, 100L));
		threshold.setToolTipText("Equivalent executions required before a pattern is marked");
		window.setToolTipText("Time window containing the repeated executions");
		var form = new JPanel(new GridBagLayout());
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(6, 6, 6, 6);
		constraints.anchor = GridBagConstraints.WEST;
		constraints.gridx = 0;
		constraints.gridy = 0;
		form.add(new JLabel("Repetition threshold"), constraints);
		constraints.gridx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1;
		form.add(threshold, constraints);
		constraints.gridx = 0;
		constraints.gridy = 1;
		constraints.weightx = 0;
		constraints.fill = GridBagConstraints.NONE;
		form.add(new JLabel("Detection window (ms)"), constraints);
		constraints.gridx = 1;
		constraints.weightx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		form.add(window, constraints);
		if (JOptionPane.showConfirmDialog(this, form, "N+1 detection settings", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
			var newThreshold = ((Number) threshold.getValue()).intValue();
			var newWindow = ((Number) window.getValue()).longValue();
			this.model.configureNPlusOne(newThreshold, newWindow);
			setStatus("\u25cf N+1 detection: " + newThreshold + " executions within " + newWindow + " ms",
					new Color(96, 165, 250));
			this.table.repaint();
		}
	}

	private void zoom(int steps) {
		if (steps == 0) {
			this.uiFontSize = this.baseFontSize;
		}
		else {
			this.uiFontSize = Math.max(10f, Math.min(30f, this.uiFontSize + steps * 2f));
		}
		applyZoom();
	}

	private void applyZoom() {
		var font = UIManager.getFont("defaultFont");
		if (font != null) {
			UIManager.put("defaultFont", font.deriveFont(this.uiFontSize));
		}
		for (var window : Window.getWindows()) {
			if (window.isDisplayable()) {
				SwingUtilities.updateComponentTreeUI(window);
			}
		}
		this.title.setFont(UIManager.getFont("defaultFont").deriveFont(Font.BOLD, this.uiFontSize + 11f));
		this.detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(this.uiFontSize)));
		this.table.setRowHeight(Math.round(this.uiFontSize * 28f / this.baseFontSize));
	}

	private void toggleTheme(JButton button) {
		this.darkMode = !this.darkMode;
		try {
			UIManager.setLookAndFeel(this.darkMode ? new FlatDarkLaf() : new FlatLightLaf());
			UIManager.put("Component.arc", 12);
			UIManager.put("Button.arc", 12);
			UIManager.put("TextComponent.arc", 12);
			applyZoom();
			button.setText(this.darkMode ? "Light mode" : "Dark mode");
			this.table.repaint();
		}
		catch (UnsupportedLookAndFeelException ex) {
			JOptionPane.showMessageDialog(this, ex.getMessage(), "Unable to change theme", JOptionPane.ERROR_MESSAGE);
		}
	}

	private static String value(String value) {
		return value == null || value.isBlank() ? "\u2014" : value;
	}

	private static String searchable(SqlEvent event) {
		return (event.rawSql() + " " + event.sql() + " " + event.fingerprint() + " " + event.callSite() + " "
				+ event.thread() + " " + event.connection() + " " + event.kind() + " " + event.error())
			.toLowerCase(Locale.ROOT);
	}

	private static int estimatedCharacters(SqlEvent event) {
		long result = event.thread().length() + event.connection().length() + event.rawSql().length()
				+ event.sql().length() + event.error().length() + event.connectionUrl().length()
				+ event.connectionProperties().length() + event.fingerprint().length() + event.callSite().length()
				+ event.stackTrace().length();
		for (var value : event.parameters().values()) {
			result += value.length() + 8L;
		}
		for (var value : event.parameterMethods().values()) {
			result += value.length() + 8L;
		}
		return (int) Math.min(Integer.MAX_VALUE, result);
	}

	private class EventRenderer extends DefaultTableCellRenderer {

		@Override
		public Component getTableCellRendererComponent(JTable source, Object value, boolean selected, boolean focus,
				int row, int column) {
			var component = super.getTableCellRendererComponent(source, value, selected, focus, row, column);
			if (!selected) {
				var modelRow = source.convertRowIndexToModel(row);
				var event = model.get(modelRow);
				var nPlusOne = model.getValueAt(modelRow, 2).toString().startsWith("N+1");
				var matches = !filter.getText().isBlank()
						&& searchable(event).contains(filter.getText().toLowerCase(Locale.ROOT));
				var failureColor = darkMode ? new Color(95, 35, 45) : new Color(255, 220, 224);
				var nPlusOneColor = darkMode ? new Color(105, 55, 20) : new Color(255, 231, 190);
				var highlightColor = darkMode ? new Color(30, 65, 90) : new Color(218, 238, 255);
				component.setBackground(!event.success() ? failureColor : nPlusOne ? nPlusOneColor
						: highlightMatches.isSelected() && matches ? highlightColor : source.getBackground());
			}
			return component;
		}

	}

	private static final class DurationRenderer extends DefaultTableCellRenderer {

		private DurationRenderer() {
			setHorizontalAlignment(RIGHT);
		}

		@Override
		protected void setValue(Object value) {
			setText(value instanceof Double duration ? String.format("%.3f ms", duration) : "");
		}

	}

	private static final class TimeRenderer extends DefaultTableCellRenderer {

		@Override
		protected void setValue(Object value) {
			setText(value instanceof Instant instant ? TIME.format(instant) : "");
		}

	}

	private static final class TypedTableModel extends DefaultTableModel {

		private final Class<?>[] types;

		private TypedTableModel(Object[][] data, Object[] columns, Class<?>[] types) {
			super(data, columns);
			this.types = types.clone();
		}

		@Override
		public Class<?> getColumnClass(int column) {
			return this.types[column];
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}

	}

	public static void main(String[] args) {
		var darkMode = !Boolean.getBoolean("jdbcObserver.lightMode");
		if (darkMode) {
			FlatDarkLaf.setup();
		}
		else {
			FlatLightLaf.setup();
		}
		UIManager.put("Component.arc", 12);
		UIManager.put("Button.arc", 12);
		UIManager.put("TextComponent.arc", 12);
		var host = "127.0.0.1";
		var port = 4561;
		var listen = false;
		try {
			for (var argument : args) {
				if (argument.equals("--listen")) {
					listen = true;
				}
				else if (argument.startsWith("--host=")) {
					host = argument.substring(7);
				}
				else if (argument.startsWith("--port=")) {
					port = parsePort(argument.substring(7));
				}
				else if (argument.matches("\\d+")) {
					port = parsePort(argument);
				}
				else {
					throw new IllegalArgumentException("Unknown argument: " + argument);
				}
			}
			if (host.isBlank()) {
				throw new IllegalArgumentException("Host must not be blank");
			}
		}
		catch (IllegalArgumentException ex) {
			System.err.println("JDBC Observer: " + ex.getMessage());
			return;
		}
		var targetHost = host;
		var targetPort = port;
		var listenerMode = listen;
		var initialDarkMode = darkMode;
		SwingUtilities
			.invokeLater(() -> new ObserverApp(targetHost, targetPort, listenerMode, initialDarkMode).setVisible(true));
	}

	private static int parsePort(String value) {
		int parsed = Integer.parseInt(value);
		if (parsed < 1 || parsed > 65_535) {
			throw new IllegalArgumentException("Port must be between 1 and 65535");
		}
		return parsed;
	}

	private record IncomingEvent(UUID sessionId, SqlEvent event, int characters) {
	}

	@FunctionalInterface
	private interface SimpleDocumentListener extends javax.swing.event.DocumentListener {

		void update(javax.swing.event.DocumentEvent event);

		default void insertUpdate(javax.swing.event.DocumentEvent event) {
			update(event);
		}

		default void removeUpdate(javax.swing.event.DocumentEvent event) {
			update(event);
		}

		default void changedUpdate(javax.swing.event.DocumentEvent event) {
			update(event);
		}

	}

}
