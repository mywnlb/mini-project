package cn.zhangyis.server.lab1;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * @Description
 * @Date 2024/5/11 11:07
 * @Created by libo
 */
public class HttpServer {
    public static void main(String[] args) {
        System.out.println("启动监听");
        new HttpServer().await();
    }

    private void await() {
        //监听8080端口
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(8080);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        //等待请求
        while (true) {
            try {
                new Thread(new HttpServerProcessor(serverSocket.accept())).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }
}
