package cn.zhangyis.server.lab1;

import java.io.InputStream;

/**
 * @Description http请求
 * @Date 2024/5/11 10:49
 * @Created by libo
 */
public class Request {
    private InputStream input;
    private String uri;

    public Request(InputStream input) {
        this.input = input;
    }

    public void parse() {
        // Read a set of characters from the socket
        StringBuffer request = new StringBuffer(2048);
        int i;
        byte[] buffer = new byte[2048];
        try {
            i = input.read(buffer);
        } catch (Exception e) {
            e.printStackTrace();
            i = -1;
        }
        for (int j = 0; j < i; j++) {
            request.append((char) buffer[j]);
        }
        System.out.print(request.toString());
        uri = parseUri(request.toString());
    }

    private String parseUri(String string) {
        int index1, index2;
        index1 = string.indexOf(' ');
        if (index1 != -1) {
            index2 = string.indexOf(' ', index1 + 1);
            if (index2 > index1) {
                return string.substring(index1 + 1, index2);
            }
        }
        return null;
    }

    public String getUri() {
        return uri;
    }


}
