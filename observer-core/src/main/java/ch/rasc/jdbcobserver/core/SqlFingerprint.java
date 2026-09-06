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
			int end = SqlText.commentEnd(sql, index);
			if (end > index) {
				appendSpace(result);
				index = end;
				continue;
			}
			end = SqlText.quotedEnd(sql, index);
			if (end > index) {
				if (current == '\'' || current == '$') {
					result.append('?');
				}
				else {
					result.append(sql, index, end);
				}
				index = end;
				continue;
			}
			if (startsNumber(sql, index)) {
				result.append('?');
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

	private static boolean startsNumber(String sql, int index) {
		char current = sql.charAt(index);
		boolean numericStart = Character.isDigit(current)
				|| (current == '.' && index + 1 < sql.length() && Character.isDigit(sql.charAt(index + 1)));
		return numericStart && (index == 0 || !isIdentifier(sql.charAt(index - 1)));
	}

	private static int skipNumber(String sql, int index) {
		if (has(sql, index, '0') && index + 1 < sql.length() && (sql.charAt(index + 1) == 'x'
				|| sql.charAt(index + 1) == 'X' || sql.charAt(index + 1) == 'b' || sql.charAt(index + 1) == 'B')) {
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
