
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

public class SearchDiff {
  static RelNode build(String sql) {
    MockCatalog catalog = new MockCatalog();
    SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    SqlNode validated = new SqlValidator(catalog).validate(ast);
    RelNode logical = new SqlToRelConverter(catalog).convert(validated);
    return new RuleOptimizer().optimize(logical);
  }
  static List<String> rows(String sql, PhysicalPlanner.JoinAlgorithm algo) {
    MockDataSource.reset();
    RelNode plan = build(sql);
    ExecNode exec = new PhysicalPlanner().plan(plan, algo);
    List<String> out = new ArrayList<>();
    exec.open();
    Row row;
    while ((row = exec.next()) != null) {
      out.add(row.toString());
    }
    exec.close();
    Collections.sort(out);
    return out;
  }
  public static void main(String[] args) {
    List<String> sqls = List.of(
      "SELECT * FROM users JOIN orders ON users.id = orders.user_id",
      "SELECT * FROM users JOIN orders ON orders.user_id = users.id",
      "SELECT * FROM orders JOIN users ON users.id = orders.user_id",
      "SELECT * FROM orders JOIN users ON orders.user_id = users.id",
      "SELECT * FROM users AS u JOIN orders AS o ON u.id = o.user_id",
      "SELECT * FROM users AS u JOIN orders AS o ON o.user_id = u.id",
      "SELECT * FROM orders AS o JOIN users AS u ON u.id = o.user_id",
      "SELECT * FROM orders AS o JOIN users AS u ON o.user_id = u.id",
      "SELECT * FROM users JOIN orders ON id = user_id",
      "SELECT * FROM users JOIN orders ON user_id = id",
      "SELECT * FROM orders JOIN users ON id = user_id",
      "SELECT * FROM orders JOIN users ON user_id = id",
      "SELECT * FROM users JOIN orders ON users.id = orders.user_id WHERE users.name = 'alice'",
      "SELECT * FROM orders JOIN users ON orders.user_id = users.id WHERE users.name = 'alice'",
      "SELECT * FROM users AS u JOIN orders AS o ON u.id = o.user_id WHERE o.amount > 100",
      "SELECT * FROM users AS u JOIN users AS v ON u.id = v.id",
      "SELECT * FROM users AS u JOIN users AS v ON v.id = u.id"
    );
    for (String sql : sqls) {
      List<String> nl = rows(sql, PhysicalPlanner.JoinAlgorithm.NESTED_LOOP);
      for (PhysicalPlanner.JoinAlgorithm algo : List.of(PhysicalPlanner.JoinAlgorithm.HASH_JOIN, PhysicalPlanner.JoinAlgorithm.SORT_MERGE, PhysicalPlanner.JoinAlgorithm.INDEX_NESTED_LOOP)) {
        List<String> got = rows(sql, algo);
        if (!nl.equals(got)) {
          System.out.println("DIFF " + algo + " :: " + sql);
          System.out.println("NL=" + nl.size() + " GOT=" + got.size());
          System.out.println("NL rows=" + nl);
          System.out.println("GOT rows=" + got);
        }
      }
    }
  }
}
