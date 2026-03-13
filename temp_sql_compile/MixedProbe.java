import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import java.util.*;

public class MixedProbe {
  static RelNode build(String sql) {
    MockCatalog catalog = new MockCatalog();
    SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    SqlNode validated = new SqlValidator(catalog).validate(ast);
    RelNode logical = new SqlToRelConverter(catalog).convert(validated);
    return new RuleOptimizer().optimize(logical);
  }
  static List<String> rows(String sql, PhysicalPlanner.JoinAlgorithm algo) {
    MockDataSource.reset();
    ExecNode exec = new PhysicalPlanner().plan(build(sql), algo);
    List<String> out = new ArrayList<>();
    exec.open();
    try {
      Row row; while ((row = exec.next()) != null) out.add(row.toString());
    } finally { exec.close(); }
    Collections.sort(out);
    return out;
  }
  public static void main(String[] args) {
    String[] sqls = {
      "SELECT * FROM users AS u JOIN users AS v ON u.id = id",
      "SELECT * FROM users AS u JOIN users AS v ON id = u.id",
      "SELECT * FROM users AS u JOIN users AS v ON v.id = id",
      "SELECT * FROM users AS u JOIN users AS v ON id = v.id"
    };
    for (String sql : sqls) {
      System.out.println("SQL=" + sql);
      for (var algo : PhysicalPlanner.JoinAlgorithm.values()) {
        var rows = rows(sql, algo);
        System.out.println(algo + " => " + rows.size());
      }
    }
  }
}
