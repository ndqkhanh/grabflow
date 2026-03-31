package grabflow.ride.dsl;

import java.util.List;

/**
 * Abstract Syntax Tree nodes for the matching DSL.
 *
 * <h3>CS Fundamental: Abstract Syntax Tree (AST)</h3>
 * <p>The AST is the central data structure of a compiler. It represents the
 * hierarchical structure of the source code after parsing, stripped of syntactic
 * sugar (parentheses, keywords, whitespace). Each node type corresponds to a
 * semantic construct in the language.</p>
 *
 * <h3>AST Structure for Matching DSL</h3>
 * <pre>
 *   MatchQuery
 *   ├── target: "driver"
 *   ├── where: BooleanExpr (AND/OR tree of Comparisons)
 *   ├── orderBy: [OrderClause(field, ASC/DESC)]
 *   └── limit: 3
 * </pre>
 */
public sealed interface AstNode {

    /**
     * Root node: represents a complete MATCH query.
     */
    record MatchQuery(
            String target,
            AstNode whereClause,
            List<OrderClause> orderBy,
            int limit
    ) implements AstNode {}

    /**
     * A comparison expression: {@code field op value}.
     * E.g., {@code distance < 5km}, {@code rating > 4.5}, {@code vehicle.type = "sedan"}
     */
    record Comparison(
            String field,
            ComparisonOp op,
            Value value
    ) implements AstNode {}

    /**
     * A boolean combination of expressions: {@code left AND/OR right}.
     */
    record BooleanExpr(
            AstNode left,
            BooleanOp op,
            AstNode right
    ) implements AstNode {}

    /**
     * An ORDER BY clause: {@code field ASC/DESC}.
     */
    record OrderClause(
            String field,
            SortDirection direction
    ) implements AstNode {}

    /**
     * A typed value (the right-hand side of a comparison).
     */
    sealed interface Value {
        record NumberValue(double value, String unit) implements Value {
            /** Returns value converted to base units (meters for distance). */
            public double toBaseUnit() {
                if ("km".equalsIgnoreCase(unit)) return value * 1000;
                if ("m".equalsIgnoreCase(unit)) return value;
                return value; // no unit = raw number
            }
        }

        record StringValue(String value) implements Value {}
        record FieldRef(String fieldName) implements Value {}
    }

    enum ComparisonOp {
        LT, GT, LTE, GTE, EQ, NEQ;

        public boolean evaluate(double left, double right) {
            return switch (this) {
                case LT -> left < right;
                case GT -> left > right;
                case LTE -> left <= right;
                case GTE -> left >= right;
                case EQ -> left == right;
                case NEQ -> left != right;
            };
        }

        public boolean evaluate(String left, String right) {
            return switch (this) {
                case EQ -> left.equalsIgnoreCase(right);
                case NEQ -> !left.equalsIgnoreCase(right);
                default -> throw new UnsupportedOperationException(
                        "Cannot apply " + this + " to strings");
            };
        }
    }

    enum BooleanOp { AND, OR }
    enum SortDirection { ASC, DESC }
}
