package grabflow.ride.dsl;

/**
 * A lexical token produced by the {@link Lexer}.
 *
 * @param type    token type
 * @param value   raw text value
 * @param position character position in the source string
 */
public record Token(TokenType type, String value, int position) {

    public enum TokenType {
        // Keywords
        MATCH, WHERE, AND, OR, ORDER, BY, ASC, DESC, LIMIT,

        // Identifiers and literals
        IDENTIFIER,     // field names: distance, rating, vehicle.type
        NUMBER,         // numeric literals: 5, 4.5, 1000
        STRING,         // quoted strings: "sedan", 'suv'

        // Operators
        LT, GT, LTE, GTE, EQ, NEQ,  // <, >, <=, >=, =, !=

        // Units (attached to numbers)
        KM, M,          // distance units

        // Punctuation
        DOT,            // .
        COMMA,          // ,
        LPAREN, RPAREN, // ( )

        // Special
        EOF
    }

    @Override
    public String toString() {
        return type + "(" + value + ")@" + position;
    }
}
