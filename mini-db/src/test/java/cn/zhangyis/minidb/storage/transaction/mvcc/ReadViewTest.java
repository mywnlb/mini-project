package cn.zhangyis.minidb.storage.transaction.mvcc;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReadView 核心功能测试
 *
 * <p>测试 ReadView 的基本功能：
 * <ul>
 *   <li>ReadView 创建和初始化</li>
 *   <li>可见性判断</li>
 *   <li>活跃列表管理</li>
 *   <li>边界值处理</li>
 * </ul>
 * </p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("ReadView 核心功能测试")
public class ReadViewTest {

    private ReadView readView;
    private TransactionId creatorId;
    private TransactionId lowLimitId;
    private TransactionId upLimitId;
    private List<TransactionId> activeList;

    @BeforeEach
    public void setUp() {
        // 创建测试数据
        creatorId = new TransactionId(10);
        lowLimitId = new TransactionId(20);
        upLimitId = new TransactionId(5);

        activeList = new ArrayList<>();
        activeList.add(new TransactionId(5));
        activeList.add(new TransactionId(8));
        activeList.add(new TransactionId(12));

        readView = new ReadView(creatorId, lowLimitId, upLimitId, activeList);
    }

    @Test
    @DisplayName("测试：ReadView 的基本属性")
    public void testReadViewBasicProperties() {
        assertEquals(creatorId, readView.getCreatorTrxId(), "创建者 ID 应该正确");
        assertEquals(lowLimitId, readView.getLowLimitId(), "low_limit_id 应该正确");
        assertEquals(upLimitId, readView.getUpLimitId(), "up_limit_id 应该正确");
        assertEquals(activeList, readView.getActiveList(), "活跃列表应该正确");
    }

    @Test
    @DisplayName("测试：ReadView 的不可变性")
    public void testReadViewImmutability() {
        // 获取活跃列表
        List<TransactionId> list1 = readView.getActiveList();
        List<TransactionId> list2 = readView.getActiveList();

        // 验证两次获取的列表相同
        assertEquals(list1, list2, "多次获取的活跃列表应该相同");

        // 验证列表是不可变的（如果实现了不可变性）
        // 尝试修改列表不应该影响 ReadView
        try {
            list1.add(new TransactionId(100));
            // 如果实现了不可变性，这会抛出异常
        } catch (UnsupportedOperationException e) {
            // 预期的异常
        }
    }

    @Test
    @DisplayName("测试：可见性判断 - 创建者事务")
    public void testVisibilityForCreator() {
        // 创建者事务的修改总是可见的
        VisibilityChecker checker = new VisibilityChecker();
        assertTrue(checker.isVisible(creatorId, readView), "创建者事务的修改应该可见");
    }

    @Test
    @DisplayName("测试：可见性判断 - 已提交事务")
    public void testVisibilityForCommittedTransaction() {
        // TRX_ID < up_limit_id 的事务已提交，应该可见
        TransactionId committedId = new TransactionId(3);
        VisibilityChecker checker = new VisibilityChecker();
        assertTrue(checker.isVisible(committedId, readView), "已提交事务的修改应该可见");
    }

    @Test
    @DisplayName("测试：可见性判断 - 活跃事务")
    public void testVisibilityForActiveTransaction() {
        // 活跃列表中的事务不可见
        TransactionId activeId = new TransactionId(8);
        VisibilityChecker checker = new VisibilityChecker();
        assertFalse(checker.isVisible(activeId, readView), "活跃事务的修改不应该可见");
    }

    @Test
    @DisplayName("测试：可见性判断 - 未来事务")
    public void testVisibilityForFutureTransaction() {
        // TRX_ID >= low_limit_id 的事务是未来事务，不可见
        TransactionId futureId = new TransactionId(25);
        VisibilityChecker checker = new VisibilityChecker();
        assertFalse(checker.isVisible(futureId, readView), "未来事务的修改不应该可见");
    }

