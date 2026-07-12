package ch.rasc.jdbcobserver.core;

public final class SqlFingerprint {

	private SqlFingerprint() {
	}

	public static String normalize(String sql) {
		if (sql == null || sql.isBlank()) {
			return "";
		}
		var result = new StringBuilder(sql.length());
		for (int index = 0; index < sql.length();) {
			char current = sql.charAt(index);
			if (Character.isWhitespace(current)) {
				appendSpace(result);
				index++;
				continue;
			}
			if (current == '-' && has(sql, index + 1, '-')) {
				index = skipLineComment(sql, index + 2);
				appendSpace(result);
				continue;
			}
			if (current == '/' && has(sql, index + 1, '*')) {
				index = skipBlockComment(sql, index + 2);
				appendSpace(result);
				continue;
			}
			if (current == '\'') {
				appendPlaceholder(result);
				index = skipQuoted(sql, index + 1, '\'', true);
				continue;
			}
			if (current == '$') {
				int end = dollarQuoteEnd(sql, index);
				if (end >= 0) {
					appendPlaceholder(result);
					index = end;
					continue;
				}
			}
			if (current == '"' || current == '`') {
				int end = skipQuoted(sql, index + 1, current, true);
				result.append(sql, index, end);
				index = end;
				continue;
			}
			if (current == '[') {
				int end = skipBracketIdentifier(sql, index + 1);
				result.append(sql, index, end);
				index = end;
				continue;
			}
			if (startsNumber(sql, index)) {
				appendPlaceholder(result);
				index = skipNumber(sql, index);
				continue;
			}
			result.append(Character.toLowerCase(current));
			index++;
		}
		int length = result.length();
		while (length > 0 && result.charAt(length - 1) == ' ') {
			result.setLength(--length);
		}
		return result.toString();
	}

	private static int skipLineComment(String sql, int index) {
		while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
			index++;
		}
		return index;
	}

	private static int skipBlockComment(String sql, int index) {
		int depth = 1;
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
		return index;
	}

	private static int skipQuoted(String sql, int index, char quote, boolean doubledEscape) {
		while (index < sql.length()) {
			char current = sql.charAt(index++);
			if (current == '\\' && quote == '\'' && index < sql.length()) {
				index++;
			}
			else if (current == quote) {
				if (doubledEscape && has(sql, index, quote)) {
					index++;
				}
				else {
					break;
				}
			}
		}
		return index;
	}

	private static int skipBracketIdentifier(String sql, int index) {
		while (index < sql.length()) {
			if (sql.charAt(index++) == ']') {
				if (has(sql, index, ']')) {
					index++;
				}
				else {
					break;
				}
			}
		}
		return index;
	}

	private static int dollarQuoteEnd(String sql, int start) {
		int delimiterEnd = start + 1;
		while (delimiterEnd < sql.length() && isDollarTag(sql.charAt(delimiterEnd))) {
			delimiterEnd++;
		}
		if (!has(sql, delimiterEnd, '$')) {
			return -1;
		}
		String delimiter = sql.substring(start, delimiterEnd + 1);
		int contentEnd = sql.indexOf(delimiter, delimiterEnd + 1);
		return contentEnd < 0 ? sql.length() : contentEnd + delimiter.length();
	}

	private static boolean isDollarTag(char value) {
		return Character.isLetterOrDigit(value) || value == '_';
	}

	private static boolean startsNumber(String sql, int index) {
		char current = sql.charAt(index);
		boolean numericStart = Character.isDigit(current)
				|| (current == '.' && index + 1 < sql.length() && Character.isDigit(sql.charAt(index + 1)));
		return numericStart && (index == 0 || !isIdentifier(sql.charAt(index - 1)));
	}

	private static int skipNumber(String sql, int index) {
		if (has(sql, index, '0') && index + 2 <= sql.length() && index + 1 < sql.length()
				&& (sql.charAt(index + 1) == 'x' || sql.charAt(index + 1) == 'X' || sql.charAt(index + 1) == 'b'
						|| sql.charAt(index + 1) == 'B')) {
			index += 2;
			while (index < sql.length() && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) {
				index++;
			}
			return index;
		}
		boolean decimalPoint = false;
		boolean exponent = false;
		while (index < sql.length()) {
			char current = sql.charAt(index);
			if (Character.isDigit(current) || current == '_') {
				index++;
			}
			else if (current == '.' && !decimalPoint && !exponent) {
				decimalPoint = true;
				index++;
			}
			else if ((current == 'e' || current == 'E') && !exponent) {
				exponent = true;
				index++;
				if (index < sql.length() && (sql.charAt(index) == '+' || sql.charAt(index) == '-')) {
					index++;
				}
			}
			else {
				break;
			}
		}
		return index;
	}

	private static void appendPlaceholder(StringBuilder result) {
		result.append('?');
	}

	private static void appendSpace(StringBuilder result) {
		if (!result.isEmpty() && result.charAt(result.length() - 1) != ' ') {
			result.append(' ');
		}
	}

	private static boolean has(String value, int index, char expected) {
		return index >= 0 && index < value.length() && value.charAt(index) == expected;
	}

	private static boolean isIdentifier(char value) {
		return Character.isLetterOrDigit(value) || value == '_' || value == '$';
	}

}
