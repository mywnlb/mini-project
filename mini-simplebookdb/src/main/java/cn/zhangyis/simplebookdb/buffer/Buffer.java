package cn.zhangyis.simplebookdb.buffer;

import cn.zhangyis.simplebookdb.file.BlockId;
import cn.zhangyis.simplebookdb.file.FileMgr;
import cn.zhangyis.simplebookdb.file.Page;
import cn.zhangyis.simplebookdb.log.LogMgr;

/**
 * 一个单独的缓冲区。数据缓冲区封装一个页面
 * 并存储有关其状态的信息、
 * 如相关磁盘块、
 * 缓冲区被钉住的次数、
 * 其内容是否被修改、
 * 如果被修改，则会显示修改事务的 id 和 lsn。
 * @Date 2024/7/8 15:34
 * @Created by libo
 */
public class Buffer {
    private FileMgr fm;
    private LogMgr lm;
    private Page contents;
    private BlockId blockId;
    private int pins = 0;
    private int txnum = -1;
    private int lsn = -1;

    public Buffer(FileMgr fm,LogMgr lm) {
        this.lm = lm;
        this.fm = fm;
        contents = new Page(fm.getBlockSize());
    }

    public Page contents(){
        return contents;
    }

    public BlockId block(){
        return blockId;
    }

    public void setModified(int txnum,int lsn){
        this.txnum = txnum;
        // 为什么
        if(lsn > 0){
            this.lsn = lsn;
        }
    }

    /**
     * 当前页面是否被引用
     * @return
     */
    public boolean isPinned(){
        return pins > 0;
    }

    public int modifyingTx(){
        return txnum;
    }

    /**
     * 将指定块的内容读入
     * 缓冲区的内容。
     * 如果缓冲区是脏的，那么它之前的内容
     * 将首先写入磁盘。
     * @param blockId
     */
    public void assignToBlock(BlockId blockId){
        flush();
        this.blockId = blockId;
        fm.read(blockId,contents);
        pins = 0;
    }

    public void flush() {
        if(txnum > 0){
            lm.flush(lsn);
            fm.write(blockId,contents);
            txnum = -1;
        }
    }

    public void pin(){
        pins++;
    }

    public void unpin(){
        pins--;
    }
}
