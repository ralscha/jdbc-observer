package ch.rasc.jdbcobserver.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CartesianProductDetector {

	public enum Finding {

		NONE, EXPLICIT_CROSS_JOIN, UNCONSTRAINED_COMMA_JOIN

	}

	private static final Set<String> FROM_TERMINATORS = Set.of("except", "fetch", "for", "group", "having", "intersect",
			"limit", "offset", "order", "qualify", "returning", "union", "window");

	private CartesianProductDetector() {
	}

	public static Finding detect(String sql) {
		if (sql == null || sql.isBlank() || sql.stripTrailing().endsWith("\u2026")) {
			return Finding.NONE;
		}
		var tokens = tokenize(SqlFingerprint.normalize(sql));
		if (containsCrossJoin(tokens)) {
			return Finding.EXPLICIT_CROSS_JOIN;
		}
		if (containsUnconstrainedCommaJoin(tokens)) {
			return Finding.UNCONSTRAINED_COMMA_JOIN;
		}
		return Finding.NONE;
	}

	private static boolean containsCrossJoin(List<Token> tokens) {
		for (int index = 0; index + 1 < tokens.size(); index++) {
			var current = tokens.get(index);
			var next = tokens.get(index + 1);
			if (current.isWord("cross") && next.isWord("join")
					&& (index == 0 || tokens.get(index - 1).type() != TokenType.DOT)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsUnconstrainedCommaJoin(List<Token> tokens) {
		for (int index = 0; index < tokens.size(); index++) {
			if (tokens.get(index).isWord("from") && unconstrainedCommaJoinAfter(tokens, index)) {
				return true;
			}
		}
		return false;
	}

	private static boolean unconstrainedCommaJoinAfter(List<Token> tokens, int fromIndex) {
		int depth = tokens.get(fromIndex).depth();
		int end = tokens.size();
		var commas = new ArrayList<Integer>();
		for (int index = fromIndex + 1; index < tokens.size(); index++) {
			var token = tokens.get(index);
			if (token.type() == TokenType.CLOSE && token.depth() < depth) {
				end = index;
				break;
			}
			if (token.depth() != depth) {
				continue;
			}
			if (token.type() == TokenType.SEMICOLON) {
				end = index;
				break;
			}
			if (token.isWord("where")) {
				return false;
			}
			if (token.type() == TokenType.WORD && FROM_TERMINATORS.contains(token.text())) {
				end = index;
				break;
			}
			if (token.type() == TokenType.COMMA) {
				commas.add(index);
			}
		}
		if (commas.isEmpty()) {
			return false;
		}

		int start = fromIndex + 1;
		for (int comma : commas) {
			if (!isSimpleTableReference(tokens, start, comma, depth)) {
				return false;
			}
			start = comma + 1;
		}
		return isSimpleTableReference(tokens, start, end, depth);
	}

	private static boolean isSimpleTableReference(List<Token> tokens, int start, int end, int depth) {
		while (end > start && tokens.get(end - 1).type() == TokenType.SEMICOLON) {
			end--;
		}
		if (start >= end || !isIdentifier(tokens.get(start), depth)) {
			return false;
		}
		int index = start + 1;
		while (index + 1 < end && tokens.get(index).type() == TokenType.DOT
				&& isIdentifier(tokens.get(index + 1), depth)) {
			index += 2;
		}
		if (index == end) {
			return true;
		}
		if (tokens.get(index).isWord("as")) {
			index++;
		}
		return index + 1 == end && isIdentifier(tokens.get(index), depth);
	}

	private static boolean isIdentifier(Token token, int depth) {
		return token.depth() == depth && (token.type() == TokenType.WORD || token.type() == TokenType.IDENTIFIER);
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
				int end = SqlText.quotedEnd(sql, index);
				tokens.add(new Token(TokenType.IDENTIFIER, sql.substring(index, end), depth));
				index = end;
			}
			else if (current == '[') {
				int end = SqlText.quotedEnd(sql, index);
				tokens.add(new Token(TokenType.IDENTIFIER, sql.substring(index, end), depth));
				index = end;
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
					case ',' -> tokens.add(new Token(TokenType.COMMA, ",", depth));
					case '.' -> tokens.add(new Token(TokenType.DOT, ".", depth));
					case ';' -> tokens.add(new Token(TokenType.SEMICOLON, ";", depth));
					default -> tokens.add(new Token(TokenType.OTHER, Character.toString(current), depth));
				}
				index++;
			}
		}
		return tokens;
	}

	private static boolean isWordPart(char value) {
		return Character.isLetterOrDigit(value) || value == '_' || value == '$';
	}

	private enum TokenType {

		WORD, IDENTIFIER, COMMA, DOT, OPEN, CLOSE, SEMICOLON, OTHER

	}

	private record Token(TokenType type, String text, int depth) {

		private boolean isWord(String expected) {
			return this.type == TokenType.WORD && this.text.equals(expected);
		}

	}

}
