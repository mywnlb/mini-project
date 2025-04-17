package cn.zhangyis.simplebookdb.file;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * @Description 分页读取和每个段一样
 * @Date 2024/6/21 16:24
 * @Created by libo
 */
public class Page {
    private ByteBuffer buffer;
    public static Charset CHARSET = StandardCharsets.UTF_8;

    public Page(int blocksize) {
        buffer = ByteBuffer.allocateDirect(blocksize);
    }

    public Page(byte[] data) {
        buffer = ByteBuffer.wrap(data);
    }

    /**
     * 当前字符在编码下的长度
     * @param strlen
     * @return
     */
    public static int maxLength(int strlen) {
        float bytesPerChar = CHARSET.newEncoder().maxBytesPerChar();
        return Integer.BYTES + (strlen * (int)bytesPerChar);
    }

    public int getInt(int offset) {
        return buffer.getInt(offset);
    }

    public void setInt(int offset, int value) {
        buffer.putInt(offset, value);
    }

    public byte[] getBytes(int offset) {
        buffer.position(offset);
        int length = buffer.getInt();
        byte[] data = new byte[length];
        buffer.get(data);
        return data;
    }

    public void setBytes(int offset, byte[] data) {
        buffer.position(offset);
        buffer.putInt(data.length);
        buffer.put(data);
    }

    public String getString(int offset) {
        byte[] data = getBytes(offset);
        return new String(data, CHARSET);
    }

    public void setString(int offset, String value) {
        byte[] data = value.getBytes(CHARSET);
        setBytes(offset, data);
    }

    public ByteBuffer contents() {
        buffer.position(0);
        return buffer;
    }


}
