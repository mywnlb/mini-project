package cn.zhangyis.minidb.common.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ANSIConstants;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;

/**
 * 自定义日志级别颜色：ERROR=红色, WARN=黄色, INFO=绿色, DEBUG=默认。
 */
public class LevelColorConverter extends ForegroundCompositeConverterBase<ILoggingEvent> {

    @Override
    protected String getForegroundColorCode(ILoggingEvent event) {
        return switch (event.getLevel().toInt()) {
            case Level.ERROR_INT -> ANSIConstants.BOLD + ANSIConstants.RED_FG;
            case Level.WARN_INT -> ANSIConstants.YELLOW_FG;
            case Level.INFO_INT -> ANSIConstants.GREEN_FG;
            default -> ANSIConstants.DEFAULT_FG;
        };
    }
}
