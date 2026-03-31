package grabflow.ride.dsl;

import grabflow.ride.dsl.Token.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lexer (tokenizer) for the ride matching DSL.
 *
 * <h3>CS Fundamental: Lexical Analysis (DFA-based Scanner)</h3>
 * <p>The lexer is the first phase of a compiler pipeline. It converts a stream of
 * characters into a stream of tokens — the smallest meaningful units of the language.
 * This implementation uses a hand-written DFA (Deterministic Finite Automaton) approach
 * rather than a regex-based or table-driven scanner.</p>
 *
 * <h3>Token Categories</h3>
 * <ul>
 *   <li><b>Keywords</b>: MATCH, WHERE, AND, OR, ORDER, BY, ASC, DESC, LIMIT</li>
 *   <li><b>Identifiers</b>: field names like {@code distance}, {@code rating}, {@code vehicle.type}</li>
 *   <li><b>Literals</b>: numbers ({@code 5}, {@code 4.5}), strings ({@code "sedan"})</li>
 *   <li><b>Operators</b>: {@code <}, {@code >}, {@code <=}, {@code >=}, {@code =}, {@code !=}</li>
 *   <li><b>Units</b>: {@code km}, {@code m} (attached to numeric literals)</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>
 *   Input:  "MATCH driver WHERE distance < 5km AND rating > 4.5"
 *   Output: [MATCH, IDENTIFIER(driver), WHERE, IDENTIFIER(distance), LT, NUMBER(5), KM,
 *            AND, IDENTIFIER(rating), GT, NUMBER(4.5)]
 * </pre>
 */
public class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("match", TokenType.MATCH),
            Map.entry("where", TokenType.WHERE),
            Map.entry("and", TokenType.AND),
            Map.entry("or", TokenType.OR),
            Map.entry("order", TokenType.ORDER),
            Map.entry("by", TokenType.BY),
            Map.entry("asc", TokenType.ASC),
            Map.entry("desc", TokenType.DESC),
            Map.entry("limit", TokenType.LIMIT)
    );

    private final String source;
    private int pos;

    public Lexer(String source) {
        this.source = source;
        this.pos = 0;
    }

    /**
     * Tokenizes the entire source string.
     *
     * @return list of tokens ending with EOF
     */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < source.length()) {
            skipWhitespace();
            if (pos >= source.length()) break;

            char c = source.charAt(pos);
            Token token = switch (c) {
                case '<' -> lexOperator();
                case '>' -> lexOperator();
                case '=' -> emit(TokenType.EQ, "=");
                case '!' -> lexBang();
                case '.' -> emit(TokenType.DOT, ".");
                case ',' -> emit(TokenType.COMMA, ",");
                case '(' -> emit(TokenType.LPAREN, "(");
                case ')' -> emit(TokenType.RPAREN, ")");
                case '"', '\'' -> lexString();
                default -> {
                    if (Character.isDigit(c)) yield lexNumber();
                    if (Character.isLetter(c) || c == '_') yield lexIdentifierOrKeyword();
                    throw new DslParseException("Unexpected character: '" + c + "'", pos);
                }
            };
            tokens.add(token);
        }
        tokens.add(new Token(TokenType.EOF, "", pos));
        return tokens;
    }

    private Token lexOperator() {
        int start = pos;
        char c = source.charAt(pos++);
        if (pos < source.length() && source.charAt(pos) == '=') {
            pos++;
            return c == '<'
                    ? new Token(TokenType.LTE, "<=", start)
                    : new Token(TokenType.GTE, ">=", start);
        }
        return c == '<'
                ? new Token(TokenType.LT, "<", start)
                : new Token(TokenType.GT, ">", start);
    }

    private Token lexBang() {
        int start = pos++;
        if (pos < source.length() && source.charAt(pos) == '=') {
            pos++;
            return new Token(TokenType.NEQ, "!=", start);
        }
        throw new DslParseException("Expected '=' after '!'", start);
    }

    private Token lexNumber() {
        int start = pos;
        while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
            pos++;
        }
        String numStr = source.substring(start, pos);

        // Check for unit suffix (km, m)
        if (pos < source.length()) {
            if (pos + 1 < source.length()
                    && Character.toLowerCase(source.charAt(pos)) == 'k'
                    && Character.toLowerCase(source.charAt(pos + 1)) == 'm') {
                // Return number token, then unit will be separate
                Token numToken = new Token(TokenType.NUMBER, numStr, start);
                // We don't advance past the unit here -- next call will pick it up
                return numToken;
            }
        }

        return new Token(TokenType.NUMBER, numStr, start);
    }

    private Token lexIdentifierOrKeyword() {
        int start = pos;
        while (pos < source.length() && (Character.isLetterOrDigit(source.charAt(pos))
                || source.charAt(pos) == '_')) {
            pos++;
        }
        String word = source.substring(start, pos);
        String lower = word.toLowerCase();

        // Check for unit keywords
        if (lower.equals("km")) return new Token(TokenType.KM, word, start);
        if (lower.equals("m") && (start == 0 || !Character.isLetter(source.charAt(start - 1)))) {
            // Only treat standalone "m" as meter unit, not the start of a word
            // But if previous token was a number, this is a unit
            return new Token(TokenType.M, word, start);
        }

        // Check for keywords
        TokenType keyword = KEYWORDS.get(lower);
        if (keyword != null) return new Token(keyword, word, start);

        return new Token(TokenType.IDENTIFIER, word, start);
    }

    private Token lexString() {
        int start = pos;
        char quote = source.charAt(pos++);
        StringBuilder sb = new StringBuilder();
        while (pos < source.length() && source.charAt(pos) != quote) {
            sb.append(source.charAt(pos++));
        }
        if (pos >= source.length()) {
            throw new DslParseException("Unterminated string literal", start);
        }
        pos++; // consume closing quote
        return new Token(TokenType.STRING, sb.toString(), start);
    }

    private Token emit(TokenType type, String value) {
        Token t = new Token(type, value, pos);
        pos += value.length();
        return t;
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
            pos++;
        }
    }

    /**
     * Exception thrown when the lexer encounters invalid input.
     */
    public static class DslParseException extends RuntimeException {
        private final int position;

        public DslParseException(String message, int position) {
            super(message + " at position " + position);
            this.position = position;
        }

        public int getPosition() {
            return position;
        }
    }
}
