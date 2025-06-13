package cn.zhangyis.sql;

import cn.zhangyis.sql.parser.MainParser;
import cn.zhangyis.sql.parser.SQLStatement;
import cn.zhangyis.sql.planner.semantic.DefaultSemanticAnalyzer;
import cn.zhangyis.sql.planner.semantic.SemanticAnalyzer;
import cn.zhangyis.storage.catalog.CatalogManager;
import cn.zhangyis.storage.catalog.Column;
import cn.zhangyis.storage.catalog.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @Description TODO
 * @Date 2025/6/6 14:35
 * @Created by libo
 */
public class SemanticAnalyzerScopeUtilTest {

    @Test
    public void testGetColumnType() {
        String sql = "SELECT a.id, a.name, b.age FROM tb_test a JOIN tb_test2 b ON a.id = b.id WHERE a.name = 'test'";
        CatalogManager catalogManager = CatalogManager.getInstance();
        Table table = new Table();
        table.setName("tb_test");
        table.setSchema("test_schema");
        table.setComment("Test table");
        table.setCreateSql("CREATE TABLE tb_test (id INT, name VARCHAR(50))");
        table.setEngine("InnoDB");
        table.setCharset("utf8mb4");
        table.setColumns(List.of(new Column("id", "INT"), new Column("name", "VARCHAR(50")));

        catalogManager.addTable(
            table
        );
        SemanticAnalyzer semanticAnalyzer = new DefaultSemanticAnalyzer(catalogManager);
        MainParser mainParser = new MainParser(sql);
        SQLStatement parse = mainParser.parse();
        semanticAnalyzer.analyze(parse);
    }
}
