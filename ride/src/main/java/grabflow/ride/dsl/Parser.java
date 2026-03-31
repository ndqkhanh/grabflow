package grabflow.ride.dsl;

import grabflow.ride.dsl.AstNode.*;
import grabflow.ride.dsl.Token.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser for the matching DSL.
 *
 * <h3>CS Fundamental: Recursive Descent Parsing</h3>
 * <p>A recursive descent parser is a top-down parser where each grammar rule is
 * implemented as a method. The parser reads tokens left-to-right and builds the
 * AST by recursive method calls that mirror the grammar structure.</p>
 *
 * <h3>Grammar (BNF)</h3>
 * <pre>
 *   query      ::= MATCH IDENTIFIER (WHERE condition)? (ORDER BY orderList)? (LIMIT NUMBER)?
 *   condition  ::= comparison ((AND | OR) comparison)*
 *   comparison ::= fieldRef op value
 *   fieldRef   ::= IDENTIFIER (DOT IDENTIFIER)*
 *   op         ::= LT | GT | LTE | GTE | EQ | NEQ
 *   value      ::= NUMBER unit? | STRING | fieldRef
 *   unit       ::= KM | M
 *   orderList  ::= orderClause (COMMA orderClause)*
 *   orderClause::= fieldRef (ASC | DESC)?
 * </pre>
 *
 * <p>This is the same parsing technique used in GCC (for C), javac (for Java),
 * and V8 (for JavaScript). It is LL(1) — each production can be determined by
 * looking at the current token.</p>
 */
public class Parser {

    private final List<Token> tokens;
    private int pos;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    /**
     * Parses a complete MATCH query.
     *
     * @return the root AST node
     */
    public MatchQuery parse() {
        expect(TokenType.MATCH);
        String target = expect(TokenType.IDENTIFIER).value();

        // Optional WHERE clause
        AstNode whereClause = null;
        if (check(TokenType.WHERE)) {
            advance();
            whereClause = parseCondition();
        }

        // Optional ORDER BY clause
        List<OrderClause> orderBy = List.of();
        if (check(TokenType.ORDER)) {
            advance();
            expect(TokenType.BY);
            orderBy = parseOrderList();
        }

        // Optional LIMIT clause
        int limit = Integer.MAX_VALUE;
        if (check(TokenType.LIMIT)) {
            advance();
            limit = Integer.parseInt(expect(TokenType.NUMBER).value());
        }

        return new MatchQuery(target, whereClause, orderBy, limit);
    }

    /**
     * Parses a boolean condition: comparison ((AND|OR) comparison)*
     * Left-associative, AND binds tighter than OR.
     */
    private AstNode parseCondition() {
        AstNode left = parseComparison();

        while (check(TokenType.AND) || check(TokenType.OR)) {
            BooleanOp op = current().type() == TokenType.AND ? BooleanOp.AND : BooleanOp.OR;
            advance();
            AstNode right = parseComparison();
            left = new BooleanExpr(left, op, right);
        }

        return left;
    }

    /**
     * Parses a comparison: fieldRef op value
     */
    private Comparison parseComparison() {
        String field = parseFieldRef();
        ComparisonOp op = parseOperator();
        Value value = parseValue();
        return new Comparison(field, op, value);
    }

    /**
     * Parses a dotted field reference: IDENTIFIER (DOT IDENTIFIER)*
     * E.g., "vehicle.type" or "rating"
     */
    private String parseFieldRef() {
        StringBuilder sb = new StringBuilder(expect(TokenType.IDENTIFIER).value());
        while (check(TokenType.DOT)) {
            advance();
            sb.append('.').append(expect(TokenType.IDENTIFIER).value());
        }
        return sb.toString();
    }

    private ComparisonOp parseOperator() {
        Token tok = current();
        advance();
        return switch (tok.type()) {
            case LT -> ComparisonOp.LT;
            case GT -> ComparisonOp.GT;
            case LTE -> ComparisonOp.LTE;
            case GTE -> ComparisonOp.GTE;
            case EQ -> ComparisonOp.EQ;
            case NEQ -> ComparisonOp.NEQ;
            default -> throw new Lexer.DslParseException(
                    "Expected comparison operator, got " + tok.type(), tok.position());
        };
    }

    private Value parseValue() {
        Token tok = current();
        return switch (tok.type()) {
            case NUMBER -> {
                advance();
                double num = Double.parseDouble(tok.value());
                String unit = "";
                if (check(TokenType.KM)) { unit = "km"; advance(); }
                else if (check(TokenType.M)) { unit = "m"; advance(); }
                yield new Value.NumberValue(num, unit);
            }
            case STRING -> {
                advance();
                yield new Value.StringValue(tok.value());
            }
            case IDENTIFIER -> {
                String ref = parseFieldRef();
                yield new Value.FieldRef(ref);
            }
            default -> throw new Lexer.DslParseException(
                    "Expected value, got " + tok.type(), tok.position());
        };
    }

    private List<OrderClause> parseOrderList() {
        List<OrderClause> clauses = new ArrayList<>();
        clauses.add(parseOrderClause());
        while (check(TokenType.COMMA)) {
            advance();
            clauses.add(parseOrderClause());
        }
        return clauses;
    }

    private OrderClause parseOrderClause() {
        String field = parseFieldRef();
        SortDirection dir = SortDirection.ASC; // default
        if (check(TokenType.ASC)) { advance(); dir = SortDirection.ASC; }
        else if (check(TokenType.DESC)) { advance(); dir = SortDirection.DESC; }
        return new OrderClause(field, dir);
    }

    // ── Token helpers ──

    private Token current() {
        return tokens.get(pos);
    }

    private boolean check(TokenType type) {
        return pos < tokens.size() && tokens.get(pos).type() == type;
    }

    private Token advance() {
        return tokens.get(pos++);
    }

    private Token expect(TokenType type) {
        Token tok = current();
        if (tok.type() != type) {
            throw new Lexer.DslParseException(
                    "Expected " + type + " but got " + tok.type() + "('" + tok.value() + "')",
                    tok.position());
        }
        return advance();
    }
}
