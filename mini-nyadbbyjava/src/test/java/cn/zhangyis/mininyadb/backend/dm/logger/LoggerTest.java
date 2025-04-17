package cn.zhangyis.mininyadb.backend.dm.logger;


import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class LoggerTest {
    @Test
    public void testLogger() {

        Logger lg = new HeapLoggerImpl("/tmp/logger_test");
        lg.appendLog("aaa");
        lg.appendLog("bbb");
        lg.appendLog("ccc");
        lg.appendLog("ddd");
        lg.appendLog("eee");

        String log = lg.next();
        assert log != null;
        assert StringUtils.equals("aaa", log);

        log = lg.next();
        assert log != null;
        assert StringUtils.equals("bbb", log);

        log = lg.next();
        assert log != null;
        assert StringUtils.equals("ccc", log);

        log = lg.next();
        assert log != null;
        assert StringUtils.equals("ddd", log);

        log = lg.next();
        assert log != null;
        assert StringUtils.equals("eee", log);

        log = lg.next();
        assert log == null;

        lg.close();
        assert new File("/tmp/logger_test.log").delete();
    }

    @Test
    public void test(){
        String aa = "aaa";
        System.out.println(StringUtils.equals("aaa",aa));
    }
}
