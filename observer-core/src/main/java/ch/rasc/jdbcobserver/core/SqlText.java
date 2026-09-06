package ch.rasc.jdbcobserver.core;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared lexical handling for SQL telemetry; this is not a SQL validator. */
public final class SqlText {

	private static final Set<String> OPERATIONS = Set.of("select", "insert", "update", "delete", "merge", "values",
			"table");

	private SqlText() {
	}

	public static String renderParameters(String sql, Map<Integer, String> parameters, int maxLength) {
		if (maxLength < 1) {
			throw new IllegalArgumentException("SQL text limit must be positive");
		}
		if (sql == null || sql.isBlank()) {
			return "";
		}
		var result = new StringBuilder(Math.min(maxLength, sql.length()));
		int parameter = 1;
		int index = 0;
		int arrayDepth = 0;
		while (index < sql.length() && result.length() < maxLength) {
			int end = commentEnd(sql, index);
			if (end == index) {
				boolean arrayBracket = sql.charAt(index) == '['
						&& (arrayDepth > 0 || followsArrayExpression(sql, index));
				if (arrayBracket) {
					arrayDepth++;
				}
				end = quotedEnd(sql, index, !arrayBracket);
			}
			if (end > index) {
				int copied = Math.min(end - index, maxLength - result.length());
				result.append(sql, index, index + copied);
				index += copied;
			}
			else if (sql.charAt(index) == '?') {
				// pgJDBC escapes question-mark operators as ?? in prepared SQL.
				boolean escaped = has(sql, index + 1, '?');
				String value = escaped ? "?" : parameters.getOrDefault(parameter++, "?");
				int copied = Math.min(value.length(), maxLength - result.length());
				result.append(value, 0, copied);
				index += escaped ? 2 : 1;
				if (copied < value.length()) {
					return result.append('\u2026').toString();
				}
			}
			else {
				if (sql.charAt(index) == ']' && arrayDepth > 0) {
					arrayDepth--;
				}
				result.append(sql.charAt(index++));
			}
		}
		return index < sql.length() ? result.append('\u2026').toString() : result.toString();
	}

	/** Returns the main operation, including after a WITH clause, when recognizable. */
	public static String operation(String sql) {
		if (sql == null) {
			return "";
		}
		int depth = 0;
		boolean with = false;
		for (int index = 0; index < sql.length();) {
			int end = commentEnd(sql, index);
			if (end == index) {
				end = quotedEnd(sql, index);
			}
			if (end > index) {
				index = end;
				continue;
			}
			char current = sql.charAt(index);
			if (Character.isLetter(current) || current == '_') {
				end = index + 1;
				while (end < sql.length() && isIdentifier(sql.charAt(end))) {
					end++;
				}
				if (depth == 0) {
					String word = sql.substring(index, end).toLowerCase(Locale.ROOT);
					if (!with) {
						if (!word.equals("with")) {
							return word;
						}
						with = true;
					}
					else if (OPERATIONS.contains(word)) {
						return word;
					}
				}
				index = end;
			}
			else {
				if (current == '(') {
					depth++;
				}
				else if (current == ')') {
					depth--;
				}
				else if (current == ';' && depth == 0) {
					return "";
				}
				index++;
			}
		}
		return "";
	}

	static int commentEnd(String sql, int index) {
		if (has(sql, index, '-') && has(sql, index + 1, '-')) {
			index += 2;
			while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
				index++;
			}
		}
		else if (has(sql, index, '/') && has(sql, index + 1, '*')) {
			int depth = 1;
			index += 2;
			while (index < sql.length() && depth > 0) {
				if (has(sql, index, '/') && has(sql, index + 1, '*')) {
					depth++;
					index += 2;
				}
				else if (has(sql, index, '*') && has(sql, index + 1, '/')) {
					depth--;
					index += 2;
				}
				else {
					index++;
				}
			}
		}
		return index;
	}

	static int quotedEnd(String sql, int index) {
		return quotedEnd(sql, index, true);
	}

	private static int quotedEnd(String sql, int index, boolean bracketIdentifier) {
		char quote = sql.charAt(index);
		if (quote == '$') {
			return dollarQuoteEnd(sql, index);
		}
		if (quote != '\'' && quote != '"' && quote != '`' && (quote != '[' || !bracketIdentifier)) {
			return index;
		}
		char closing = quote == '[' ? ']' : quote;
		index++;
		while (index < sql.length()) {
			char current = sql.charAt(index++);
			if (current == '\\' && quote == '\'' && index < sql.length()) {
				index++;
			}
			else if (current == closing) {
				if (has(sql, index, closing)) {
					index++;
				}
				else {
					break;
				}
			}
		}
		return index;
	}

	private static boolean followsArrayExpression(String sql, int index) {
		if (index > 0 && (isIdentifier(sql.charAt(index - 1)) || sql.charAt(index - 1) == ']'
				|| sql.charAt(index - 1) == ')')) {
			return true;
		}
		int end = index;
		while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
			end--;
		}
		return end >= 5 && sql.regionMatches(true, end - 5, "array", 0, 5)
				&& (end == 5 || !isIdentifier(sql.charAt(end - 6)));
	}

	private static int dollarQuoteEnd(String sql, int start) {
		if (start > 0 && isIdentifier(sql.charAt(start - 1))) {
			return start;
		}
		int end = start + 1;
		if (!has(sql, end, '$')) {
			if (end >= sql.length() || !(Character.isLetter(sql.charAt(end)) || sql.charAt(end) == '_')) {
				return start;
			}
			while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
				end++;
			}
			if (!has(sql, end, '$')) {
				return start;
			}
		}
		String delimiter = sql.substring(start, end + 1);
		int close = sql.indexOf(delimiter, end + 1);
		return close < 0 ? sql.length() : close + delimiter.length();
	}

	static boolean has(String value, int index, char expected) {
		return index >= 0 && index < value.length() && value.charAt(index) == expected;
	}

	static boolean isIdentifier(char value) {
		return Character.isLetterOrDigit(value) || value == '_' || value == '$';
	}

}
