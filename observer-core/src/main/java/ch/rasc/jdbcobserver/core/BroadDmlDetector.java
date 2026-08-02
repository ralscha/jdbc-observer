package ch.rasc.jdbcobserver.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class BroadDmlDetector {

	public enum Finding {

		NONE, UPDATE_WITHOUT_WHERE, DELETE_WITHOUT_WHERE

	}

	private static final Set<String> STATEMENT_OPERATIONS = Set.of("delete", "insert", "merge", "select", "update",
			"values");

	private BroadDmlDetector() {
	}

	public static Finding detect(String sql) {
		if (sql == null || sql.isBlank() || sql.stripTrailing().endsWith("\u2026")) {
			return Finding.NONE;
		}
		var tokens = tokenize(SqlFingerprint.normalize(sql));
		int segmentStart = 0;
		for (int index = 0; index <= tokens.size(); index++) {
			if (index == tokens.size() || isTopLevelSemicolon(tokens.get(index))) {
				var finding = detectSegment(tokens, segmentStart, index);
				if (finding != Finding.NONE) {
					return finding;
				}
				segmentStart = index + 1;
			}
		}
		return Finding.NONE;
	}

	private static Finding detectSegment(List<Token> tokens, int start, int end) {
		int firstWord = firstWord(tokens, start, end, 0);
		if (firstWord < 0) {
			return Finding.NONE;
		}
		var first = tokens.get(firstWord);
		if (isDml(first)) {
			return withoutWhere(tokens, firstWord, end);
		}
		if (!first.isWord("with")) {
			return Finding.NONE;
		}

		int mainOperation = firstStatementOperation(tokens, firstWord + 1, end);
		int cteEnd = mainOperation < 0 ? end : mainOperation;
		for (int index = firstWord + 1; index < cteEnd; index++) {
			var token = tokens.get(index);
			if (token.depth() > 0 && isDml(token) && isFirstWordInScope(tokens, start, index)) {
				var finding = withoutWhere(tokens, index, cteEnd);
				if (finding != Finding.NONE) {
					return finding;
				}
			}
		}
		return mainOperation >= 0 && isDml(tokens.get(mainOperation)) ? withoutWhere(tokens, mainOperation, end)
				: Finding.NONE;
	}

	private static int firstStatementOperation(List<Token> tokens, int start, int end) {
		for (int index = start; index < end; index++) {
			var token = tokens.get(index);
			if (token.depth() == 0 && token.type() == TokenType.WORD && STATEMENT_OPERATIONS.contains(token.text())) {
				return index;
			}
		}
		return -1;
	}

	private static Finding withoutWhere(List<Token> tokens, int operationIndex, int end) {
		var operation = tokens.get(operationIndex);
		for (int index = operationIndex + 1; index < end; index++) {
			var token = tokens.get(index);
			if (token.type() == TokenType.CLOSE && token.depth() < operation.depth()) {
				break;
			}
			if (token.depth() == operation.depth() && token.isWord("where")) {
				return Finding.NONE;
			}
		}
		return operation.isWord("update") ? Finding.UPDATE_WITHOUT_WHERE : Finding.DELETE_WITHOUT_WHERE;
	}

	private static int firstWord(List<Token> tokens, int start, int end, int depth) {
		for (int index = start; index < end; index++) {
			var token = tokens.get(index);
			if (token.depth() == depth && token.type() == TokenType.WORD) {
				return index;
			}
		}
		return -1;
	}

	private static boolean isFirstWordInScope(List<Token> tokens, int start, int candidate) {
		int depth = tokens.get(candidate).depth();
		for (int index = candidate - 1; index >= start; index--) {
			var token = tokens.get(index);
			if (token.depth() < depth) {
				return true;
			}
			if (token.depth() == depth && token.type() == TokenType.WORD) {
				return false;
			}
		}
		return true;
	}

	private static boolean isDml(Token token) {
		return token.isWord("update") || token.isWord("delete");
	}

	private static boolean isTopLevelSemicolon(Token token) {
		return token.depth() == 0 && token.type() == TokenType.SEMICOLON;
	}

	private static List<Token> tokenize(String sql) {
		var tokens = new ArrayList<Token>();
		int depth = 0;
		for (int index = 0; index < sql.length();) {
			char current = sql.charAt(index);
			if (Character.isWhitespace(current)) {
				index++;
			}
			else if (Character.isLetter(current) || current == '_' || current == '$') {
				int end = index + 1;
				while (end < sql.length() && isWordPart(sql.charAt(end))) {
					end++;
				}
				tokens.add(new Token(TokenType.WORD, sql.substring(index, end), depth));
				index = end;
			}
			else if (current == '"' || current == '`') {
				index = quotedIdentifierEnd(sql, index + 1, current);
			}
			else if (current == '[') {
				index = bracketIdentifierEnd(sql, index + 1);
			}
			else {
				switch (current) {
					case '(' -> {
						tokens.add(new Token(TokenType.OPEN, "(", depth));
						depth++;
					}
					case ')' -> {
						depth = Math.max(0, depth - 1);
						tokens.add(new Token(TokenType.CLOSE, ")", depth));
					}
					case ';' -> tokens.add(new Token(TokenType.SEMICOLON, ";", depth));
					default -> {
					}
				}
				index++;
			}
		}
		return tokens;
	}

	private static int quotedIdentifierEnd(String sql, int index, char quote) {
		while (index < sql.length()) {
			if (sql.charAt(index++) == quote) {
				if (index < sql.length() && sql.charAt(index) == quote) {
					index++;
				}
				else {
					break;
				}
			}
		}
		return index;
	}

	private static int bracketIdentifierEnd(String sql, int index) {
		while (index < sql.length()) {
			if (sql.charAt(index++) == ']') {
				if (index < sql.length() && sql.charAt(index) == ']') {
					index++;
				}
				else {
					break;
				}
			}
		}
		return index;
	}

	private static boolean isWordPart(char value) {
		return Character.isLetterOrDigit(value) || value == '_' || value == '$';
	}

	private enum TokenType {

		WORD, OPEN, CLOSE, SEMICOLON

	}

	private record Token(TokenType type, String text, int depth) {

		private boolean isWord(String expected) {
			return this.type == TokenType.WORD && this.text.equals(expected);
		}

	}

}
