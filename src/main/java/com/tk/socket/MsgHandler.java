package com.tk.socket;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
public class MsgHandler {

    private final int msgSizeLimit;

    private final Map<String, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    private final Cache<ChannelId, SocketMsgDto<ByteBuf>> readCacheMap;

    private final MsgDecode msgDecode;

    private final MsgEncode msgEncode;

    private final DataConsumerThreadPoolExecutor<ChannelHandlerContext, byte[]> dataConsumerThreadPoolExecutor;

    public MsgHandler() {
        throw new BusinessException("该类不可使用无参构造函数实例化");
    }

    public MsgHandler(
            Integer msgExpireSeconds
            , Integer msgSizeLimit
            , MsgDecode msgDecode
            , MsgEncode msgEncode
            , BiConsumer<ChannelHandlerContext, byte[]> dataConsumer
            , int maxDataThreadCount
            , int singleThreadDataConsumerCount
    ) {
        msgExpireSeconds = Optional.ofNullable(msgExpireSeconds).orElse(10);
        this.msgSizeLimit = Optional.ofNullable(msgSizeLimit).orElse(4 * 1024 * 1024);
        this.msgDecode = msgDecode;
        this.msgEncode = msgEncode;
        this.dataConsumerThreadPoolExecutor = new DataConsumerThreadPoolExecutor<>(dataConsumer, maxDataThreadCount, singleThreadDataConsumerCount);
        this.readCacheMap = Caffeine.newBuilder()
                .expireAfterAccess(msgExpireSeconds, TimeUnit.SECONDS)
                .removalListener((ChannelId key, SocketMsgDto<ByteBuf> value, RemovalCause removalCause) -> ReferenceCountUtil.release(value.getMsg()))
                //key必须使用弱引用
                .weakKeys()
                .build();
    }

