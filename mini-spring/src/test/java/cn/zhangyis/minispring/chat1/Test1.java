package cn.zhangyis.minispring.chat1;

import org.junit.jupiter.api.Test;

/**
 * @Description TODO
 * @Date 2024/6/2 14:49
 * @Created by libo
 */
public class Test1 {

    @Test
    public void test() {
        ClassPathXmlApplicationContext applicationContext = new ClassPathXmlApplicationContext("chat1/bean.xml");
        Aservice aservice = (Aservice) applicationContext.getBean("aservice");
        aservice.say();
    }
}
