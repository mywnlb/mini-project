package cn.zhangyis.minidb.sql.planner;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.ValidatedSqlSelect;

public class SqlToRelConverter {
    private final RelFactories factories;

    public SqlToRelConverter(RelFactories factories) {
        this.factories = factories;
    }

    public RelNode convert(ValidatedSqlSelect validated) {
        SqlSelect select = validated.original();
        TableMeta table = validated.tableMeta();

        RelNode scan = factories.scan(select.table().name(), table);

        RelNode plan = scan;
        if (select.where() != null) {
            plan = factories.filter(plan, select.where());
        }
        plan = factories.project(plan, select.projection());

        return plan;
    }
}

interface RelFactories {
    RelScan scan(String tableName, TableMeta meta);
    RelFilter filter(RelNode input, SqlNode condition);
    RelProject project(RelNode input, SqlNodeList projection);
}

class DefaultRelFactories implements RelFactories {
    public static final DefaultRelFactories INSTANCE = new DefaultRelFactories();

    @Override
    public RelScan scan(String tableName, TableMeta meta) {
        return new RelScan(tableName, meta);
    }

    @Override
    public RelFilter filter(RelNode input, SqlNode condition) {
        return new RelFilter(input, condition);
    }

    @Override
    public RelProject project(RelNode input, SqlNodeList projection) {
        return new RelProject(input, projection);
    }
}