    public void readMsg(ChannelHandlerContext ctx, ByteBuf msg) throws InterruptedException {
        Channel socketChannel = ctx.channel();
        ChannelId channelId = socketChannel.id();
        boolean isReleaseMsg = false;
        try {
            isReleaseMsg = parsingMsg(ctx, msg);
        } catch (Exception e) {
            log.error("数据解析异常", e);
            //丢弃数据并关闭连接
            isReleaseMsg = true;
            socketChannel.close();
            //异常才释放
            readCacheMap.invalidate(channelId);
        } finally {
            if (isReleaseMsg) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    public boolean parsingMsg(ChannelHandlerContext ctx, ByteBuf msg) throws InterruptedException {
        Channel socketChannel = ctx.channel();
        ChannelId channelId = socketChannel.id();
        ByteBuf byteBuf;
        boolean isReleaseMsg = false;
        int readableBytes;
        Integer msgSize;
        int writeIndex;
        SocketMsgDto<ByteBuf> socketMsgDto = readCacheMap.getIfPresent(channelId);
        if (socketMsgDto == null) {
            readableBytes = msg.readableBytes();
            byteBuf = Unpooled.buffer();
            if (readableBytes < 5) {
                //数据不足，继续等待
                byteBuf.writeBytes(msg);
                //已读取完，可释放
                readCacheMap.put(channelId, new SocketMsgDto<>(null, byteBuf));
                return true;
            }
            byteBuf.writeBytes(msg, 5);
            msgSize = SocketMessageUtil.checkMsgFirst(byteBuf, msgSizeLimit);
            //5+2+3+1
            if (msgSize < 11) {
                //丢弃并关闭连接
                throw new BusinessException("报文格式错误");
            }
            writeIndex = byteBuf.writerIndex();
            readCacheMap.put(channelId, new SocketMsgDto<>(msgSize, byteBuf));
        } else {
            byteBuf = socketMsgDto.getMsg();
            msgSize = socketMsgDto.getSize();
            if (msgSize == null) {
                //补全数据
                int writerIndex = byteBuf.writerIndex();
                if (writerIndex < 4) {
                    readableBytes = msg.readableBytes();
                    if (writerIndex + readableBytes < 4) {
                        //数据不足，继续等待
                        byteBuf.writeBytes(msg);
                        //已读取完，可释放
                        socketMsgDto.setMsg(byteBuf);
                        //刷新
                        //readCacheMap.put(channelId, socketMsgDto);
                        return true;
                    }
                }
                msgSize = SocketMessageUtil.checkMsgFirst(byteBuf, msgSizeLimit);
                if (msgSize < 11) {
                    //丢弃并关闭连接
                    throw new BusinessException("报文格式错误");
                }
                socketMsgDto.setSize(msgSize);
                //刷新
                //readCacheMap.put(channelId, socketMsgDto);
            }
            writeIndex = byteBuf.writerIndex();
        }
        boolean isStick = false;
        int leftDataIndex = msgSize - writeIndex;
        if (leftDataIndex > 0) {
            readableBytes = msg.readableBytes();
            int i = leftDataIndex - readableBytes;
            if (i > 0) {
                //继续读取
                byteBuf.writeBytes(msg);
                //已读取完，可释放
                        /*if (log.isDebugEnabled()) {
                            log.debug("byteBuf处理中 已读：{}，已写：{}，容量，{}", byteBuf.readerIndex(), byteBuf.writerIndex(), byteBuf.capacity());
                            log.debug("msg处理中 已读：{}，已写：{}，容量，{}", msg.readerIndex(), msg.writerIndex(), msg.capacity());
                        }*/
                return true;
            } else if (i < 0) {
                //此处粘包了，手动切割
                byteBuf.writeBytes(msg, leftDataIndex);
                isStick = true;
            } else {
                byteBuf.writeBytes(msg);
                //已读取完，可释放
                isReleaseMsg = true;
            }
        } else if (leftDataIndex < 0) {
            //丢弃并关闭连接
            throw new BusinessException("报文数据异常");
        }
        Byte secretByte = SocketMessageUtil.checkMsgTail(byteBuf, msgSize);
        if (secretByte == null) {
            //丢弃并关闭连接
            throw new BusinessException("报文尾部验证失败");
        }
        //读取完成，写入队列
        byte[] decodeBytes;
        try {
            byte[] bytes = new byte[msgSize - 8];
            byteBuf.getBytes(5, bytes);
                    /*if (log.isDebugEnabled()) {
                        log.debug("byteBuf完成 已读：{}，已写：{}，容量，{}", byteBuf.readerIndex(), byteBuf.writerIndex(), byteBuf.capacity());
                        log.debug("msg完成 已读：{}，已写：{}，容量，{}", msg.readerIndex(), msg.writerIndex(), msg.capacity());
                    }*/
            decodeBytes = msgDecode.decode(channelId, bytes, secretByte);
        } catch (Exception e) {
            log.debug("解码错误", e);
            //丢弃并关闭连接
            throw new BusinessException("报文解码错误");
        }
        int length = decodeBytes.length;
        boolean isAckData = SocketMessageUtil.isAckData(decodeBytes);
        log.debug("接收isAckData：{}", isAckData);
        if (length > 3) {
            log.debug("开始处理数据，channelId：{}", channelId);
            dataConsumerThreadPoolExecutor.putData(ctx, SocketMessageUtil.unPackageData(decodeBytes));
        } else {
            if (!isAckData) {
                //接收ackBytes
                byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                log.debug("ack接收，ackBytes：{}", ackBytes);
                int ackKey = SocketMessageUtil.threeByteArrayToInt(ackBytes);
                String key = Integer.toString(ackKey).concat(channelId.asShortText());
                SocketAckThreadDto socketAckThreadDto = ackDataMap.get(key);
                if (socketAckThreadDto != null) {
                    synchronized (socketAckThreadDto) {
                        socketAckThreadDto.setIsAck(true);
                        socketAckThreadDto.notify();
                    }
//                            LockSupport.unpark(socketAckThreadDto.getThread());
                    log.debug("ack成功，ackBytes：{}", ackBytes);
                    readCacheMap.invalidate(channelId);
                    return isReleaseMsg;
                }
            } else {
                //关闭连接
                throw new BusinessException("报文数据异常");
            }
            //丢弃
            readCacheMap.invalidate(channelId);
            return isReleaseMsg;
        }
        if (isAckData) {
            byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
            try {
                socketChannel.writeAndFlush(ackBytes);
                log.debug("已发送ackBytes：{}", ackBytes);
                readCacheMap.invalidate(channelId);
                return isReleaseMsg;
            } catch (Exception e) {
                log.error("ack编码错误", e);
            }
        }
        //正常丢弃
        readCacheMap.invalidate(channelId);
        if (isStick) {
            if (parsingMsg(ctx, msg)) {
                isReleaseMsg = true;
            }
        }
        return isReleaseMsg;
    }

    public void handlerWrite(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof byte[]) {
            ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(msgEncode.encode(ctx.channel().id(), (byte[]) msg))), promise);
        } else {
            //传输其他类型数据时暂不支持ACK，需使用byte[]
            ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(msgEncode.encode(ctx.channel().id(), SocketMessageUtil.packageData(JSONUtil.toJsonStr(msg).getBytes(StandardCharsets.UTF_8), false)))), promise);
        }
        log.debug("数据已发送，channelId：{}", ctx.channel().id());
    }

    public void write(Channel socketChannel, byte[] data) {
        try {
            socketChannel.writeAndFlush(SocketMessageUtil.packageData(data, false));
        } catch (Exception e) {
            log.error("写入异常", e);
            throw e;
        }
    }

    /**
     * 等待确认时间最久10秒
     *
     * @param socketChannel
     * @param data
     * @param seconds
     * @return
     */
    public boolean writeAck(Channel socketChannel, byte[] data, int seconds) {
        byte[] packageData = SocketMessageUtil.packageData(data, true);
        byte[] needAckBytes = {(byte) (packageData[0] & (byte) 0x7F), packageData[1], packageData[2]};
        int ackKey = SocketMessageUtil.threeByteArrayToInt(needAckBytes);
        String key = Integer.toString(ackKey).concat(socketChannel.id().asShortText());
        try {
            SocketAckThreadDto ackThreadDto = new SocketAckThreadDto();
            ackDataMap.put(key, ackThreadDto);
            synchronized (ackThreadDto) {
                socketChannel.writeAndFlush(packageData);
                log.debug("等待needAckBytes：{}", needAckBytes);
                ackThreadDto.wait(TimeUnit.SECONDS.toMillis(seconds));
            }
//            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(Math.min(seconds, 10)));
            SocketAckThreadDto socketAckThreadDto = ackDataMap.remove(key);
            return socketAckThreadDto != null && socketAckThreadDto.getIsAck();
        } catch (InterruptedException e) {
            log.error("写入异常", e);
            ackDataMap.remove(key);
            throw new BusinessException("写入异常");
        } catch (Exception e) {
            log.error("setAndWaitAck异常", e);
            ackDataMap.remove(key);
            throw e;
        }
    }

    public void cleanUpReadCacheMap() {
        readCacheMap.cleanUp();
    }

    @FunctionalInterface
    interface MsgDecode {
        byte[] decode(ChannelId channelId, byte[] data, byte secretByte);
    }

    @FunctionalInterface
    interface MsgEncode {
        SocketEncodeDto encode(ChannelId channelId, byte[] data);
    }
}