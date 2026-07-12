package ch.rasc.jdbcobserver.ui;

import ch.rasc.jdbcobserver.core.SqlEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

final class TransactionTimelineDialog extends JDialog {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
		.withZone(ZoneId.systemDefault());

	private final Supplier<List<SqlEvent>> events;

	private final JTable transactions = new JTable();

	private final JTable timeline = new JTable();

	private final JSpinner longThreshold = new JSpinner(
			new SpinnerNumberModel(Long.valueOf(threshold("jdbcObserver.longTransactionMillis", 5_000L)),
					Long.valueOf(1), Long.valueOf(86_400_000), Long.valueOf(500)));

	private final JSpinner idleThreshold = new JSpinner(
			new SpinnerNumberModel(Long.valueOf(threshold("jdbcObserver.idleTransactionMillis", 2_000L)),
					Long.valueOf(1), Long.valueOf(86_400_000), Long.valueOf(500)));

	private final ReadOnlyTableModel transactionModel = new ReadOnlyTableModel(
			new Object[] { "Transaction", "Connection", "Started", "Duration ms", "Idle ms", "Events", "Status",
					"Flag" },
			new Class<?>[] { Long.class, String.class, Instant.class, Long.class, Long.class, Integer.class,
					String.class, String.class });

	private final ReadOnlyTableModel timelineModel = new ReadOnlyTableModel(
			new Object[] { "Offset ms", "Time", "Type", "Duration ms", "Thread", "Detail" }, new Class<?>[] {
					Double.class, Instant.class, SqlEvent.Kind.class, Double.class, String.class, String.class });

	private List<TransactionSummary> summaries = List.of();

