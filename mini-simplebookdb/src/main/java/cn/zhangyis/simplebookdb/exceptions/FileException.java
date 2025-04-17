package cn.zhangyis.simplebookdb.exceptions;

/**
 * @Description 文件异常
 * @Date 2024/7/8 11:29
 * @Created by libo
 */
public class FileException extends RuntimeException{
    private String msg;

    public FileException(String message) {
        super(message);
        this.msg = msg;
    }
}
