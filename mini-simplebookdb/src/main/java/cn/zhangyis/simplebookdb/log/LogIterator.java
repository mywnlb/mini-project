package cn.zhangyis.simplebookdb.log;

import cn.zhangyis.simplebookdb.file.BlockId;
import cn.zhangyis.simplebookdb.file.FileMgr;
import cn.zhangyis.simplebookdb.file.Page;

import java.util.Iterator;

/**
 * @Description TODO
 * @Date 2024/7/23 15:37
 * @Created by libo
 */
public class LogIterator implements Iterator<byte[]> {
    private FileMgr fileMgr;
    private BlockId blockId;
    private Page page;
    private int currentPos;
    private int boundary;
    public LogIterator(FileMgr fileMgr, BlockId currnetBlk) {
        this.fileMgr = fileMgr;
        this.blockId = currnetBlk;

        //读出最后一页
        page = new Page(new byte[fileMgr.getBlockSize()]);
        moveToBlock(blockId);
    }

    private void moveToBlock(BlockId blockId) {
        fileMgr.read(blockId,page);
        boundary = page.getInt(0);
        currentPos = boundary;
    }


    @Override
    public boolean hasNext() {
        return blockId.getBlkNum() > 0 && currentPos < fileMgr.getBlockSize();
    }

    @Override
    public byte[] next() {
        if(currentPos == fileMgr.getBlockSize()){
            blockId = new BlockId(blockId.getFileName(), blockId.getBlkNum() - 1);
            moveToBlock(blockId);
        }
        byte[] bytes = page.getBytes(currentPos);
        currentPos += bytes.length;
        return bytes;
    }
}