	TransactionTimelineDialog(Window owner, Supplier<List<SqlEvent>> events) {
		super(owner, "Transaction timelines", ModalityType.MODELESS);
		this.events = events;
		build();
		refresh();
		var timer = new Timer(1_000, ignored -> refresh());
		timer.start();
		addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosed(java.awt.event.WindowEvent event) {
				timer.stop();
			}
		});
	}

	private void build() {
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setSize(1_250, 720);
		setLocationRelativeTo(getOwner());
		var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		controls.add(new JLabel("Long transaction (ms)"));
		controls.add(this.longThreshold);
		controls.add(new JLabel("Idle transaction (ms)"));
		controls.add(this.idleThreshold);
		var refresh = new JButton("Refresh");
		refresh.addActionListener(event -> refresh());
		controls.add(refresh);
		add(controls, BorderLayout.NORTH);

		this.transactions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		this.transactions.setModel(this.transactionModel);
		this.transactions.setAutoCreateRowSorter(true);
		this.transactions.setRowHeight(27);
		installTransactionRenderers();
		this.transactions.getSelectionModel().addListSelectionListener(event -> showSelectedTimeline());
		this.timeline.setAutoCreateRowSorter(true);
		this.timeline.setModel(this.timelineModel);
		this.timeline.setDefaultRenderer(Instant.class, new TimeRenderer());
		this.timeline.setRowHeight(25);

		var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(this.transactions),
				new JScrollPane(this.timeline));
		split.setResizeWeight(0.45);
		split.setDividerLocation(300);
		split.setPreferredSize(new Dimension(1_200, 650));
		add(split);
	}

	private void refresh() {
		long selectedId = selectedTransactionId();
		this.summaries = summarize(this.events.get());
		this.transactionModel.setRowCount(0);
		for (var summary : this.summaries) {
			this.transactionModel
				.addRow(new Object[] { summary.id(), summary.connection(), summary.started(), summary.durationMillis(),
						summary.idleMillis(), summary.events().size(), summary.status(), flag(summary) });
		}
		installTransactionRenderers();
		reselect(selectedId);
		showSelectedTimeline();
	}

	private void showSelectedTimeline() {
		int viewRow = this.transactions.getSelectedRow();
		if (viewRow < 0 || this.summaries.isEmpty()) {
			this.timelineModel.setRowCount(0);
			return;
		}
		long id = ((Number) this.transactionModel.getValueAt(this.transactions.convertRowIndexToModel(viewRow), 0))
			.longValue();
		var summary = this.summaries.stream().filter(item -> item.id() == id).findFirst().orElse(null);
		if (summary == null)
			return;
		this.timelineModel.setRowCount(0);
		for (var event : summary.events()) {
			this.timelineModel
				.addRow(new Object[] { Duration.between(summary.started(), event.timestamp()).toNanos() / 1_000_000.0,
						event.timestamp(), event.kind(), event.durationMillis(), event.thread(), detail(event) });
		}
	}

	private List<TransactionSummary> summarize(List<SqlEvent> events) {
		var grouped = new LinkedHashMap<Long, List<SqlEvent>>();
		events.stream()
			.filter(event -> event.transactionId() != 0)
			.sorted(Comparator.comparing(SqlEvent::timestamp))
			.forEach(event -> grouped.computeIfAbsent(event.transactionId(), ignored -> new ArrayList<>()).add(event));
		var now = Instant.now();
		return grouped.entrySet()
			.stream()
			.filter(entry -> entry.getValue()
				.stream()
				.anyMatch(event -> event.kind() == SqlEvent.Kind.TRANSACTION_BEGIN))
			.map(entry -> TransactionSummary.create(entry.getKey(), entry.getValue(), now))
			.sorted(Comparator.comparing(TransactionSummary::started).reversed())
			.toList();
	}

	private String flag(TransactionSummary summary) {
		long longLimit = ((Number) this.longThreshold.getValue()).longValue();
		long idleLimit = ((Number) this.idleThreshold.getValue()).longValue();
		if (summary.active() && summary.idleMillis() >= idleLimit)
			return "IDLE";
		if (summary.durationMillis() >= longLimit)
			return "LONG";
		return "";
	}

	private void installTransactionRenderers() {
		var renderer = new TransactionRenderer();
		this.transactions.setDefaultRenderer(Object.class, renderer);
		this.transactions.setDefaultRenderer(String.class, renderer);
		this.transactions.setDefaultRenderer(Long.class, renderer);
		this.transactions.setDefaultRenderer(Integer.class, renderer);
		this.transactions.setDefaultRenderer(Instant.class, renderer);
	}

	private long selectedTransactionId() {
		int row = this.transactions.getSelectedRow();
		return row < 0 ? -1
				: ((Number) this.transactionModel.getValueAt(this.transactions.convertRowIndexToModel(row), 0))
					.longValue();
	}

	private void reselect(long id) {
		for (int row = 0; row < this.transactions.getRowCount(); row++) {
			if (((Number) this.transactionModel.getValueAt(this.transactions.convertRowIndexToModel(row), 0))
				.longValue() == id) {
				this.transactions.setRowSelectionInterval(row, row);
				return;
			}
		}
		if (this.transactions.getRowCount() > 0)
			this.transactions.setRowSelectionInterval(0, 0);
	}

	private static String detail(SqlEvent event) {
		if (!event.success() && !event.error().isBlank())
			return event.error();
		if (!event.sql().isBlank())
			return event.sql();
		if (!event.rawSql().isBlank())
			return event.rawSql();
		if (!event.error().isBlank())
			return event.error();
		return event.kind().toString();
	}

	private static long threshold(String property, long defaultValue) {
		return Math.clamp(Long.getLong(property, defaultValue), 1, 86_400_000);
	}

	private final class TransactionRenderer extends DefaultTableCellRenderer {

		@Override
		protected void setValue(Object value) {
			setText(value instanceof Instant instant ? TIME.format(instant) : String.valueOf(value));
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
				int row, int column) {
			var component = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
			if (!selected) {
				var flag = table.getModel().getValueAt(table.convertRowIndexToModel(row), 7).toString();
				component.setForeground(flag.isBlank() ? table.getForeground() : Color.DARK_GRAY);
				component.setBackground(switch (flag) {
					case "IDLE" -> new Color(255, 226, 170);
					case "LONG" -> new Color(255, 215, 220);
					default -> table.getBackground();
				});
			}
			return component;
		}

	}

	private static final class TimeRenderer extends DefaultTableCellRenderer {

		@Override
		protected void setValue(Object value) {
			setText(value instanceof Instant instant ? TIME.format(instant) : "");
		}

	}

	private record TransactionSummary(long id, String connection, Instant started, Instant lastActivity,
			long durationMillis, long idleMillis, boolean active, String status, List<SqlEvent> events) {

		static TransactionSummary create(long id, List<SqlEvent> events, Instant now) {
			var first = events.stream()
				.filter(event -> event.kind() == SqlEvent.Kind.TRANSACTION_BEGIN)
				.findFirst()
				.orElseThrow();
			var last = events.getLast();
			boolean active = !terminal(last);
			var ended = active ? now : last.timestamp();
			return new TransactionSummary(id, first.connection(), first.timestamp(), last.timestamp(),
					Duration.between(first.timestamp(), ended).toMillis(),
					active ? Duration.between(last.timestamp(), now).toMillis() : 0, active,
					active ? "ACTIVE" : last.kind().toString(), List.copyOf(events));
		}

		private static boolean terminal(SqlEvent event) {
			return event.success() && (event.kind() == SqlEvent.Kind.COMMIT || event.kind() == SqlEvent.Kind.ROLLBACK
					|| event.kind() == SqlEvent.Kind.CONNECTION_CLOSE
					|| (event.kind() == SqlEvent.Kind.AUTOCOMMIT_CHANGE && event.sql().endsWith("true")));
		}
	}

	private static final class ReadOnlyTableModel extends DefaultTableModel {

		private final Class<?>[] types;

		private ReadOnlyTableModel(Object[] columns, Class<?>[] types) {
			super(columns, 0);
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

}
