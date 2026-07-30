package ch.rasc.jdbcobserver.ui;

import ch.rasc.jdbcobserver.core.ControlCodec;
import ch.rasc.jdbcobserver.core.EventCodec;
import ch.rasc.jdbcobserver.core.SqlEvent;
import ch.rasc.jdbcobserver.core.TransportCodec;
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
import java.awt.Insets;
import java.awt.Window;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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

	private final JLabel throttleIndicator = new JLabel();

	private final JLabel explainIndicator = new JLabel();

	private final AtomicBoolean paused = new AtomicBoolean();

	private final ArrayBlockingQueue<IncomingEvent> incoming = new ArrayBlockingQueue<>(INCOMING_CAPACITY);

	private final AtomicLong droppedIncoming = new AtomicLong();

	private final AtomicLong explainRequestIds = new AtomicLong();

	private final ConcurrentMap<Long, ExplainContext> pendingExplains = new ConcurrentHashMap<>();

	private final List<JComponent> exportTriggers = new ArrayList<>();

	private final Object controlLock = new Object();

	private DataOutputStream controlOutput;

	private UUID connectedSession;

	private int throttleMillis;

	private int incomingCharacters;

	private final JSpinner minimumDuration = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 3_600_000.0, 1.0));

	private final JCheckBoxMenuItem sqlStatementsOnly = new JCheckBoxMenuItem("SQL statements only", true);

	private final JCheckBoxMenuItem highlightMatches = new JCheckBoxMenuItem("Highlight matches", true);

	private final JCheckBoxMenuItem autoScroll = new JCheckBoxMenuItem("Auto-scroll", true);

	private JPanel commandBar;

	private JPanel inlineFilters;

	private JButton filtersButton;

	private JToggleButton pauseButton;

	private JButton clearButton;

	private JCheckBoxMenuItem darkModeMenuItem;

	private JMenuItem clearThrottleMenuItem;

	private JMenuItem explainMenuItem;

	private JPopupMenu filtersPopup;

	private final int port;

	private final String host;

	private final boolean listen;

	private boolean darkMode;

	private final float baseFontSize;

	private float uiFontSize;

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
		setMinimumSize(new Dimension(720, 520));
		setSize(1400, 860);
		setLocationByPlatform(true);
		var root = new JPanel(new BorderLayout(0, 12));
		root.setBorder(new EmptyBorder(12, 18, 14, 18));
		setContentPane(root);
		this.filter.putClientProperty("JTextField.placeholderText", "Filter SQL, thread, connection, or type...");
		this.filter.getDocument().addDocumentListener((SimpleDocumentListener) event -> applyFilter());
		this.pauseButton = new JToggleButton("Pause");
		this.pauseButton.addActionListener(event -> {
			this.paused.set(this.pauseButton.isSelected());
			if (this.pauseButton.isSelected()) {
				clearIncoming();
			}
			this.pauseButton.setText(this.pauseButton.isSelected() ? "Resume" : "Pause");
		});
		this.clearButton = new JButton("Clear");
		this.clearButton.addActionListener(event -> clearEvents());
		this.minimumDuration.setToolTipText("Minimum observed duration in milliseconds");
		this.minimumDuration.addChangeListener(event -> {
			applyFilter();
			updateFiltersButton();
		});
		this.sqlStatementsOnly.addActionListener(event -> applyFilter());
		this.highlightMatches.addActionListener(event -> this.table.repaint());
		this.autoScroll.setToolTipText("Keep the latest event visible as new events arrive");
		this.autoScroll.addActionListener(event -> {
			if (this.autoScroll.isSelected()) {
				scrollToLatestEvent();
			}
		});
		setJMenuBar(createMenuBar());
		this.commandBar = createCommandBar();
		this.table.setRowSorter(this.sorter);
		this.table.setRowHeight(28);
		this.table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		this.table.setShowVerticalLines(false);
		configureTableColumns();
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
		center.add(this.commandBar, BorderLayout.NORTH);
		center.add(split);
		root.add(center);
		this.status.setBorder(new EmptyBorder(3, 0, 0, 0));
		var footer = new JPanel(new BorderLayout(12, 0));
		footer.add(this.status);
		var footerSummary = new JPanel(new BorderLayout(12, 0));
		var indicators = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
		this.throttleIndicator.setVisible(false);
		indicators.add(this.throttleIndicator);
		this.explainIndicator.setVisible(false);
		indicators.add(this.explainIndicator);
		footerSummary.add(indicators);
		footerSummary.add(this.metrics, BorderLayout.EAST);
		footer.add(footerSummary, BorderLayout.EAST);
		root.add(footer, BorderLayout.SOUTH);
		var inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		var actionMap = getRootPane().getActionMap();
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK), "zoom-in");
		actionMap.put("zoom-in", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				zoom(1);
			}
		});
		new Timer(50, event -> drainIncoming()).start();
		SwingUtilities.invokeLater(this::updateCommandBarLayout);
	}

	private JMenuBar createMenuBar() {
		var menuBar = new JMenuBar();

		var file = new JMenu("File");
		file.setMnemonic(KeyEvent.VK_F);
		file.add(createExportMenuItem());
		file.addSeparator();
		var clear = new JMenuItem("Clear events");
		clear.addActionListener(event -> clearEvents());
		file.add(clear);
		file.addSeparator();
		var exit = new JMenuItem("Exit");
		exit.addActionListener(event -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
		file.add(exit);
		menuBar.add(file);

		var analyze = new JMenu("Analyze");
		analyze.setMnemonic(KeyEvent.VK_A);
		this.explainMenuItem = new JMenuItem("Explain selected SQL...");
		this.explainMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK));
		this.explainMenuItem.addActionListener(event -> explainSelectedSql());
		this.explainMenuItem.setEnabled(false);
		analyze.add(this.explainMenuItem);
		analyze.addSeparator();
		var transactions = new JMenuItem("Transactions");
		transactions.addActionListener(event -> showTransactions());
		analyze.add(transactions);
		var groupedAnalysis = new JMenuItem("Group & analyze");
		groupedAnalysis.addActionListener(event -> showAnalysis());
		analyze.add(groupedAnalysis);
		menuBar.add(analyze);

		var view = new JMenu("View");
		view.setMnemonic(KeyEvent.VK_V);
		this.darkModeMenuItem = new JCheckBoxMenuItem("Dark mode", this.darkMode);
		this.darkModeMenuItem.addActionListener(event -> setDarkMode(this.darkModeMenuItem.isSelected()));
		view.add(this.darkModeMenuItem);
		view.addSeparator();
		var zoomIn = new JMenuItem("Zoom in");
		zoomIn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK));
		zoomIn.addActionListener(event -> zoom(1));
		view.add(zoomIn);
		var zoomOut = new JMenuItem("Zoom out");
		zoomOut.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK));
		zoomOut.addActionListener(event -> zoom(-1));
		view.add(zoomOut);
		var resetZoom = new JMenuItem("Actual size");
		resetZoom.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK));
		resetZoom.addActionListener(event -> zoom(0));
		view.add(resetZoom);
		view.addSeparator();
		view.add(this.sqlStatementsOnly);
		view.add(this.highlightMatches);
		view.add(this.autoScroll);
		menuBar.add(view);

		var settings = new JMenu("Settings");
		settings.setMnemonic(KeyEvent.VK_S);
		var nPlusOne = new JMenuItem("N+1 detection...");
		nPlusOne.addActionListener(event -> showNPlusOneSettings());
		settings.add(nPlusOne);
		settings.addSeparator();
		var throttler = new JMenuItem("Throttler...");
		throttler.addActionListener(event -> showThrottleSettings());
		settings.add(throttler);
		this.clearThrottleMenuItem = new JMenuItem("Clear throttler");
		this.clearThrottleMenuItem.addActionListener(event -> configureThrottle(0));
		this.clearThrottleMenuItem.setEnabled(false);
		settings.add(this.clearThrottleMenuItem);
		menuBar.add(settings);
		return menuBar;
	}

	private JPanel createCommandBar() {
		var bar = new JPanel(new BorderLayout(8, 0));
		bar.add(this.filter);

		this.inlineFilters = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		this.inlineFilters.add(new JLabel("Min ms"));
		this.inlineFilters.add(this.minimumDuration);

		this.filtersButton = new JButton("Filters");
		this.filtersButton.setVisible(false);
		this.filtersPopup = createFiltersPopup();
		this.filtersButton
			.addActionListener(event -> this.filtersPopup.show(this.filtersButton, 0, this.filtersButton.getHeight()));

		var controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		controls.add(this.inlineFilters);
		controls.add(this.filtersButton);
		controls.add(this.pauseButton);
		controls.add(this.clearButton);
		bar.add(controls, BorderLayout.EAST);
		bar.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent event) {
				updateCommandBarLayout();
			}
		});
		updateFiltersButton();
		return bar;
	}

	private JPopupMenu createFiltersPopup() {
		var popup = new JPopupMenu();
		var minimum = new JPanel(new BorderLayout(12, 0));
		minimum.setBorder(new EmptyBorder(7, 10, 7, 10));
		minimum.add(new JLabel("Minimum duration (ms)"));
		var compactMinimumDuration = new JSpinner(this.minimumDuration.getModel());
		compactMinimumDuration.setToolTipText(this.minimumDuration.getToolTipText());
		compactMinimumDuration.setPreferredSize(new Dimension(110, compactMinimumDuration.getPreferredSize().height));
		minimum.add(compactMinimumDuration, BorderLayout.EAST);
		popup.add(minimum);
		return popup;
	}

	private JMenuItem createExportMenuItem() {
		var item = new JMenuItem("Export CSV...");
		item.addActionListener(event -> export());
		this.exportTriggers.add(item);
		return item;
	}

	private void updateFiltersButton() {
		if (this.filtersButton == null) {
			return;
		}
		boolean durationFilterActive = ((Number) this.minimumDuration.getValue()).doubleValue() > 0;
		this.filtersButton.setText(durationFilterActive ? "Filters (1)" : "Filters");
	}

	private void updateCommandBarLayout() {
		if (this.commandBar == null || this.inlineFilters == null) {
			return;
		}
		int requiredWidth = 320 + this.inlineFilters.getPreferredSize().width
				+ this.pauseButton.getPreferredSize().width + this.clearButton.getPreferredSize().width + 48;
		boolean showInlineFilters = this.commandBar.getWidth() >= requiredWidth;
		if (this.inlineFilters.isVisible() != showInlineFilters
				|| this.filtersButton.isVisible() == showInlineFilters) {
			this.inlineFilters.setVisible(showInlineFilters);
			this.filtersButton.setVisible(!showInlineFilters);
			this.commandBar.revalidate();
			this.commandBar.repaint();
		}
	}

	private void configureTableColumns() {
		int[] widths = { 100, 110, 90, 90, 80, 90, 70, 75, 95, 130, 160, 160, 260, 420, 80 };
		for (int column = 0; column < widths.length; column++) {
			this.table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
		}
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
					readEvents(socket, "Connected to " + this.host + ":" + this.port);
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
				try (var socket = server.accept()) {
					readEvents(socket, "Agent connected from " + socket.getRemoteSocketAddress());
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

	private void readEvents(Socket socket, String connectedText) throws IOException {
		try (var input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
				var output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
			UUID sessionId = EventCodec.readHeader(input);
			activateControlChannel(output, sessionId);
			try {
				setStatus("\u25cf " + connectedText, new Color(74, 222, 128));
				while (true) {
					switch (TransportCodec.read(input)) {
						case TransportCodec.EventMessage message -> {
							var event = message.event();
							if (!this.paused.get()) {
								enqueue(new IncomingEvent(sessionId, event, estimatedCharacters(event)));
							}
						}
						case TransportCodec.ExplainResponse response -> receiveExplainResponse(response);
					}
				}
			}
			finally {
				deactivateControlChannel(output);
			}
		}
	}

	private void activateControlChannel(DataOutputStream output, UUID sessionId) throws IOException {
		synchronized (this.controlLock) {
			ControlCodec.writeHeader(output);
			ControlCodec.writeThrottleMillis(output, this.throttleMillis);
			output.flush();
			this.controlOutput = output;
			this.connectedSession = sessionId;
		}
		SwingUtilities.invokeLater(this::updateRemoteControlState);
	}

	private void deactivateControlChannel(DataOutputStream output) {
		boolean deactivated = false;
		synchronized (this.controlLock) {
			if (this.controlOutput == output) {
				this.controlOutput = null;
				this.connectedSession = null;
				deactivated = true;
			}
		}
		if (deactivated) {
			boolean explainWasPending = !this.pendingExplains.isEmpty();
			this.pendingExplains.clear();
			SwingUtilities.invokeLater(() -> {
				updateRemoteControlState();
				if (explainWasPending && isDisplayable()) {
					JOptionPane.showMessageDialog(this, "The agent disconnected before it returned the EXPLAIN plan.",
							"EXPLAIN failed", JOptionPane.ERROR_MESSAGE);
				}
			});
		}
	}

	private boolean sendThrottleConfiguration() {
		synchronized (this.controlLock) {
			if (this.controlOutput == null) {
				return false;
			}
			try {
				ControlCodec.writeThrottleMillis(this.controlOutput, this.throttleMillis);
				this.controlOutput.flush();
				return true;
			}
			catch (IOException ex) {
				try {
					this.controlOutput.close();
				}
				catch (IOException ignored) {
				}
				this.controlOutput = null;
				this.connectedSession = null;
				this.pendingExplains.clear();
				SwingUtilities.invokeLater(this::updateRemoteControlState);
				return false;
			}
		}
	}

	private boolean sendExplainRequest(long requestId, String connectionId, String sql) {
		synchronized (this.controlLock) {
			if (this.controlOutput == null) {
				return false;
			}
			try {
				ControlCodec.writeExplainRequest(this.controlOutput, requestId, connectionId, sql);
				this.controlOutput.flush();
				return true;
			}
			catch (IOException ex) {
				try {
					this.controlOutput.close();
				}
				catch (IOException ignored) {
				}
				this.controlOutput = null;
				this.connectedSession = null;
				this.pendingExplains.clear();
				SwingUtilities.invokeLater(this::updateRemoteControlState);
				return false;
			}
		}
	}

	private void configureThrottle(int milliseconds) {
		if (milliseconds < 0 || milliseconds > ControlCodec.MAX_THROTTLE_MILLIS) {
			throw new IllegalArgumentException("Invalid throttle delay: " + milliseconds);
		}
		this.throttleMillis = milliseconds;
		sendThrottleConfiguration();
		updateThrottleIndicator();
	}

	private void updateThrottleIndicator() {
		boolean connected;
		synchronized (this.controlLock) {
			connected = this.controlOutput != null;
		}
		boolean active = this.throttleMillis > 0;
		this.throttleIndicator.setVisible(active);
		this.throttleIndicator.setText(active ? "Throttle +" + this.throttleMillis + " ms / SQL call" : "");
		this.throttleIndicator.setForeground(this.darkMode ? new Color(251, 191, 36) : new Color(180, 83, 9));
		this.throttleIndicator.setToolTipText(
				!active ? null : connected ? "Artificial SQL execution delay is active in the connected agent"
						: "Artificial SQL execution delay will be applied when an agent connects");
		if (this.clearThrottleMenuItem != null) {
			this.clearThrottleMenuItem.setEnabled(active);
		}
		if (this.throttleIndicator.getParent() != null) {
			this.throttleIndicator.getParent().revalidate();
			this.throttleIndicator.getParent().repaint();
		}
	}

	private void updateRemoteControlState() {
		updateThrottleIndicator();
		updateExplainState();
	}

	private void updateExplainState() {
		boolean connected;
		synchronized (this.controlLock) {
			connected = this.controlOutput != null && Objects.equals(this.connectedSession, this.currentSession);
		}
		var selectedEvent = selectedEvent();
		boolean running = !this.pendingExplains.isEmpty();
		this.explainIndicator.setVisible(running);
		this.explainIndicator.setText(running ? "EXPLAIN running…" : "");
		this.explainIndicator.setForeground(this.darkMode ? new Color(147, 197, 253) : new Color(29, 78, 216));
		if (this.explainMenuItem != null) {
			this.explainMenuItem.setEnabled(connected && !running && isExplainable(selectedEvent));
			this.explainMenuItem.setToolTipText(!connected ? "Connect to an agent to request an execution plan"
					: selectedEvent == null ? "Select a SQL event first"
							: !isExplainable(selectedEvent) ? "Select a query, update, or execute event"
									: running ? "Waiting for the current EXPLAIN request" : null);
		}
		if (this.explainIndicator.getParent() != null) {
			this.explainIndicator.getParent().revalidate();
			this.explainIndicator.getParent().repaint();
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
			updateExplainState();
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
			updateExplainState();
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
		updateExplainState();
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

	private void export() {
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
		setExportEnabled(false);
		new SwingWorker<Void, Void>() {
			@Override
			protected Void doInBackground() throws Exception {
				writeCsv(target, snapshot);
				return null;
			}

			@Override
			protected void done() {
				setExportEnabled(true);
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

	private void setExportEnabled(boolean enabled) {
		this.exportTriggers.forEach(trigger -> trigger.setEnabled(enabled));
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

	private void explainSelectedSql() {
		var event = selectedEvent();
		boolean connected;
		synchronized (this.controlLock) {
			connected = this.controlOutput != null && Objects.equals(this.connectedSession, this.currentSession);
		}
		if (!connected || !isExplainable(event)) {
			updateExplainState();
			return;
		}
		String sql = event.sql().isBlank() ? event.rawSql() : event.sql();
		long requestId = this.explainRequestIds.updateAndGet(current -> current == Long.MAX_VALUE ? 1 : current + 1);
		var context = new ExplainContext(event.connection());
		this.pendingExplains.put(requestId, context);
		if (!sendExplainRequest(requestId, event.connection(), sql)) {
			this.pendingExplains.remove(requestId);
			updateRemoteControlState();
			JOptionPane.showMessageDialog(this, "The EXPLAIN request could not be sent because the agent disconnected.",
					"EXPLAIN failed", JOptionPane.ERROR_MESSAGE);
			return;
		}
		updateExplainState();
	}

	private void receiveExplainResponse(TransportCodec.ExplainResponse response) {
		var context = this.pendingExplains.remove(response.requestId());
		if (context == null) {
			return;
		}
		SwingUtilities.invokeLater(() -> {
			updateExplainState();
			if (response.success()) {
				showExplainPlan(context, response.plan());
			}
			else {
				JOptionPane.showMessageDialog(this, response.error(), "EXPLAIN failed", JOptionPane.ERROR_MESSAGE);
			}
		});
	}

	private void showExplainPlan(ExplainContext context, String plan) {
		var planText = new JTextArea(plan);
		planText.setEditable(false);
		planText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(this.uiFontSize)));
		planText.setCaretPosition(0);
		var pane = new JScrollPane(planText);
		pane.setPreferredSize(new Dimension(900, 560));
		var content = new JPanel(new BorderLayout(0, 8));
		content.add(new JLabel("Plain EXPLAIN on " + context.connectionId() + " (the statement was not executed)"),
				BorderLayout.NORTH);
		content.add(pane);
		JOptionPane.showMessageDialog(this, content, "EXPLAIN plan", JOptionPane.PLAIN_MESSAGE);
	}

	private SqlEvent selectedEvent() {
		int row = this.table.getSelectedRow();
		return row < 0 ? null : this.model.get(this.table.convertRowIndexToModel(row));
	}

	private boolean isExplainable(SqlEvent event) {
		if (event == null || event.connection().isBlank()) {
			return false;
		}
		boolean supportedKind = switch (event.kind()) {
			case QUERY, UPDATE, EXECUTE -> true;
			default -> false;
		};
		return supportedKind && !(event.sql().isBlank() && event.rawSql().isBlank());
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

	private void showThrottleSettings() {
		var delay = new JSpinner(new SpinnerNumberModel(this.throttleMillis, 0, ControlCodec.MAX_THROTTLE_MILLIS, 1));
		delay.setToolTipText("Artificial delay added before every SQL execution");
		var form = new JPanel(new GridBagLayout());
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(6, 6, 6, 6);
		constraints.anchor = GridBagConstraints.WEST;
		constraints.gridx = 0;
		constraints.gridy = 0;
		form.add(new JLabel("Delay per SQL call (ms)"), constraints);
		constraints.gridx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1;
		form.add(delay, constraints);
		constraints.gridx = 0;
		constraints.gridy = 1;
		constraints.gridwidth = 2;
		constraints.weightx = 0;
		form.add(new JLabel("Applies to queries, updates, execute calls, and batches."), constraints);
		var options = new Object[] { "Apply", "Clear", "Cancel" };
		int choice = JOptionPane.showOptionDialog(this, form, "JDBC call throttler", JOptionPane.DEFAULT_OPTION,
				JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
		if (choice == 0) {
			configureThrottle(((Number) delay.getValue()).intValue());
		}
		else if (choice == 1) {
			configureThrottle(0);
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
		updatePopupUIs();
		this.detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(this.uiFontSize)));
		this.table.setRowHeight(Math.round(this.uiFontSize * 28f / this.baseFontSize));
		updateCommandBarLayout();
	}

	private void updatePopupUIs() {
		if (this.filtersPopup != null) {
			SwingUtilities.updateComponentTreeUI(this.filtersPopup);
		}
		var menuBar = getJMenuBar();
		if (menuBar != null) {
			for (int index = 0; index < menuBar.getMenuCount(); index++) {
				var menu = menuBar.getMenu(index);
				if (menu != null) {
					SwingUtilities.updateComponentTreeUI(menu.getPopupMenu());
				}
			}
		}
	}

	private void setDarkMode(boolean darkMode) {
		boolean previousDarkMode = this.darkMode;
		this.darkMode = darkMode;
		try {
			UIManager.setLookAndFeel(this.darkMode ? new FlatDarkLaf() : new FlatLightLaf());
			UIManager.put("Component.arc", 12);
			UIManager.put("Button.arc", 12);
			UIManager.put("TextComponent.arc", 12);
			applyZoom();
			this.darkModeMenuItem.setSelected(this.darkMode);
			updateRemoteControlState();
			this.table.repaint();
		}
		catch (UnsupportedLookAndFeelException ex) {
			this.darkMode = previousDarkMode;
			this.darkModeMenuItem.setSelected(this.darkMode);
			updateRemoteControlState();
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
		var darkMode = Boolean.getBoolean("jdbcObserver.darkMode");
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

	private record ExplainContext(String connectionId) {
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
