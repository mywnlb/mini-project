package cn.zhangyis.minidb.storage.redo.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生命周期框架单元测试
 */
@DisplayName("生命周期框架测试")
class LifecycleTest {

    private DatabaseLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new DatabaseLifecycleManager("test-manager");
    }

    // ==================== LifecycleState 测试 ====================

    @Test
    @DisplayName("状态转换验证")
    void testStateTransitions() {
        // 正常转换链
        assertTrue(LifecycleState.NEW.canTransitionTo(LifecycleState.INITIALIZING));
        assertTrue(LifecycleState.INITIALIZING.canTransitionTo(LifecycleState.INITIALIZED));
        assertTrue(LifecycleState.INITIALIZED.canTransitionTo(LifecycleState.STARTING));
        assertTrue(LifecycleState.STARTING.canTransitionTo(LifecycleState.RUNNING));
        assertTrue(LifecycleState.RUNNING.canTransitionTo(LifecycleState.STOPPING));
        assertTrue(LifecycleState.STOPPING.canTransitionTo(LifecycleState.STOPPED));
        assertTrue(LifecycleState.STOPPED.canTransitionTo(LifecycleState.DESTROYING));
        assertTrue(LifecycleState.DESTROYING.canTransitionTo(LifecycleState.DESTROYED));

        // 任何状态都可以转到 FAILED
        for (LifecycleState state : LifecycleState.values()) {
            assertTrue(state.canTransitionTo(LifecycleState.FAILED));
        }

        // 无效转换
        assertFalse(LifecycleState.NEW.canTransitionTo(LifecycleState.RUNNING));
        assertFalse(LifecycleState.RUNNING.canTransitionTo(LifecycleState.NEW));
        assertFalse(LifecycleState.DESTROYED.canTransitionTo(LifecycleState.NEW));
    }

    @Test
    @DisplayName("状态属性测试")
    void testStateProperties() {
        assertTrue(LifecycleState.RUNNING.isActive());
        assertFalse(LifecycleState.STOPPED.isActive());

        assertTrue(LifecycleState.STOPPED.isTerminated());
        assertTrue(LifecycleState.DESTROYED.isTerminated());
        assertTrue(LifecycleState.FAILED.isTerminated());
        assertFalse(LifecycleState.RUNNING.isTerminated());

        assertTrue(LifecycleState.INITIALIZING.isTransitioning());
        assertTrue(LifecycleState.STARTING.isTransitioning());
        assertFalse(LifecycleState.RUNNING.isTransitioning());
    }

    // ==================== BackgroundService 测试 ====================

    @Test
    @DisplayName("后台服务生命周期")
    @Timeout(5)
    void testBackgroundServiceLifecycle() throws Exception {
        AtomicInteger workCount = new AtomicInteger(0);

        BackgroundService service = new BackgroundService("test-service", 100) {
            @Override
            protected void doWork() throws Exception {
                workCount.incrementAndGet();
                Thread.sleep(10);
            }
        };

        assertEquals(LifecycleState.NEW, service.getState());
        assertEquals("test-service", service.getName());
        assertEquals(100, service.getOrder());

        // 初始化
        service.initialize();
        assertEquals(LifecycleState.INITIALIZED, service.getState());

        // 启动
        service.start();
        assertEquals(LifecycleState.RUNNING, service.getState());
        assertTrue(service.isRunning());

        // 等待一些工作完成
        Thread.sleep(100);
        assertTrue(workCount.get() > 0);

        // 停止
        service.stop();
        assertEquals(LifecycleState.STOPPED, service.getState());
        assertFalse(service.isRunning());

        // 销毁
        service.destroy();
        assertEquals(LifecycleState.DESTROYED, service.getState());
    }

    @Test
    @DisplayName("后台服务错误处理")
    @Timeout(5)
    void testBackgroundServiceErrorHandling() throws Exception {
        AtomicInteger errorCount = new AtomicInteger(0);

        BackgroundService service = new BackgroundService("error-service", 100) {
            private int callCount = 0;

            @Override
            protected void doWork() throws Exception {
                callCount++;
                if (callCount <= 3) {
                    throw new RuntimeException("Test error " + callCount);
                }
                Thread.sleep(10);
            }

            @Override
            protected void onError(Exception e) {
                errorCount.incrementAndGet();
                // 不调用 super，避免 100ms 延迟
            }
        };

        service.initialize();
        service.start();

        // 等待错误发生
        Thread.sleep(100);
        assertTrue(errorCount.get() >= 3);

        service.stop();
        service.destroy();
    }

    // ==================== DatabaseLifecycleManager 测试 ====================

    @Test
    @DisplayName("组件注册和排序")
    void testComponentRegistration() {
        TestComponent comp1 = new TestComponent("comp1", 200);
        TestComponent comp2 = new TestComponent("comp2", 100);
        TestComponent comp3 = new TestComponent("comp3", 300);

        manager.register(comp1);
        manager.register(comp2);
        manager.register(comp3);

        List<Lifecycle> components = manager.getComponents();
        assertEquals(3, components.size());

        // 应该按 order 排序
        assertEquals("comp2", components.get(0).getName()); // order=100
        assertEquals("comp1", components.get(1).getName()); // order=200
        assertEquals("comp3", components.get(2).getName()); // order=300
    }

    @Test
    @DisplayName("按顺序启动组件")
    @Timeout(5)
    void testStartOrder() throws Exception {
        List<String> startOrder = new ArrayList<>();

        TestComponent comp1 = new TestComponent("comp1", 200) {
            @Override
            public void start() throws Exception {
                super.start();
                startOrder.add(getName());
            }
        };
        TestComponent comp2 = new TestComponent("comp2", 100) {
            @Override
            public void start() throws Exception {
                super.start();
                startOrder.add(getName());
            }
        };
        TestComponent comp3 = new TestComponent("comp3", 300) {
            @Override
            public void start() throws Exception {
                super.start();
                startOrder.add(getName());
            }
        };

        manager.register(comp1);
        manager.register(comp2);
        manager.register(comp3);

        manager.start();

        // 验证启动顺序
        assertEquals(List.of("comp2", "comp1", "comp3"), startOrder);
        assertEquals(LifecycleState.RUNNING, manager.getState());

        manager.stop();
    }

    @Test
    @DisplayName("按逆序停止组件")
    @Timeout(5)
    void testStopOrder() throws Exception {
        List<String> stopOrder = new ArrayList<>();

        TestComponent comp1 = new TestComponent("comp1", 200) {
            @Override
            public void stop() throws Exception {
                stopOrder.add(getName());
                super.stop();
            }
        };
        TestComponent comp2 = new TestComponent("comp2", 100) {
            @Override
            public void stop() throws Exception {
                stopOrder.add(getName());
                super.stop();
            }
        };
        TestComponent comp3 = new TestComponent("comp3", 300) {
            @Override
            public void stop() throws Exception {
                stopOrder.add(getName());
                super.stop();
            }
        };

        manager.register(comp1);
        manager.register(comp2);
        manager.register(comp3);

        manager.start();
        manager.stop();

        // 验证停止顺序 (与启动顺序相反)
        assertEquals(List.of("comp3", "comp1", "comp2"), stopOrder);
        assertEquals(LifecycleState.STOPPED, manager.getState());
    }

    @Test
    @DisplayName("生命周期监听器")
    @Timeout(5)
    void testLifecycleListener() throws Exception {
        List<String> events = new ArrayList<>();

        manager.addListener(new LifecycleListener() {
            @Override
            public void onInitialized(LifecycleEvent event) {
                events.add("initialized:" + event.getSourceName());
            }

            @Override
            public void onStarted(LifecycleEvent event) {
                events.add("started:" + event.getSourceName());
            }

            @Override
            public void onStopped(LifecycleEvent event) {
                events.add("stopped:" + event.getSourceName());
            }
        });

        TestComponent comp = new TestComponent("test", 100);
        manager.register(comp);

        manager.start();
        manager.stop();

        // 验证事件
        assertTrue(events.contains("initialized:test"));
        assertTrue(events.contains("started:test"));
        assertTrue(events.contains("stopped:test"));
    }

    @Test
    @DisplayName("获取指定类型组件")
    void testGetComponentByType() {
        TestComponent comp1 = new TestComponent("comp1", 100);
        SpecialComponent comp2 = new SpecialComponent("special", 200);

        manager.register(comp1);
        manager.register(comp2);

        SpecialComponent found = manager.getComponent(SpecialComponent.class);
        assertNotNull(found);
        assertEquals("special", found.getName());

        List<TestComponent> all = manager.getComponents(TestComponent.class);
        assertEquals(2, all.size()); // SpecialComponent 也是 TestComponent
    }

    @Test
    @DisplayName("启动失败时回滚")
    void testStartFailureRollback() {
        List<String> stoppedComponents = new ArrayList<>();

        TestComponent comp1 = new TestComponent("comp1", 100) {
            @Override
            public void stop() throws Exception {
                stoppedComponents.add(getName());
                super.stop();
            }
        };
        TestComponent comp2 = new TestComponent("comp2", 200) {
            @Override
            public void start() throws Exception {
                throw new RuntimeException("Simulated start failure");
            }
        };

        manager.register(comp1);
        manager.register(comp2);

        // 启动应该失败
        assertThrows(DatabaseLifecycleManager.LifecycleException.class,
            () -> manager.start());

        // comp1 应该被停止 (回滚)
        assertTrue(stoppedComponents.contains("comp1"));
        assertEquals(LifecycleState.FAILED, manager.getState());
    }

    // ==================== 辅助类 ====================

    /**
     * 测试用组件
     */
    static class TestComponent implements Lifecycle {
        private final String name;
        private final int order;
        private LifecycleState state = LifecycleState.NEW;

        TestComponent(String name, int order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public void initialize() throws Exception {
            state = LifecycleState.INITIALIZED;
        }

        @Override
        public void start() throws Exception {
            state = LifecycleState.RUNNING;
        }

        @Override
        public void stop() throws Exception {
            state = LifecycleState.STOPPED;
        }

        @Override
        public void destroy() throws Exception {
            state = LifecycleState.DESTROYED;
        }

        @Override
        public LifecycleState getState() {
            return state;
        }
    }

    /**
     * 特殊组件 (用于类型查找测试)
     */
    static class SpecialComponent extends TestComponent {
        SpecialComponent(String name, int order) {
            super(name, order);
        }
    }
}
