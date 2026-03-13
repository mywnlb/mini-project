
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

public class CompareOldNew {
  static RelNode build(String sql) {
    MockCatalog catalog = new MockCatalog();
    SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    SqlNode validated = new SqlValidator(catalog).validate(ast);
    RelNode logical = new SqlToRelConverter(catalog).convert(validated);
    return new NewRuleOptimizer().optimize(logical);
  }
  static RelNode buildOld(String sql) {
    MockCatalog catalog = new MockCatalog();
    SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    SqlNode validated = new SqlValidator(catalog).validate(ast);
    RelNode logical = new SqlToRelConverter(catalog).convert(validated);
    return new OldRuleOptimizer().optimize(logical);
  }
  static class NewRuleOptimizer extends RuleOptimizer {}
  static class OldJoinCommuteRule extends RelOptRule {
    public boolean matches(RelNode node) {
      if (!(node instanceof RelJoin join)) return false;
      return leftRowCount(join) > rightRowCount(join);
    }
    public RelNode apply(RelNode node) {
      RelJoin join = (RelJoin) node;
      return new RelJoin(join.right(), join.left(), join.condition());
    }
    private long leftRowCount(RelJoin join) { return join.left() instanceof RelScan s ? s.tableMeta().rowCount() : Long.MAX_VALUE; }
    private long rightRowCount(RelJoin join) { return join.right() instanceof RelScan s ? s.tableMeta().rowCount() : Long.MAX_VALUE; }
  }
  static class OldRuleOptimizer {
    private final List<RelOptRule> rules = List.of(
      FilterProjectTransposeRule.INSTANCE,
      FilterJoinPushdownRule.INSTANCE,
      PushFilterIntoScanRule.INSTANCE,
      new OldJoinCommuteRule()
    );
    public RelNode optimize(RelNode root) {
      RelNode current = optimizeChildren(root);
      boolean changed = true;
      int iterations = 0;
      while (changed && iterations++ < 20) {
        changed = false;
        for (RelOptRule rule : rules) {
          if (rule.matches(current)) {
            RelNode next = rule.apply(current);
            if (next != current) {
              current = next;
              changed = true;
              current = optimizeChildren(current);
              break;
            }
          }
        }
      }
      return current;
    }
    private RelNode optimizeChildren(RelNode node) {
      if (node instanceof RelProject project) {
        RelNode opt = optimize(project.input());
        return opt != project.input() ? project.copy(List.of(opt)) : node;
      }
      if (node instanceof RelDistinct distinct) {
        RelNode opt = optimize(distinct.input());
        return opt != distinct.input() ? distinct.copy(List.of(opt)) : node;
      }
      if (node instanceof RelFilter filter) {
        RelNode opt = optimize(filter.input());
        return opt != filter.input() ? filter.copy(List.of(opt)) : node;
      }
      if (node instanceof RelJoin join) {
        RelNode optL = optimize(join.left());
        RelNode optR = optimize(join.right());
        return (optL != join.left() || optR != join.right()) ? join.copy(List.of(optL, optR)) : node;
      }
      if (node instanceof RelAggregate agg) {
        RelNode opt = optimize(agg.input());
        return opt != agg.input() ? agg.copy(List.of(opt)) : node;
      }
      if (node instanceof RelSort sort) {
        RelNode opt = optimize(sort.input());
        return opt != sort.input() ? sort.copy(List.of(opt)) : node;
      }
      return node;
    }
  }
  static class OldPhysicalPlanner {
    private final CostOptimizer costOptimizer = new CostOptimizer();
    private ExecNode planInternal(RelNode relNode, PhysicalPlanner.JoinAlgorithm overrideAlgo) {
      if (relNode instanceof RelIndexedScan indexed) {
        ExecNode scan = new ScanExec(indexed.tableName(), indexed.outputName(), MockDataSourceAdapter.INSTANCE);
        return new FilterExec(scan, indexed.indexCondition());
      }
      if (relNode instanceof RelScan scan) return new ScanExec(scan.tableName(), scan.outputName(), MockDataSourceAdapter.INSTANCE);
      if (relNode instanceof RelFilter filter) return new FilterExec(planInternal(filter.input(), overrideAlgo), filter.condition());
      if (relNode instanceof RelProject project) return new ProjectExec(planInternal(project.input(), overrideAlgo), project.projection());
      if (relNode instanceof RelDistinct distinct) return new DistinctExec(planInternal(distinct.input(), overrideAlgo));
      if (relNode instanceof RelJoin join) return planJoin(join, overrideAlgo);
      throw new IllegalArgumentException();
    }
    private ExecNode planJoin(RelJoin join, PhysicalPlanner.JoinAlgorithm overrideAlgo) {
      ExecNode left = planInternal(join.left(), overrideAlgo);
      ExecNode right = planInternal(join.right(), overrideAlgo);
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
      if (condition instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.BINARY_EQ) {
        if (binOp.left() instanceof SqlIdentifier left && binOp.right() instanceof SqlIdentifier right) {
          return new String[]{left.name(), right.name()};
        }
      }
      return null;
    }
    ExecNode plan(RelNode relNode, PhysicalPlanner.JoinAlgorithm algo) { return planInternal(relNode, algo); }
  }
  static List<String> rows(RelNode plan, boolean old, PhysicalPlanner.JoinAlgorithm algo) {
    MockDataSource.reset();
    ExecNode exec = old ? new OldPhysicalPlanner().plan(plan, algo) : new PhysicalPlanner().plan(plan, algo);
    List<String> out = new ArrayList<>();
    exec.open();
    Row row; while ((row = exec.next()) != null) out.add(row.toString());
    exec.close(); Collections.sort(out); return out;
  }
  public static void main(String[] args) {
    List<String> sqls = List.of(
      "SELECT * FROM users JOIN orders ON users.id = orders.user_id",
      "SELECT * FROM orders JOIN users ON orders.user_id = users.id",
      "SELECT * FROM users AS u JOIN users AS v ON id = id",
      "SELECT * FROM users AS u JOIN users AS v ON u.id = v.id"
    );
    for (String sql : sqls) {
      RelNode oldPlan = buildOld(sql);
      RelNode newPlan = build(sql);
      for (PhysicalPlanner.JoinAlgorithm algo : List.of(PhysicalPlanner.JoinAlgorithm.HASH_JOIN, PhysicalPlanner.JoinAlgorithm.SORT_MERGE, PhysicalPlanner.JoinAlgorithm.INDEX_NESTED_LOOP)) {
        List<String> oldRows = rows(oldPlan, true, algo);
        List<String> newRows = rows(newPlan, false, algo);
        if (!oldRows.equals(newRows)) {
          System.out.println("SQL=" + sql + " ALGO=" + algo);
          System.out.println("OLD(" + oldRows.size() + ")=" + oldRows);
          System.out.println("NEW(" + newRows.size() + ")=" + newRows);
        }
      }
    }
  }
}
