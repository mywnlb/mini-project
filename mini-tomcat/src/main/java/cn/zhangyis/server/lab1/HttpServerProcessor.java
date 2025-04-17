package cn.zhangyis.server.lab1;

import java.io.InputStream;
import java.net.Socket;

/**
 * @Description TODO
 * @Date 2024/5/11 11:10
 * @Created by libo
 */
public class HttpServerProcessor implements Runnable {
    private Socket accept;

    public HttpServerProcessor(Socket accept) {
        this.accept = accept;
    }

    @Override
    public void run() {
        try {
            InputStream inputStream = accept.getInputStream();
            Request request = new Request(inputStream);
            request.parse();

            Response response = new Response(accept.getOutputStream());
            response.setRequest(request);
            response.sendStaticResource();
            accept.close();
        }catch (Exception e){
            e.printStackTrace();
        }

    }
}
