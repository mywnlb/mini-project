
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.validation.SqlValidator;

public class Runner2 {
  static RelNode build(String sql) {
    MockCatalog catalog = new MockCatalog();
    SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    SqlNode validated = new SqlValidator(catalog).validate(ast);
    RelNode logical = new SqlToRelConverter(catalog).convert(validated);
    return new RuleOptimizer().optimize(logical);
  }
  static int count(String sql, PhysicalPlanner.JoinAlgorithm algo) {
    MockDataSource.reset();
    RelNode plan = build(sql);
    ExecNode exec = new PhysicalPlanner().plan(plan, algo);
    exec.open();
    int n = 0;
    while (exec.next() != null) n++;
    exec.close();
    return n;
  }
  public static void main(String[] args) {
    String[] sqls = {
      "SELECT * FROM users JOIN orders ON id = user_id",
      "SELECT * FROM orders JOIN users ON id = user_id",
      "SELECT * FROM users JOIN orders ON user_id = id",
      "SELECT * FROM orders JOIN users ON user_id = id"
    };
    for (String sql : sqls) {
      System.out.println(sql + " => " + count(sql, PhysicalPlanner.JoinAlgorithm.HASH_JOIN));
    }
  }
}
