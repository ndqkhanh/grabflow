package grabflow.ride.dsl;

import grabflow.ride.dsl.Token.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class LexerTest {

    @Test
    void tokenizesSimpleQuery() {
        var tokens = new Lexer("MATCH driver").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.MATCH);
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.IDENTIFIER);
        assertThat(tokens.get(1).value()).isEqualTo("driver");
        assertThat(tokens.get(2).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenizesFullQuery() {
        var tokens = new Lexer("MATCH driver WHERE distance < 5 AND rating > 4.5 ORDER BY distance ASC LIMIT 3").tokenize();
        assertThat(tokens.stream().map(Token::type).toList()).containsExactly(
                TokenType.MATCH, TokenType.IDENTIFIER, TokenType.WHERE,
                TokenType.IDENTIFIER, TokenType.LT, TokenType.NUMBER,
                TokenType.AND, TokenType.IDENTIFIER, TokenType.GT, TokenType.NUMBER,
                TokenType.ORDER, TokenType.BY, TokenType.IDENTIFIER, TokenType.ASC,
                TokenType.LIMIT, TokenType.NUMBER, TokenType.EOF
        );
    }

    @Test
    void tokenizesComparisonOperators() {
        var tokens = new Lexer("< > <= >= = !=").tokenize();
        assertThat(tokens.stream().map(Token::type).toList()).containsExactly(
                TokenType.LT, TokenType.GT, TokenType.LTE, TokenType.GTE,
                TokenType.EQ, TokenType.NEQ, TokenType.EOF
        );
    }

    @Test
    void tokenizesStringLiterals() {
        var tokens = new Lexer("\"sedan\"").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(0).value()).isEqualTo("sedan");
    }

    @Test
    void tokenizesSingleQuotedStrings() {
        var tokens = new Lexer("'suv'").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(0).value()).isEqualTo("suv");
    }

    @Test
    void tokenizesDecimalNumbers() {
        var tokens = new Lexer("4.5").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.NUMBER);
        assertThat(tokens.get(0).value()).isEqualTo("4.5");
    }

    @Test
    void tokenizesDistanceWithUnit() {
        var tokens = new Lexer("5km").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.NUMBER);
        assertThat(tokens.get(0).value()).isEqualTo("5");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.KM);
    }

    @Test
    void tokenizesDottedIdentifier() {
        var tokens = new Lexer("vehicle.type").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.IDENTIFIER);
        assertThat(tokens.get(0).value()).isEqualTo("vehicle");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.DOT);
        assertThat(tokens.get(2).type()).isEqualTo(TokenType.IDENTIFIER);
        assertThat(tokens.get(2).value()).isEqualTo("type");
    }

    @Test
    void keywordsAreCaseInsensitive() {
        var tokens = new Lexer("match WHERE And").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.MATCH);
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.WHERE);
        assertThat(tokens.get(2).type()).isEqualTo(TokenType.AND);
    }

    @Test
    void unexpectedCharacterThrows() {
        assertThatThrownBy(() -> new Lexer("MATCH @driver").tokenize())
                .isInstanceOf(Lexer.DslParseException.class);
    }

    @Test
    void unterminatedStringThrows() {
        assertThatThrownBy(() -> new Lexer("\"unterminated").tokenize())
                .isInstanceOf(Lexer.DslParseException.class);
    }
}
