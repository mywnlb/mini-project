package cn.zhangyis.minidb.sql.planner;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.ValidatedDml;
import cn.zhangyis.minidb.sql.validation.ValidatedSqlSelect;

import java.util.ArrayList;
import java.util.List;

public class SqlToRelConverter {
    private final RelFactories factories;
    private final CatalogSpi catalog;

    public SqlToRelConverter() {
        this(DefaultRelFactories.INSTANCE, null);
    }

    public SqlToRelConverter(CatalogSpi catalog) {
        this(DefaultRelFactories.INSTANCE, catalog);
    }

    public SqlToRelConverter(RelFactories factories) {
        this(factories, null);
    }

    public SqlToRelConverter(RelFactories factories, CatalogSpi catalog) {
        this.factories = factories;
        this.catalog = catalog;
    }

    public RelNode convert(SqlNode validated) {
        return switch (validated) {
            case ValidatedSqlSelect select -> convertSelect(select);
            case ValidatedDml dml -> convertDml(dml);
            case SqlCreateTable create -> new RelCreateTable(create, catalog);
            case SqlDropTable drop -> new RelDropTable(drop, catalog);
            case SqlAlterTable alter -> new RelAlterTable(alter, catalog);
            case SqlCreateIndex createIdx -> new RelCreateIndex(createIdx, catalog);
            case SqlDropIndex dropIdx -> new RelDropIndex(dropIdx, catalog);
            default -> throw new IllegalArgumentException("Unsupported: " + validated.kind());
        };
    }

    // ==================== SELECT ====================

    private RelNode convertSelect(ValidatedSqlSelect validated) {
        SqlSelect select = validated.original();
        RelNode plan;

        if (validated.isJoin()) {
            SqlJoin join = (SqlJoin) select.from();
            SqlIdentifier leftId = (SqlIdentifier) join.left();
            SqlIdentifier rightId = (SqlIdentifier) join.right();
            RelScan leftScan = factories.scan(leftId.name(), validated.leftTable());
            RelScan rightScan = factories.scan(rightId.name(), validated.rightTable());
            plan = factories.join(leftScan, rightScan, join.condition());
        } else {
            SqlIdentifier tableId = (SqlIdentifier) select.from();
            plan = factories.scan(tableId.name(), validated.tableMeta());
        }

        if (select.where() != null) {
            plan = factories.filter(plan, select.where());
        }

        if (select.groupBy() != null) {
            List<SqlAggCall> aggCalls = extractAggCalls(select);
            plan = new RelAggregate(plan, select.groupBy(), aggCalls);
            if (select.having() != null) {
                plan = factories.filter(plan, select.having());
            }
        }

        plan = factories.project(plan, select.projection());

        if (select.orderBy() != null || select.limit() != null) {
            plan = new RelSort(plan, select.orderBy(), select.limit());
        }

        return plan;
    }

    private List<SqlAggCall> extractAggCalls(SqlSelect select) {
        List<SqlAggCall> aggCalls = new ArrayList<>();
        for (SqlNode node : select.projection().nodes()) {
            if (node instanceof SqlAggCall agg) {
                aggCalls.add(agg);
            }
        }
        return aggCalls;
    }

    // ==================== DML ====================

    private RelNode convertDml(ValidatedDml dml) {
        return switch (dml.original()) {
            case SqlInsert insert -> convertInsert(insert, dml.tableMeta());
            case SqlUpdate update -> convertUpdate(update, dml.tableMeta());
            case SqlDelete delete -> convertDelete(delete, dml.tableMeta());
            default -> throw new IllegalArgumentException("Unsupported DML: " + dml.kind());
        };
    }

    private RelNode convertInsert(SqlInsert insert, TableMeta table) {
        return new RelInsert(insert.table().name(), table, insert.columns(), insert.valueRows());
    }

    private RelNode convertUpdate(SqlUpdate update, TableMeta table) {
        RelNode scan = factories.scan(update.table().name(), table);
        RelNode plan = scan;
        if (update.where() != null) {
            plan = factories.filter(plan, update.where());
        }
        return new RelUpdate(plan, update.assignments());
    }

    private RelNode convertDelete(SqlDelete delete, TableMeta table) {
        RelNode scan = factories.scan(delete.table().name(), table);
        RelNode plan = scan;
        if (delete.where() != null) {
            plan = factories.filter(plan, delete.where());
        }
        return new RelDelete(plan);
    }
}

interface RelFactories {
    RelScan scan(String tableName, TableMeta meta);
    RelFilter filter(RelNode input, SqlNode condition);
    RelProject project(RelNode input, SqlNodeList projection);
    RelJoin join(RelScan left, RelScan right, SqlNode condition);
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

    @Override
    public RelJoin join(RelScan left, RelScan right, SqlNode condition) {
        return new RelJoin(left, right, condition);
    }
}
