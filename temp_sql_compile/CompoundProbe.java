import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.validation.SqlValidator;

public class CompoundProbe {
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
    String[] sqls = {
      "SELECT * FROM users AS u JOIN users AS v ON id = id AND u.name = 'alice'",
      "SELECT * FROM users AS u JOIN users AS v ON id = id AND v.name = 'alice'",
      "SELECT * FROM users AS u JOIN users AS v ON u.id = v.id AND id = id",
      "SELECT * FROM users AS u JOIN users AS v ON u.id = v.id AND name = name"
    };
    for (String sql : sqls) {
      System.out.println(sql);
      for (var algo : PhysicalPlanner.JoinAlgorithm.values()) {
        try { System.out.println(algo + " => " + count(sql, algo)); }
        catch (Throwable t) { System.out.println(algo + " => EX " + t); }
      }
    }
  }
}
