package cn.zhangyis.sql.parser;

import java.util.List;

public class IndexDefinition {
    private String name;
    private List<String> columns;
    private boolean isPrimaryKey;

    public IndexDefinition(String name, List<String> columns, boolean isPrimaryKey) {
        this.name = name;
        this.columns = columns;
        this.isPrimaryKey = isPrimaryKey;
    }

    public String getName() {
        return name;
    }

    public List<String> getColumns() {
        return columns;
    }

    public boolean isPrimaryKey() {
        return isPrimaryKey;
    }

    @Override
    public String toString() {
        return "IndexDefinition{" +
                "name='" + name + '\'' +
                ", columns=" + columns +
                ", isPrimaryKey=" + isPrimaryKey +
                '}';
    }
}