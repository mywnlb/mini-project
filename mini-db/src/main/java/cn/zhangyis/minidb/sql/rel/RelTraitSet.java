package cn.zhangyis.minidb.sql.rel;

public interface RelTraitSet {
    RelTraitSet DEFAULT = new DefaultRelTraitSet();
}

class DefaultRelTraitSet implements RelTraitSet {}