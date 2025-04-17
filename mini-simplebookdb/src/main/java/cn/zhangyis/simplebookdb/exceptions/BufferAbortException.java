package cn.zhangyis.simplebookdb.exceptions;

/**
 * @Description buffer异常
 * @Date 2024/7/8 12:20
 * @Created by libo
 */
public class BufferAbortException extends RuntimeException{
    private String msg;

    public BufferAbortException(String msg) {
        super(msg);
        this.msg = msg;
    }
}
