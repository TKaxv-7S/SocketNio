package com.tk.socket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Optional;

@Slf4j
public class SocketParseMsgDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final byte DATA_START_BYTE = (byte) 0xA5;

    //身部
    private byte[] msg;

    //加密字节
    private Byte secretByte;

    //全部
    private CompositeByteBuf full;

    //长度
    private Integer size;

    private Long msgParsedTimeMillis;

    private Boolean isDone = false;

    private Boolean isRelease = false;

    private final Integer sizeLimit;

    private final Long msgExpireTimeMillis;

    public byte[] getMsg() {
        return msg;
    }

    public Byte getSecretByte() {
        return secretByte;
    }

    public CompositeByteBuf getFull() {
        return full;
    }

    public Long getMsgParsedTimeMillis() {
        return msgParsedTimeMillis;
    }

    public Boolean getDone() {
        return isDone;
    }

    public Boolean getRelease() {
        return isRelease;
    }

    public Integer getSize() {
        return size;
    }

    public Integer getSizeLimit() {
        return sizeLimit;
    }

    public Long getMsgExpireTimeMillis() {
        return msgExpireTimeMillis;
    }

    public SocketParseMsgDto(Integer sizeLimit, Integer msgExpireTimeMillis) {
        this(ByteBufAllocator.DEFAULT.compositeBuffer(), null, sizeLimit, msgExpireTimeMillis);
    }

    public SocketParseMsgDto(CompositeByteBuf full, Integer sizeLimit) {
        this(full, null, sizeLimit, null);
    }

    public SocketParseMsgDto(CompositeByteBuf full, Integer size, Integer sizeLimit) {
        this(full, size, sizeLimit, null);
    }

    public SocketParseMsgDto(CompositeByteBuf full, Integer size, Integer sizeLimit, Integer msgExpireSeconds) {
        this.size = size;
        this.full = full;
        this.sizeLimit = sizeLimit;
        this.msgExpireTimeMillis = Optional.ofNullable(msgExpireSeconds).orElse(10) * 1000L;
    }

    public synchronized ByteBuf parsingMsg(ByteBuf msg) {
        if (isDone) {
            clear();
        }
        long currentTimeMillis = System.currentTimeMillis();
        if (msgParsedTimeMillis != null && currentTimeMillis > msgParsedTimeMillis + msgExpireTimeMillis) {
            clear();
        }
        msgParsedTimeMillis = currentTimeMillis;
        if (!msg.isReadable()) {
            return null;
        }
        int readableBytes;
        int writeIndex;
        if (full.writerIndex() <= 0) {
            readableBytes = msg.readableBytes();
            if (readableBytes < 5) {
                //数据不足，继续等待
                full.addComponent(true, msg);
                return null;
            }
            //头部，5字节
            ByteBuf head = msg.readRetainedSlice(5);
            size = checkMsgFirst(head);
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
                        return null;
                    }
                }
                //头部，5字节
                ByteBuf head = msg.readRetainedSlice(5);
                size = checkMsgFirst(head);
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
                return null;
            } else if (i < 0) {
                //此处粘包了，手动切割
                full.addComponent(true, msg.readSlice(leftDataLength));
                stickMsg = msg.retainedSlice();
            } else {
                full.addComponent(true, msg);
            }
        } else if (leftDataLength < 0) {
            //丢弃并关闭连接
            throw new SocketException("报文数据异常");
        }
        checkMsgTail();
        this.msg = new byte[size - 8];
        full.getBytes(5, this.msg);
        isDone = true;
        return stickMsg;
    }

    public int checkMsgFirst(ByteBuf msg) {
        //验证报文头是否为：10100101
        if (DATA_START_BYTE != msg.getByte(0)) {
            return 0;
        }
        int msgSize = ((msg.getByte(1) & 0xFF) << 24) |
                ((msg.getByte(2) & 0xFF) << 16) |
                ((msg.getByte(3) & 0xFF) << 8) |
                (msg.getByte(4) & 0xFF);
        if (msgSize > sizeLimit) {
            return 0;
        }
        /*if (log.isDebugEnabled()) {
            log.debug("检查报文 长度：{}，头部：{},{},{},{},{}"
                    , msgSize
                    , msg.getByte(0)
                    , msg.getByte(1)
                    , msg.getByte(2)
                    , msg.getByte(3)
                    , msg.getByte(4)
            );
        }*/
        return msgSize;
    }

    private synchronized void checkMsgTail() {
        //报文尾共3字节，2字节为 msgSize首字节 + 数据中间字节，1字节加密类型
        if (full.getByte(size - 3) == full.getByte(1) && full.getByte(size - 2) == full.getByte(size / 2)) {
            secretByte = full.getByte(size - 1);
            return;
        }
        throw new SocketException("报文尾部验证失败");
    }

    public synchronized Boolean isExpire() {
        return msgParsedTimeMillis != null && msgParsedTimeMillis + msgExpireTimeMillis >= System.currentTimeMillis();
    }

    public synchronized void release() {
        while (full.refCnt() > 0) {
            ReferenceCountUtil.release(full);
        }
        isRelease = true;
    }

    public synchronized void clear() {
        while (full.refCnt() > 0) {
            ReferenceCountUtil.release(full);
        }
        msg = null;
        secretByte = null;
        full = ByteBufAllocator.DEFAULT.compositeBuffer();
        size = null;
        isDone = false;
        isRelease = false;
    }
}
