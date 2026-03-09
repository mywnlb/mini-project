# MySQL · 源码分析 · 8.0 原子DDL的实现过程续

原文链接: [http://mysql.taobao.org/monthly/2018/07/02/](http://mysql.taobao.org/monthly/2018/07/02/)

之前的一篇月报
MySQL · 源码分析 · 原子DDL的实现过程
对MySQL8.0的原子DDL的背景以及使用的一些关键数据结构进行了阐述，同时也以CREATE TABLE为例介绍了Server层和Storage层统一系统表后如何创建一张新表进行了介绍。接下来本篇文章，我们将以DROP TABLE为例来继续看一下MySQL8.0对于DDL执行成功和执行失败时，如何实现DDL事务的提交和回滚。

为了实现原子DDL的提交和回滚，InnoDB存储引擎引入了一个表DDL_LOG。该表用来存储DDL执行期间InnoDB存储引擎需要对物理文件以及相关系统表操作的记录。当DDL事务进行提交或者回滚之前，InnoDB存储引擎实际上不对物理文件或者相关系统表进行修改，只是记录相关的操作日志。而当DDL进行提交或者回滚操作的时候，InnoDB会对DDL_LOG表里的日志进行重放或者删除。在后面的章节我们会看到相关的函数调用过程。

DDL_LOG表作为一张日志记录表，它具有以下特点:

- 不允许外部用户查询和修改,包括对该表进行DDL以及DML；
- 对于DDL_LOG中的每一条记录都包含有trx_id（事务id），当DDL提交或者回滚完成的时候，post_ddl hook将会自动清除该表中的记录
- 为了防止SERVER crash的时候DDL还能支持原子性，这个表的存储比较特殊，需要进行同步刷新。也就是只要写入数据就会进行持久化，不受innodb_flush_log_at_trx_commit的控制。

InnoDB引擎对于DDL操作的记录是通过Log_DDL这么一个类实现的。这个类会将存储引擎内部执行的操作记录到DDL_LOG这个表里。下面我们看看LOG_DDL这张表中会记录存储引擎的哪些操作：

下面我们看一下InnoDB执行原子DROP TABLE的简单流程图：

![image](http://ata2-img.cn-hangzhou.img-pub.aliyun-inc.com/a599cf1ef61639310dbf336d0192c7f8.png)
从图中我们可以看到，DROP TABLE的时候会调用Handler::ha_delete_table。对于不支持原子DDL的存储引擎来说，Handler::ha_delete_table MySQL8.0的执行方式和之前版本没有太大的区别，都是直接删除物理文件，然后清理系统表。但是对于InnoDB存储引擎而言，Handler::ha_delete_table并不会进行实际物理文件的修改，而只是记录相关的操作到DDL_LOG table中。下面我们看一下innobase_basic_ddl::delete_impl函数的源码。

当DDL事务提交或者回滚的时候，会调用post_ddl进行日志回放。简单看一下post_ddl的源码：

原子DDL是MySQL8.0引入的非常重要的一个特性，相比之前的版本已经有了长足的变化。可以期待以后事务DDL的出现。通过两篇文章，从源码层面，以CREATE/DROP TABLE为例，简要的分析了InnoDB存储引擎支持原子DDL的实现原理。希望对关注原子DDL，并对其实现原理感兴趣的用户有所帮助。

