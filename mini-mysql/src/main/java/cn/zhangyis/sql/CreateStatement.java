package cn.zhangyis.sql;

import java.util.List;

/**
 * CREATE TABLE `tb_test` (
 *   `id` bigint(20) NOT NULL COMMENT '1',
 *   `name` varchar(255) DEFAULT NULL COMMENT '2',
 *   `age` int(11) DEFAULT NULL COMMENT '4',
 *   `money` double(20,5) DEFAULT NULL COMMENT '5',
 *   PRIMARY KEY (`id`),
 *   KEY `name_index` (`name`,`age`),
 *   KEY `age_index` (`age`)
 * )  COMMENT='asdasdsa';
 */
public class CreateStatement extends SQLStatement {
    /**
     * 表名称
     */
    private String tableName;

    /**
     * 字段信息
     */
    private List<ColumnDefinition> columns;

    /**
     * 索引信息
     */
    private List<IndexDefinition> indexes;

    /**
     * 表注释
     */
    private String tableComment;


    public CreateStatement( String tableName, List<ColumnDefinition> columns, List<IndexDefinition> indexes, String tableComment) {
        super(SQLType.CREATE);
        this.tableName = tableName;
        this.columns = columns;
        this.indexes = indexes;
        this.tableComment = tableComment;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ColumnDefinition> getColumns() {
        return columns;
    }

    public List<IndexDefinition> getIndexes() {
        return indexes;
    }

    public String getTableComment() {
        return tableComment;
    }

    @Override
    public String toString() {
        return "CreateStatement{" +
                "tableName='" + tableName + '\'' +
                ", columns=" + columns +
                ", indexes=" + indexes +
                ", tableComment='" + tableComment + '\'' +
                "} " + super.toString();
    }
}