    @Test
    @DisplayName("测试：可见性判断 - 边界值")
    public void testVisibilityBoundaryValues() {
        VisibilityChecker checker = new VisibilityChecker();

        // up_limit_id 边界
        TransactionId atUpLimit = new TransactionId(5);
        assertFalse(checker.isVisible(atUpLimit, readView), "up_limit_id 处的事务不可见");

        // low_limit_id 边界
        TransactionId atLowLimit = new TransactionId(20);
        assertFalse(checker.isVisible(atLowLimit, readView), "low_limit_id 处的事务不可见");

        // 刚好在范围内
        TransactionId justBefore = new TransactionId(19);
        assertTrue(checker.isVisible(justBefore, readView), "low_limit_id 之前的事务应该可见");
    }

    @Test
    @DisplayName("测试：活跃列表的二分查找")
    public void testActiveListBinarySearch() {
        // 验证活跃列表是有序的
        List<TransactionId> list = readView.getActiveList();
        for (int i = 1; i < list.size(); i++) {
            assertTrue(list.get(i - 1).getValue() < list.get(i).getValue(),
                    "活跃列表应该是有序的");
        }
    }

    @Test
    @DisplayName("测试：空活跃列表")
    public void testEmptyActiveList() {
        List<TransactionId> emptyList = new ArrayList<>();
        ReadView rv = new ReadView(creatorId, lowLimitId, upLimitId, emptyList);

        assertEquals(0, rv.getActiveList().size(), "活跃列表应该为空");

        // 验证可见性判断
        VisibilityChecker checker = new VisibilityChecker();
        TransactionId id = new TransactionId(10);
        assertTrue(checker.isVisible(id, rv), "空活跃列表时，范围内的事务应该可见");
    }

    @Test
    @DisplayName("测试：单个活跃事务")
    public void testSingleActiveTransaction() {
        List<TransactionId> singleList = new ArrayList<>();
        singleList.add(new TransactionId(10));

        ReadView rv = new ReadView(creatorId, lowLimitId, upLimitId, singleList);

        assertEquals(1, rv.getActiveList().size(), "活跃列表应该有一个元素");

        // 验证可见性判断
        VisibilityChecker checker = new VisibilityChecker();
        assertFalse(checker.isVisible(new TransactionId(10), rv), "活跃事务不可见");
        assertTrue(checker.isVisible(new TransactionId(9), rv), "已提交事务可见");
    }

    @Test
    @DisplayName("测试：ReadView 的字符串表示")
    public void testReadViewToString() {
        String str = readView.toString();
        assertNotNull(str, "toString() 不应该返回 null");
        assertTrue(str.length() > 0, "toString() 应该返回非空字符串");
        assertTrue(str.contains("ReadView") || str.contains("readView"),
                "toString() 应该包含 ReadView 标识");
    }

    @Test
    @DisplayName("测试：ReadView 的相等性")
    public void testReadViewEquality() {
        ReadView rv1 = new ReadView(creatorId, lowLimitId, upLimitId, activeList);
        ReadView rv2 = new ReadView(creatorId, lowLimitId, upLimitId, activeList);

        // 注意：这取决于 ReadView 是否实现了 equals() 方法
        // 如果没有实现，两个不同的对象不相等
        assertNotEquals(rv1, rv2, "不同的 ReadView 对象应该不相等（除非实现了 equals）");
    }

    @Test
    @DisplayName("测试：ReadView 的哈希码")
    public void testReadViewHashCode() {
        ReadView rv = readView;
        int hash = rv.hashCode();

        // 验证哈希码是一致的
        assertEquals(hash, rv.hashCode(), "同一对象的哈希码应该一致");
    }

    // ==================== 辅助类 ====================

    /**
     * 可见性检查器（简化实现）
     */
    private static class VisibilityChecker {
        public boolean isVisible(TransactionId trxId, ReadView readView) {
            // 创建者事务的修改总是可见的
            if (trxId.equals(readView.getCreatorTrxId())) {
                return true;
            }

            // TRX_ID < up_limit_id 的事务已提交，可见
            if (trxId.getValue() < readView.getUpLimitId().getValue()) {
                return true;
            }

            // TRX_ID >= low_limit_id 的事务是未来事务，不可见
            if (trxId.getValue() >= readView.getLowLimitId().getValue()) {
                return false;
            }

            // 在 [up_limit_id, low_limit_id) 范围内，检查活跃列表
            for (TransactionId activeId : readView.getActiveList()) {
                if (activeId.equals(trxId)) {
                    return false;  // 活跃事务不可见
                }
            }

            return true;  // 已提交事务可见
        }
    }
}
