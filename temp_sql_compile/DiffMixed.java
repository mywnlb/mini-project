import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.*;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.optimize.*;
import cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import java.util.*;

public class DiffMixed {
  static RelNode build(String sql, boolean old) {
    MockCatalog catalog = new MockCatalog();
    SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    SqlNode validated = new SqlValidator(catalog).validate(ast);
    RelNode logical = new SqlToRelConverter(catalog).convert(validated);
    return old ? new OldRuleOptimizer().optimize(logical) : new RuleOptimizer().optimize(logical);
  }
  static class OldJoinCommuteRule extends RelOptRule {
    public boolean matches(RelNode node) { if (!(node instanceof RelJoin join)) return false; return leftRowCount(join) > rightRowCount(join); }
    public RelNode apply(RelNode node) { RelJoin join = (RelJoin) node; return new RelJoin(join.right(), join.left(), join.condition()); }
    private long leftRowCount(RelJoin join) { return join.left() instanceof RelScan s ? s.tableMeta().rowCount() : Long.MAX_VALUE; }
    private long rightRowCount(RelJoin join) { return join.right() instanceof RelScan s ? s.tableMeta().rowCount() : Long.MAX_VALUE; }
  }
  static class OldRuleOptimizer {
    private final List<RelOptRule> rules = List.of(FilterProjectTransposeRule.INSTANCE, FilterJoinPushdownRule.INSTANCE, PushFilterIntoScanRule.INSTANCE, new OldJoinCommuteRule());
    public RelNode optimize(RelNode root) {
      RelNode current = optimizeChildren(root); boolean changed = true; int iterations = 0;
      while (changed && iterations++ < 20) {
        changed = false;
        for (RelOptRule rule : rules) {
          if (rule.matches(current)) {
            RelNode next = rule.apply(current);
            if (next != current) { current = optimizeChildren(next); changed = true; break; }
          }
        }
      }
      return current;
    }
    private RelNode optimizeChildren(RelNode node) {
      if (node instanceof RelProject project) { RelNode opt = optimize(project.input()); return opt != project.input() ? project.copy(List.of(opt)) : node; }
      if (node instanceof RelDistinct distinct) { RelNode opt = optimize(distinct.input()); return opt != distinct.input() ? distinct.copy(List.of(opt)) : node; }
      if (node instanceof RelFilter filter) { RelNode opt = optimize(filter.input()); return opt != filter.input() ? filter.copy(List.of(opt)) : node; }
      if (node instanceof RelJoin join) { RelNode l = optimize(join.left()); RelNode r = optimize(join.right()); return (l != join.left() || r != join.right()) ? join.copy(List.of(l,r)) : node; }
      if (node instanceof RelAggregate agg) { RelNode opt = optimize(agg.input()); return opt != agg.input() ? agg.copy(List.of(opt)) : node; }
      if (node instanceof RelSort sort) { RelNode opt = optimize(sort.input()); return opt != sort.input() ? sort.copy(List.of(opt)) : node; }
      return node;
    }
  }
  static class OldPhysicalPlanner {
    private final CostOptimizer costOptimizer = new CostOptimizer();
    ExecNode plan(RelNode relNode, PhysicalPlanner.JoinAlgorithm algo) { return planInternal(relNode, algo); }
    private ExecNode planInternal(RelNode relNode, PhysicalPlanner.JoinAlgorithm overrideAlgo) {
      if (relNode instanceof RelIndexedScan indexed) { ExecNode scan = new ScanExec(indexed.tableName(), indexed.outputName(), MockDataSourceAdapter.INSTANCE); return new FilterExec(scan, indexed.indexCondition()); }
      if (relNode instanceof RelScan scan) return new ScanExec(scan.tableName(), scan.outputName(), MockDataSourceAdapter.INSTANCE);
      if (relNode instanceof RelFilter filter) return new FilterExec(planInternal(filter.input(), overrideAlgo), filter.condition());
      if (relNode instanceof RelProject project) return new ProjectExec(planInternal(project.input(), overrideAlgo), project.projection());
      if (relNode instanceof RelDistinct distinct) return new DistinctExec(planInternal(distinct.input(), overrideAlgo));
      if (relNode instanceof RelJoin join) return planJoin(join, overrideAlgo);
      if (relNode instanceof RelAggregate agg) return new AggregateExec(planInternal(agg.input(), overrideAlgo), agg.groupKeys(), agg.aggCalls());
      if (relNode instanceof RelSort sort) { Integer limit = null; if (sort.limit() instanceof SqlLiteral lit) limit = Integer.parseInt(lit.value()); return new SortExec(planInternal(sort.input(), overrideAlgo), sort.orderBy(), limit); }
      throw new IllegalArgumentException();
    }
    private ExecNode planJoin(RelJoin join, PhysicalPlanner.JoinAlgorithm overrideAlgo) {
      ExecNode left = planInternal(join.left(), overrideAlgo); ExecNode right = planInternal(join.right(), overrideAlgo);
      PhysicalPlanner.JoinAlgorithm algo = overrideAlgo != null ? overrideAlgo : costOptimizer.chooseJoinAlgorithm(join);
      String[] keys = extractJoinKeys(join.condition());
      return switch (algo) {
        case NESTED_LOOP -> new NestedLoopJoinExec(left, right, join.condition());
        case HASH_JOIN -> keys != null ? new HashJoinExec(left, right, keys[0], keys[1]) : new NestedLoopJoinExec(left, right, join.condition());
        case SORT_MERGE -> keys != null ? new SortMergeJoinExec(left, right, keys[0], keys[1]) : new NestedLoopJoinExec(left, right, join.condition());
        case INDEX_NESTED_LOOP -> keys != null ? new IndexNestedLoopJoinExec(left, right, keys[0], keys[1]) : new NestedLoopJoinExec(left, right, join.condition());
      };
    }
    private String[] extractJoinKeys(SqlNode condition) {
      if (condition instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.BINARY_EQ && binOp.left() instanceof SqlIdentifier left && binOp.right() instanceof SqlIdentifier right) return new String[]{left.name(), right.name()};
      return null;
    }
  }
  static int count(String sql, boolean old, PhysicalPlanner.JoinAlgorithm algo) {
    MockDataSource.reset();
    RelNode plan = build(sql, old);
    ExecNode exec = old ? new OldPhysicalPlanner().plan(plan, algo) : new PhysicalPlanner().plan(plan, algo);
    exec.open(); int n = 0; while (exec.next() != null) n++; exec.close(); return n;
  }
  public static void main(String[] args) {
    String[] exprs = {"id", "u.id", "v.id", "name", "u.name", "v.name"};
    for (String a : exprs) for (String b : exprs) {
      String sql = "SELECT * FROM users AS u JOIN users AS v ON " + a + " = " + b;
      int oldCount = count(sql, true, PhysicalPlanner.JoinAlgorithm.HASH_JOIN);
      int newCount = count(sql, false, PhysicalPlanner.JoinAlgorithm.HASH_JOIN);
      if (oldCount != newCount) {
        System.out.println(sql + " :: old=" + oldCount + " new=" + newCount);
      }
    }
  }
}
