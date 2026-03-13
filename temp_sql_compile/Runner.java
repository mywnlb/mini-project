
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
import java.util.*;

public class Runner {
  static RelNode build(String sql) {
    MockCatalog catalog = new MockCatalog();
    SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    SqlNode validated = new SqlValidator(catalog).validate(ast);
    RelNode logical = new SqlToRelConverter(catalog).convert(validated);
    return new RuleOptimizer().optimize(logical);
  }
  static void run(String sql, PhysicalPlanner.JoinAlgorithm algo) {
    MockDataSource.reset();
    RelNode plan = build(sql);
    ExecNode exec = new PhysicalPlanner().plan(plan, algo);
    System.out.println("SQL=" + sql);
    System.out.println("PLAN=" + plan.explain());
    System.out.println("EXEC=" + exec.getClass().getSimpleName());
    exec.open();
    int n = 0;
    while (true) {
      Row row = exec.next();
      if (row == null) break;
      n++;
      if (n <= 10) System.out.println(row);
    }
    exec.close();
    System.out.println("COUNT=" + n);
    System.out.println();
  }
  public static void main(String[] args) {
    run("SELECT * FROM orders JOIN users ON orders.user_id = users.id", PhysicalPlanner.JoinAlgorithm.HASH_JOIN);
    run("SELECT * FROM orders JOIN users ON orders.user_id = users.id WHERE users.name = 'alice'", PhysicalPlanner.JoinAlgorithm.HASH_JOIN);
    run("SELECT * FROM users AS u JOIN users AS v ON id = id", PhysicalPlanner.JoinAlgorithm.HASH_JOIN);
    run("SELECT * FROM users AS u JOIN users AS v ON u.id = v.id", PhysicalPlanner.JoinAlgorithm.HASH_JOIN);
  }
}
