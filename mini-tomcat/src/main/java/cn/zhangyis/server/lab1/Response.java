package cn.zhangyis.server.lab1;

import java.io.OutputStream;

/**
 * @Description TODO
 * @Date 2024/5/11 11:08
 * @Created by libo
 */
public class Response {
    private static final int BUFFER_SIZE = 1024;
    Request request;
    OutputStream output;

    public Response(OutputStream output) {
        this.output = output;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public void sendStaticResource() {
        byte[] bytes = new byte[BUFFER_SIZE];
        try {
            String uri = request.getUri();
            if (uri == null) {
                return;
            }
            output.write("HTTP/1.1 200 OK\r\n".getBytes());
            output.write("Content-Type: text/html\r\n".getBytes());
            output.write("\r\n".getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
