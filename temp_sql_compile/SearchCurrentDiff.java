import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.validation.SqlValidator;

public class SearchCurrentDiff {
  static RelNode build(String sql) {
    MockCatalog catalog = new MockCatalog();
    SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    SqlNode validated = new SqlValidator(catalog).validate(ast);
    RelNode logical = new SqlToRelConverter(catalog).convert(validated);
    return new RuleOptimizer().optimize(logical);
  }
  static int count(String sql, PhysicalPlanner.JoinAlgorithm algo) {
    MockDataSource.reset();
    ExecNode exec = new PhysicalPlanner().plan(build(sql), algo);
    exec.open(); int n = 0; while (exec.next() != null) n++; exec.close(); return n;
  }
  public static void main(String[] args) {
    String[] exprs = {"id", "u.id", "v.id", "name", "u.name", "v.name"};
    for (String a : exprs) for (String b : exprs) {
      String sql = "SELECT * FROM users AS u JOIN users AS v ON " + a + " = " + b;
      int nl = count(sql, PhysicalPlanner.JoinAlgorithm.NESTED_LOOP);
      for (var algo : new PhysicalPlanner.JoinAlgorithm[]{PhysicalPlanner.JoinAlgorithm.HASH_JOIN, PhysicalPlanner.JoinAlgorithm.SORT_MERGE, PhysicalPlanner.JoinAlgorithm.INDEX_NESTED_LOOP}) {
        int n = count(sql, algo);
        if (n != nl) {
          System.out.println(sql + " :: NL=" + nl + " " + algo + "=" + n);
        }
      }
    }
  }
}
