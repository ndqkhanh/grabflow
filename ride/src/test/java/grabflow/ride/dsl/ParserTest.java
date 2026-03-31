package grabflow.ride.dsl;

import grabflow.ride.dsl.AstNode.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ParserTest {

    @Test
    void parsesMinimalQuery() {
        MatchQuery query = parse("MATCH driver");
        assertThat(query.target()).isEqualTo("driver");
        assertThat(query.whereClause()).isNull();
        assertThat(query.orderBy()).isEmpty();
        assertThat(query.limit()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void parsesWhereWithComparison() {
        MatchQuery query = parse("MATCH driver WHERE distance < 5km");
        assertThat(query.whereClause()).isInstanceOf(Comparison.class);

        Comparison comp = (Comparison) query.whereClause();
        assertThat(comp.field()).isEqualTo("distance");
        assertThat(comp.op()).isEqualTo(ComparisonOp.LT);
        assertThat(comp.value()).isInstanceOf(Value.NumberValue.class);

        Value.NumberValue numVal = (Value.NumberValue) comp.value();
        assertThat(numVal.value()).isEqualTo(5.0);
        assertThat(numVal.unit()).isEqualTo("km");
        assertThat(numVal.toBaseUnit()).isEqualTo(5000.0);
    }

    @Test
    void parsesAndCondition() {
        MatchQuery query = parse("MATCH driver WHERE distance < 5km AND rating > 4.5");
        assertThat(query.whereClause()).isInstanceOf(BooleanExpr.class);

        BooleanExpr bool = (BooleanExpr) query.whereClause();
        assertThat(bool.op()).isEqualTo(BooleanOp.AND);
        assertThat(bool.left()).isInstanceOf(Comparison.class);
        assertThat(bool.right()).isInstanceOf(Comparison.class);
    }

    @Test
    void parsesOrderByAsc() {
        MatchQuery query = parse("MATCH driver ORDER BY distance ASC");
        assertThat(query.orderBy()).hasSize(1);
        assertThat(query.orderBy().getFirst().field()).isEqualTo("distance");
        assertThat(query.orderBy().getFirst().direction()).isEqualTo(SortDirection.ASC);
    }

    @Test
    void parsesOrderByDesc() {
        MatchQuery query = parse("MATCH driver ORDER BY rating DESC");
        assertThat(query.orderBy().getFirst().direction()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void parsesLimit() {
        MatchQuery query = parse("MATCH driver LIMIT 3");
        assertThat(query.limit()).isEqualTo(3);
    }

    @Test
    void parsesFullQuery() {
        MatchQuery query = parse(
                "MATCH driver WHERE distance < 5km AND rating >= 4.0 ORDER BY distance ASC LIMIT 3");
        assertThat(query.target()).isEqualTo("driver");
        assertThat(query.whereClause()).isInstanceOf(BooleanExpr.class);
        assertThat(query.orderBy()).hasSize(1);
        assertThat(query.limit()).isEqualTo(3);
    }

    @Test
    void parsesStringComparison() {
        MatchQuery query = parse("MATCH driver WHERE vehicle.type = \"sedan\"");
        Comparison comp = (Comparison) query.whereClause();
        assertThat(comp.field()).isEqualTo("vehicle.type");
        assertThat(comp.op()).isEqualTo(ComparisonOp.EQ);
        assertThat(comp.value()).isInstanceOf(Value.StringValue.class);
        assertThat(((Value.StringValue) comp.value()).value()).isEqualTo("sedan");
    }

    @Test
    void parsesMultipleOrderBy() {
        MatchQuery query = parse("MATCH driver ORDER BY distance ASC, rating DESC");
        assertThat(query.orderBy()).hasSize(2);
        assertThat(query.orderBy().get(0).field()).isEqualTo("distance");
        assertThat(query.orderBy().get(1).field()).isEqualTo("rating");
    }

    @Test
    void defaultOrderIsAsc() {
        MatchQuery query = parse("MATCH driver ORDER BY distance");
        assertThat(query.orderBy().getFirst().direction()).isEqualTo(SortDirection.ASC);
    }

    @Test
    void invalidQueryThrows() {
        assertThatThrownBy(() -> parse("WHERE distance < 5"))
                .isInstanceOf(Lexer.DslParseException.class);
    }

    private MatchQuery parse(String query) {
        return new Parser(new Lexer(query).tokenize()).parse();
    }
}
