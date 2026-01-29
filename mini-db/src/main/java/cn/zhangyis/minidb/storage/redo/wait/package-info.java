/**
 * Redo Log 等待/唤醒机制
 *
 * <p>本包提供分片条件变量实现，用于精准唤醒等待 redo log 持久化的线程，
 * 避免传统单一条件变量的"惊群效应"。</p>
 *
 * <h2>核心组件</h2>
 * <ul>
 *   <li>{@link cn.zhangyis.minidb.storage.redo.wait.WaitSlots} - 分片等待槽位</li>
 * </ul>
 *
 * <h2>设计原理</h2>
 * <p>将等待线程按 SN 取模分散到多个槽位。当进度推进时，
 * 只唤醒受影响槽位的线程，其他槽位继续等待。</p>
 *
 * <pre>
 * 传统方式:
 *   signalAll() → 唤醒所有线程 → 大部分线程条件不满足 → 重新等待
 *
 * 分片方式:
 *   wakeupRange(from, to) → 只唤醒 slot[from..to] → 减少无效唤醒
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
package cn.zhangyis.minidb.storage.redo.wait;
