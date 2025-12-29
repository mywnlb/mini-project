package cn.zhangyis.sql;

import cn.zhangyis.enums.FiledType;
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
 * @Date 2025/6/20 9:41
 * @Created by libo
 */
public class SemanticTest {

    @Test
    public void test() {
        String sql = "SELECT a.department_id, COUNT(*) as countvalue, SUM(a.salary) as total_salary " +
                "FROM employees a " +
                "JOIN departments d ON a.department_id = d.id " +
                "WHERE a.salary > 5000 AND d.location IN ('Beijing', 'Shanghai') " +
                "GROUP BY a.department_id " +
                "HAVING COUNT(*) > 5 " +
                "ORDER BY total_salary DESC " +
                "LIMIT 10";

        Table departments = new Table();
        departments.setName("departments");

        SQLStatement statement1 = new MainParser(sql).parse();
        CatalogManager catalogManager = CatalogManager.getInstance();
        Column dId = new Column(departments, "id", FiledType.BIGINT);
        Column dLocation = new Column(departments, "location", FiledType.VARCHAR);

        departments.setColumns(List.of(dId, dLocation));
        catalogManager.addTable(departments);

        Table employees = new Table();
        employees.setName("employees");
        Column employeesdepartment_id = new Column(employees, "department_id", FiledType.BIGINT);
        Column employeessalary = new Column(employees, "salary", FiledType.BIGINT);

        employees.setColumns(List.of(employeesdepartment_id, employeessalary));
        catalogManager.addTable(employees);

        SemanticAnalyzer semanticAnalyzer = new DefaultSemanticAnalyzer(catalogManager);
        try {
            semanticAnalyzer.analyze(statement1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Test
    public void testsadsada(){
        if(true){
            return;
        }
        System.out.println(111);
    }
}
