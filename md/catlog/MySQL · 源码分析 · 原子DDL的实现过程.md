# MySQL · 源码分析 · 原子DDL的实现过程

原文链接: [http://mysql.taobao.org/monthly/2018/03/02/](http://mysql.taobao.org/monthly/2018/03/02/)

众所周知，MySQL8.0之前的版本DDL是非原子的。也就是说对于复合的DDL，比如DROP TABLE t1, t2;执行过程中如果遇到server crash，有可能出现表t1被DROP掉了，但是t2没有被DROP掉的情况。即便是一条DDL，比如CREATE TABLE t1(a int);也可能在server crash的情况下导致建表不完整，有可能在建表失败的情况下遗留.frm或者.ibd文件。

上面情况出现的主要原因就是MySQL不支持原子的DDL。从图1可以看出，MySQL8.0以前，metadata在Server layer是存储在MyISAM引擎的系统表里，对于事务型引擎Innodb则自己存储一份metadata。这也导致MySQL存在如下一些弊端：

- metadata由于存储在Server layer以及存储引擎（这里特指Innodb)，两份系统表很容易造成数据的不一致。
- 两份系统表存储的信息有所不同，访问Server layer以及存储引擎需要使用不同API，这种设计导致了不能很好的统一对系统metadata的访问。另外两份API，也同时增加了代码的维护量。
- 由于Server layer的metadata存储在非事务引擎（MyISAM)里，所以在进行crash recovery的时候就不能维持原子性。
- DDL的非原子性使得Replication处理异常情况变得更加复杂。比如DROP TABLE t1, t2; 如果DROP t1成功，但是DROP t2失败，Replication就无法保证主备一致性了。

![image](http://ata2-img.cn-hangzhou.img-pub.aliyun-inc.com/a5739b9d001e4efe49280fe0afa0dcff.png)
图1： MySQL Data Dictionary before MySQL8.0

MySQL8.0为了解决上面的缺陷，引入了事务型DDL。首先我们看一下MySQL8.0 metadata存储的架构变化：

![image](http://ata2-img.cn-hangzhou.img-pub.aliyun-inc.com/3e801b1231e0d058a00abb3cfadda108.png)
图2: MySQL Data Dictionary in MySQL8.0

图2我们可以看到，Server layer(后面简称SL）以及Storage Engine（后面简称SE） 使用同一份data dictionary(后面简称DD）用来存储metadata。SL和SE将各自需要的metadata存入DD中。由于DD使用Innodb作为存储引擎，所以crash recovery的时候，DD可以安全的进行事务回滚。

下面我们介绍一下MySQL8.0为了实现原子DDL，在源码层面引入的一些重要数据结构：

接下来我们以CREATE TABLE为例从源码上简单看一下MYSQL8.0是如何实现原子DDL的。

CREATE TABLE实现的流程图如下：

![image](http://ata2-img.cn-hangzhou.img-pub.aliyun-inc.com/3e376a1451b12f56b9b0750fb6a1db9f.jpg)
这里我们看一下CREATE TABLE过程中新增加的几个比较重要的函数（这里主要看Innodb存储引擎）：

综上篇章简要的描述了MySQL8.0实现原子DDL的背景以及一些重点的数据结构，并对CREATE TABLE过程，以及创建过程中用到的几个重要函数进行了分析。但是原子DDL的实现是一个非常大的工程，本篇月报由于篇幅问题，只是挖了冰山一角。以后的月报会继续对原子DDL的实现进行分析，希望大家持续关注。

