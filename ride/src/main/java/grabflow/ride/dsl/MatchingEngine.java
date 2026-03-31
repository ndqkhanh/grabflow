package grabflow.ride.dsl;

import grabflow.common.DriverLocation;
import grabflow.ride.dsl.AstNode.*;

import java.util.*;

/**
 * Interpreter that evaluates a parsed matching DSL query against a set of drivers.
 *
 * <h3>CS Fundamental: Tree-Walking Interpreter</h3>
 * <p>The interpreter walks the AST produced by the {@link Parser} and evaluates
 * each node against a driver context. This is the same execution model used by
 * CPython, Ruby MRI, and early JavaScript engines before JIT compilation.</p>
 *
 * <h3>Evaluation Pipeline</h3>
 * <pre>
 *   Source → Lexer → Parser → AST → Interpreter → Matched Drivers
 *   "MATCH driver WHERE distance < 5km"
 *       → tokens → MatchQuery AST → evaluate against drivers → filtered list
 * </pre>
 */
public class MatchingEngine {

    /**
     * Context provided for each driver evaluation. Contains the driver's data
     * and the rider's query location for distance calculations.
     */
    public record MatchContext(
            DriverLocation driver,
            double riderLat,
            double riderLng,
            double distanceMeters
    ) {}

    /**
     * Compiles a DSL query string and evaluates it against a list of drivers.
     *
     * @param query      DSL query string
     * @param drivers    candidate drivers
     * @param riderLat   rider's latitude
     * @param riderLng   rider's longitude
     * @param distanceCalculator function to compute distance
     * @return matched drivers sorted per ORDER BY clause
     */
    public List<DriverLocation> match(String query,
                                       List<DriverLocation> drivers,
                                       double riderLat, double riderLng,
                                       DistanceCalculator distanceCalculator) {
        // Phase 1: Lex
        Lexer lexer = new Lexer(query);
        List<Token> tokens = lexer.tokenize();

        // Phase 2: Parse
        Parser parser = new Parser(tokens);
        MatchQuery ast = parser.parse();

        // Phase 3: Evaluate
        List<MatchContext> matched = new ArrayList<>();
        for (DriverLocation driver : drivers) {
            double distance = distanceCalculator.distanceMeters(
                    riderLat, riderLng, driver.lat(), driver.lng());
            MatchContext ctx = new MatchContext(driver, riderLat, riderLng, distance);

            if (ast.whereClause() == null || evaluateCondition(ast.whereClause(), ctx)) {
                matched.add(ctx);
            }
        }

        // Phase 4: Sort
        if (!ast.orderBy().isEmpty()) {
            Comparator<MatchContext> comparator = buildComparator(ast.orderBy());
            matched.sort(comparator);
        }

        // Phase 5: Limit
        int limit = Math.min(ast.limit(), matched.size());
        return matched.subList(0, limit).stream()
                .map(MatchContext::driver)
                .toList();
    }

    /**
     * Evaluates a WHERE condition against a driver context.
     */
    boolean evaluateCondition(AstNode node, MatchContext ctx) {
        return switch (node) {
            case Comparison comp -> evaluateComparison(comp, ctx);
            case BooleanExpr bool -> {
                boolean left = evaluateCondition(bool.left(), ctx);
                boolean right = evaluateCondition(bool.right(), ctx);
                yield bool.op() == BooleanOp.AND ? left && right : left || right;
            }
            default -> throw new IllegalStateException("Unexpected AST node: " + node.getClass());
        };
    }

    private boolean evaluateComparison(Comparison comp, MatchContext ctx) {
        double fieldValue = resolveNumericField(comp.field(), ctx);

        return switch (comp.value()) {
            case Value.NumberValue num -> comp.op().evaluate(fieldValue, num.toBaseUnit());
            case Value.StringValue str -> {
                String strFieldValue = resolveStringField(comp.field(), ctx);
                yield comp.op().evaluate(strFieldValue, str.value());
            }
            case Value.FieldRef ref -> {
                double refValue = resolveNumericField(ref.fieldName(), ctx);
                yield comp.op().evaluate(fieldValue, refValue);
            }
        };
    }

    private double resolveNumericField(String field, MatchContext ctx) {
        return switch (field.toLowerCase()) {
            case "distance" -> ctx.distanceMeters();
            case "rating" -> 4.5; // placeholder -- would come from driver profile
            case "speed" -> ctx.driver().speed();
            case "heading" -> ctx.driver().heading();
            default -> throw new IllegalArgumentException("Unknown numeric field: " + field);
        };
    }

    private String resolveStringField(String field, MatchContext ctx) {
        return switch (field.toLowerCase()) {
            case "vehicle.type" -> "sedan"; // placeholder
            case "driverid", "driver.id" -> ctx.driver().driverId();
            default -> throw new IllegalArgumentException("Unknown string field: " + field);
        };
    }

    private Comparator<MatchContext> buildComparator(List<OrderClause> orderBy) {
        Comparator<MatchContext> comparator = null;
        for (OrderClause clause : orderBy) {
            Comparator<MatchContext> fieldComp = Comparator.comparingDouble(
                    ctx -> resolveNumericField(clause.field(), ctx));
            if (clause.direction() == SortDirection.DESC) {
                fieldComp = fieldComp.reversed();
            }
            comparator = comparator == null ? fieldComp : comparator.thenComparing(fieldComp);
        }
        return comparator;
    }

    /**
     * Functional interface for distance calculation (allows injection for testing).
     */
    @FunctionalInterface
    public interface DistanceCalculator {
        double distanceMeters(double lat1, double lng1, double lat2, double lng2);
    }
}
