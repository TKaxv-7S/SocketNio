package com.tk.socket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

@Slf4j
public class SocketMsgDto implements Serializable {

    private static final long serialVersionUID = 1L;

    //头部，5字节
    private ByteBuf head;

    //身部
    private byte[] body;

    //验证字节，1字节
    private Byte verifyByte0;

    //验证字节，1字节
    private Byte verifyByte1;

    //加密字节，1字节
    private Byte secretByte;

    //全部
    private final CompositeByteBuf full;

    //长度
    private Integer size;

    private final Integer sizeLimit;

    //isFinal
    private Boolean isDone = false;

    private SocketMsgDto next;

    public CompositeByteBuf getFull() {
        return full;
    }

    public Byte getSecretByte() {
        return secretByte;
    }

    public SocketMsgDto getNext() {
        return next;
    }

    public SocketMsgDto(Integer sizeLimit) {
        this.full = ByteBufAllocator.DEFAULT.compositeBuffer();
        this.sizeLimit = sizeLimit;
    }

    public SocketMsgDto(CompositeByteBuf full, Integer sizeLimit) {
        this.full = full;
        this.sizeLimit = sizeLimit;
    }

    public SocketMsgDto(Integer size, CompositeByteBuf full, Integer sizeLimit) {
        this.size = size;
        this.full = full;
        this.sizeLimit = sizeLimit;
    }

    public Boolean isDone() {
        return isDone;
    }

    private synchronized SocketMsgDto buildNext() {
        if (next == null) {
            return next = new SocketMsgDto(sizeLimit);
        }
        return next;
    }

    public byte[] getBody() {
        return body;
    }

    public synchronized Boolean parsingMsg(ByteBuf msg) {
        if (!msg.isReadable()) {
            return true;
        }
        int readableBytes;
        int writeIndex;
        if (full.writerIndex() <= 0) {
            readableBytes = msg.readableBytes();
            if (readableBytes < 5) {
                //数据不足，继续等待
                full.addComponent(true, msg);
                return false;
            }
            head = msg.readRetainedSlice(5);
            size = SocketMessageUtil.checkMsgFirst(head, sizeLimit);
            //5+2+3+1
            if (size < 11) {
                //丢弃并关闭连接
                throw new SocketException("非法报文");
            }
            full.addComponent(true, head);
            full.readerIndex(5);
            writeIndex = full.writerIndex();
        } else {
            if (size == null) {
                //补全数据
                int writerIndex = full.writerIndex();
                readableBytes = msg.readableBytes();
                if (writerIndex < 4) {
                    if (writerIndex + readableBytes < 4) {
                        //数据不足，继续等待
                        full.addComponent(true, msg);
                        //刷新
                        //readCacheMap.put(channelId, socketMsgDto);
                        return false;
                    }
                }
                head = msg.readRetainedSlice(5);
                size = SocketMessageUtil.checkMsgFirst(head, sizeLimit);
                if (size < 11) {
                    //丢弃并关闭连接
                    throw new SocketException("非法报文");
                }
                full.addComponent(true, head);
                full.readerIndex(5);
                //刷新
                //readCacheMap.put(channelId, socketMsgDto);
            }
            writeIndex = full.writerIndex();
        }
        ByteBuf stickMsg = null;
        int leftDataLength = size - writeIndex;
        if (leftDataLength > 0) {
            readableBytes = msg.readableBytes();
            int i = leftDataLength - readableBytes;
            if (i > 0) {
                //继续读取
                full.addComponent(true, msg);
                return true;
            } else if (i < 0) {
                //此处粘包了，手动切割
                full.addComponent(true, msg.slice(msg.readerIndex(), leftDataLength));
                stickMsg = msg.retainedSlice(msg.readerIndex() + leftDataLength, -i);
            } else {
                full.addComponent(true, msg);
            }
        } else if (leftDataLength < 0) {
            //丢弃并关闭连接
            throw new SocketException("报文数据异常");
        }
        checkMsgTail();
        body = new byte[size - 8];
        full.readBytes(body);
        isDone = true;
        if (stickMsg != null) {
            while (full.refCnt() > 0) {
                ReferenceCountUtil.release(full);
            }
            SocketMsgDto next = buildNext();
            return next.parsingMsg(stickMsg);
        }
        return true;
    }

    private void checkMsgTail() {
        //报文尾共3字节，2字节为 msgSize首字节 + 数据中间字节，1字节加密类型
        verifyByte0 = full.getByte(size - 3);
        verifyByte1 = full.getByte(size - 2);
        secretByte = full.getByte(size - 1);
        if (verifyByte0 == full.getByte(1) && verifyByte1 == full.getByte(size / 2)) {
            return;
        }
        log.error("{} != {} ?", verifyByte0, full.getByte(1));
        log.error("{} != {} ?", verifyByte1, full.getByte(size / 2));
        byte[] dst = new byte[full.writerIndex()];
        full.getBytes(0, dst);
        log.error("full:{}", dst);
        throw new SocketException("报文尾部验证失败");
    }
}
