/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.BoundSignature;
import io.prestosql.metadata.LiteralFunction;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.ResolvedFunction;
import io.prestosql.metadata.Signature;
import io.prestosql.operator.scalar.Re2JCastToRegexpFunction;
import io.prestosql.security.AllowAllAccessControl;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.spi.type.VarcharType;
import io.prestosql.sql.analyzer.ExpressionAnalyzer;
import io.prestosql.sql.analyzer.Scope;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.transaction.TestingTransactionManager;
import io.prestosql.type.Re2JRegexp;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.function.BiPredicate;

import static com.google.common.base.Verify.verify;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.testing.Assertions.assertEqualsIgnoreCase;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.metadata.FunctionId.toFunctionId;
import static io.prestosql.metadata.LiteralFunction.LITERAL_FUNCTION_NAME;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.scalar.JoniRegexpCasts.castVarcharToJoniRegexp;
import static io.prestosql.operator.scalar.JsonFunctions.castVarcharToJsonPath;
import static io.prestosql.operator.scalar.StringFunctions.castVarcharToCodePoints;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.CharType.createCharType;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static io.prestosql.spi.type.TypeSignatureParameter.typeVariable;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.spi.type.VarcharType.createVarcharType;
import static io.prestosql.sql.SqlFormatter.formatSql;
import static io.prestosql.sql.planner.ExpressionInterpreter.expressionInterpreter;
import static io.prestosql.transaction.TransactionBuilder.transaction;
import static io.prestosql.type.CodePointsType.CODE_POINTS;
import static io.prestosql.type.JoniRegexpType.JONI_REGEXP;
import static io.prestosql.type.JsonPathType.JSON_PATH;
import static io.prestosql.type.LikePatternType.LIKE_PATTERN;
import static io.prestosql.type.Re2JRegexpType.RE2J_REGEXP_SIGNATURE;
import static io.prestosql.type.UnknownType.UNKNOWN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestLiteralEncoder
{
    private final Metadata metadata = createTestMetadataManager();
    private final LiteralEncoder encoder = new LiteralEncoder(metadata);

    private final ResolvedFunction literalFunction = new ResolvedFunction(
            new BoundSignature(LITERAL_FUNCTION_NAME, VARBINARY, ImmutableList.of(VARBINARY)),
            new LiteralFunction(metadata::getBlockEncodingSerde).getFunctionMetadata().getFunctionId(),
            ImmutableMap.of(),
            ImmutableSet.of());

    private final ResolvedFunction base64Function = new ResolvedFunction(
            new BoundSignature("from_base64", VARBINARY, ImmutableList.of(VARCHAR)),
            toFunctionId(new Signature("from_base64", VARBINARY.getTypeSignature(), ImmutableList.of(new TypeSignature("varchar", typeVariable("x"))))),
            ImmutableMap.of(),
            ImmutableSet.of());

    @Test
    public void testEncode()
    {
        assertEncodeCaseInsensitively(utf8Slice("hello"), VARBINARY, literalVarbinary("hello".getBytes(UTF_8)));
        assertEncode(null, UNKNOWN, "null");
        assertEncode(null, BIGINT, "CAST(null AS bigint)");
        assertEncode(123L, BIGINT, "BIGINT '123'");

        assertEncode(123L, createDecimalType(7, 1), "CAST(DECIMAL '12.3' AS decimal(7, 1))");

        assertEncode(utf8Slice("hello"), createCharType(5), "CAST('hello' AS char(5))");
        assertEncode(utf8Slice("hello"), createCharType(13), "CAST('hello' AS char(13))");

        assertEncode(utf8Slice("hello"), createVarcharType(5), "'hello'");
        assertEncode(utf8Slice("hello"), createVarcharType(13), "CAST('hello' AS varchar(13))");
        assertEncode(utf8Slice("hello"), VARCHAR, "CAST('hello' AS varchar)");

        assertEncodeCaseInsensitively(utf8Slice("hello"), VARBINARY, literalVarbinary("hello".getBytes(UTF_8)));

        assertRoundTrip(castVarcharToJoniRegexp(utf8Slice("[a-z]")), LIKE_PATTERN, (left, right) -> left.pattern().equals(right.pattern()));
        assertRoundTrip(castVarcharToJoniRegexp(utf8Slice("[a-z]")), JONI_REGEXP, (left, right) -> left.pattern().equals(right.pattern()));
        assertRoundTrip(castVarcharToRe2JRegexp(utf8Slice("[a-z]")), metadata.getType(RE2J_REGEXP_SIGNATURE), (left, right) -> left.pattern().equals(right.pattern()));
        assertRoundTrip(castVarcharToJsonPath(utf8Slice("$.foo")), JSON_PATH, (left, right) -> left.pattern().equals(right.pattern()));
        assertRoundTrip(castVarcharToCodePoints(utf8Slice("hello")), CODE_POINTS, Arrays::equals);
    }

    private void assertEncode(Object value, Type type, String expected)
    {
        Expression expression = encoder.toExpression(value, type);
        assertEquals(getExpressionType(expression), type);
        assertEquals(getExpressionValue(expression), value);
        assertEquals(formatSql(expression), expected);
    }

    /**
     * @deprecated Use {@link #assertEncode} instead.
     */
    @Deprecated
    private void assertEncodeCaseInsensitively(Object value, Type type, String expected)
    {
        Expression expression = encoder.toExpression(value, type);
        assertEquals(getExpressionType(expression), type);
        assertEquals(getExpressionValue(expression), value);
        assertEqualsIgnoreCase(formatSql(expression), expected);
    }

    private <T> void assertRoundTrip(T value, Type type, BiPredicate<T, T> predicate)
    {
        Expression expression = encoder.toExpression(value, type);
        assertEquals(getExpressionType(expression), type);
        @SuppressWarnings("unchecked")
        T decodedValue = (T) getExpressionValue(expression);
        assertTrue(predicate.test(value, decodedValue));
    }

    private Object getExpressionValue(Expression expression)
    {
        return expressionInterpreter(expression, metadata, TEST_SESSION, getExpressionTypes(expression)).evaluate();
    }

    private Type getExpressionType(Expression expression)
    {
        Map<NodeRef<Expression>, Type> expressionTypes = getExpressionTypes(expression);
        Type expressionType = expressionTypes.get(NodeRef.of(expression));
        verify(expressionType != null, "No type found");
        return expressionType;
    }

    private Map<NodeRef<Expression>, Type> getExpressionTypes(Expression expression)
    {
        return transaction(new TestingTransactionManager(), new AllowAllAccessControl())
                .singleStatement()
                .execute(TEST_SESSION, transactionSession -> {
                    ExpressionAnalyzer expressionAnalyzer = ExpressionAnalyzer.createWithoutSubqueries(
                            metadata,
                            new AllowAllAccessControl(),
                            transactionSession,
                            TypeProvider.empty(),
                            emptyMap(),
                            node -> new IllegalStateException("Unexpected node: " + node),
                            WarningCollector.NOOP,
                            false);
                    expressionAnalyzer.analyze(expression, Scope.create());
                    Map<NodeRef<Expression>, Type> expressionTypes = expressionAnalyzer.getExpressionTypes();
                    return expressionTypes;
                });
    }

    private String literalVarbinary(byte[] value)
    {
        return "\"" + literalFunction.toQualifiedName() + "\"" +
                "(\"" + base64Function.toQualifiedName() + "\"" +
                "('" + Base64.getEncoder().encodeToString(value) + "'))";
    }

    private static Re2JRegexp castVarcharToRe2JRegexp(Slice value)
    {
        return Re2JCastToRegexpFunction.castToRegexp(Integer.MAX_VALUE, 5, false, VarcharType.UNBOUNDED_LENGTH, value);
    }
}
