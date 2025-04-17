package cn.zhangyis.mininyadb.backend.dm.manager;

/**
 * @Description TODO
 * @Date 2024/6/26 11:26
 * @Created by libo
 */
/*
   Dataitem 为DataEngine为上层模块提供的数据抽象
   上层模块需要根据地址， 向DataEngine请求对应的Dataitem
   然后通过Data方法， 取得DataItem实际内容

   下面是一些关于DataItem的协议.

 	数据共享:
		利用d.Data()得到的数据, 是内存共享的.

  	数据项修改协议:
   		上层模块在对数据项进行任何修改之前, 都必须调用d.Before(), 如果想撤销修改, 则再调用
		d.UnBefore(). 修改完成后, 还必须调用d.After(xid).
		DM会保证对Dataitem的修改是原子性的.

	数据项释放协议:
		上层模块不用数据项时, 必须调用d.Release()来将其释放
*/
/*
   对DataItem的实际实现， 其结构如下：
   [Valid Flag]        [Data Size]          [Data]
   1 byte bool		   2 bytes uint16       *

   Data Size标示了该dataitem中实际存储的data长度
   Valid Flag现在只有两个值， 0表示该dataitem合法， 1表示非法
   xid和flag的存在原因请参考logs.go中描述的恢复机制
*/
public interface DataItem {
    void before();
    void unBefore();
    void after(int uuid);
    void release();
}
