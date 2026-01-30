package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.page.PageId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 页面锁持有记录
 *
 * <p>记录当前持有的页面锁，用于蟹行协议和锁释放。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LatchHolder {

    /** 持有的锁列表 */
    private final List<HeldLatch> heldLatches;

    /** 默认锁超时时间（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    /**
     * 构造锁持有记录
     */
    public LatchHolder() {
        this.heldLatches = new ArrayList<>();
    }

    /**
     * 获取页面锁
     *
     * @param frame 页面帧
     * @param mode  锁模式
     * @return 如果成功获取返回 true
     */
    public boolean acquire(BufferFrame frame, LatchMode mode) {
        return acquire(frame, mode, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 获取页面锁（带超时）
     *
     * @param frame     页面帧
     * @param mode      锁模式
     * @param timeoutMs 超时时间（毫秒）
     * @return 如果成功获取返回 true
     */
    public boolean acquire(BufferFrame frame, LatchMode mode, long timeoutMs) {
        if (mode == LatchMode.NONE) {
            return true;
        }

        try {
            if (mode == LatchMode.SHARED) {
                if (timeoutMs > 0) {
                    boolean acquired = frame.tryReadLock(timeoutMs, TimeUnit.MILLISECONDS);
                    if (acquired) {
                        heldLatches.add(new HeldLatch(frame, mode));
                    }
                    return acquired;
                } else {
                    frame.readLock();
                    heldLatches.add(new HeldLatch(frame, mode));
                    return true;
                }
            } else {
                if (timeoutMs > 0) {
                    boolean acquired = frame.tryWriteLock(timeoutMs, TimeUnit.MILLISECONDS);
                    if (acquired) {
                        heldLatches.add(new HeldLatch(frame, mode));
                    }
                    return acquired;
                } else {
                    frame.writeLock();
                    heldLatches.add(new HeldLatch(frame, mode));
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放指定页面的锁
     *
     * @param frame 页面帧
     */
    public void release(BufferFrame frame) {
        for (int i = heldLatches.size() - 1; i >= 0; i--) {
            HeldLatch held = heldLatches.get(i);
            if (held.frame == frame) {
                releaseLatch(held);
                heldLatches.remove(i);
                return;
            }
        }
    }

    /**
     * 释放所有锁
     */
    public void releaseAll() {
        // 按获取的逆序释放
        for (int i = heldLatches.size() - 1; i >= 0; i--) {
            releaseLatch(heldLatches.get(i));
        }
        heldLatches.clear();
    }

    /**
     * 释放除最后一个之外的所有锁（蟹行协议）
     *
     * <p>在确认子节点安全后，释放父节点的锁。</p>
     */
    public void releaseAllButLast() {
        if (heldLatches.size() <= 1) {
            return;
        }

        // 保留最后一个，释放其他
        HeldLatch last = heldLatches.get(heldLatches.size() - 1);
        for (int i = heldLatches.size() - 2; i >= 0; i--) {
            releaseLatch(heldLatches.get(i));
        }
        heldLatches.clear();
        heldLatches.add(last);
    }

    /**
     * 释放除指定页面之外的所有锁
     *
     * @param keepFrame 要保留的页面帧
     */
    public void releaseAllExcept(BufferFrame keepFrame) {
        List<HeldLatch> toKeep = new ArrayList<>();

        for (int i = heldLatches.size() - 1; i >= 0; i--) {
            HeldLatch held = heldLatches.get(i);
            if (held.frame == keepFrame) {
                toKeep.add(held);
            } else {
                releaseLatch(held);
            }
        }

        heldLatches.clear();
        heldLatches.addAll(toKeep);
    }

    /**
     * 升级锁（S -> X）
     *
     * @param frame 页面帧
     * @return 如果成功升级返回 true
     */
    public boolean upgrade(BufferFrame frame) {
        for (int i = 0; i < heldLatches.size(); i++) {
            HeldLatch held = heldLatches.get(i);
            if (held.frame == frame && held.mode == LatchMode.SHARED) {
                // 先释放读锁，再获取写锁
                frame.readUnlock();
                frame.writeLock();
                heldLatches.set(i, new HeldLatch(frame, LatchMode.EXCLUSIVE));
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否持有指定页面的锁
     *
     * @param frame 页面帧
     * @return 如果持有锁返回 true
     */
    public boolean isHolding(BufferFrame frame) {
        for (HeldLatch held : heldLatches) {
            if (held.frame == frame) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否持有指定页面的排他锁
     *
     * @param frame 页面帧
     * @return 如果持有排他锁返回 true
     */
    public boolean isHoldingExclusive(BufferFrame frame) {
        for (HeldLatch held : heldLatches) {
            if (held.frame == frame && held.mode == LatchMode.EXCLUSIVE) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取持有的锁数量
     *
     * @return 锁数量
     */
    public int getLatchCount() {
        return heldLatches.size();
    }

    /**
     * 释放单个锁
     */
    private void releaseLatch(HeldLatch held) {
        if (held.mode == LatchMode.SHARED) {
            held.frame.readUnlock();
        } else if (held.mode == LatchMode.EXCLUSIVE) {
            held.frame.writeUnlock();
        }
    }

    /**
     * 持有的锁记录
     */
    private static class HeldLatch {
        final BufferFrame frame;
        final LatchMode mode;

        HeldLatch(BufferFrame frame, LatchMode mode) {
            this.frame = frame;
            this.mode = mode;
        }
    }
}
