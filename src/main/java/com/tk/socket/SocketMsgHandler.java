package com.tk.socket;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.tk.utils.JsonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
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
import java.util.concurrent.*;
import java.util.function.BiConsumer;

@Slf4j
public class SocketMsgHandler {

    private final int msgSizeLimit;

    private final Map<String, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    private final Cache<ChannelId, SocketMsgDto<CompositeByteBuf>> readCacheMap;

    private final MsgDecode msgDecode;

    private final MsgEncode msgEncode;

    private final BiConsumer<ChannelHandlerContext, byte[]> dataConsumer;

    private final ThreadPoolExecutor dataConsumerThreadPoolExecutor;

    public SocketMsgHandler() {
        throw new SocketException("该类不可使用无参构造函数实例化");
    }

    public SocketMsgHandler(
            Integer msgExpireSeconds
            , Integer msgSizeLimit
            , MsgDecode msgDecode
            , MsgEncode msgEncode
            , BiConsumer<ChannelHandlerContext, byte[]> dataConsumer
            , int maxDataThreadCount
    ) {
        msgExpireSeconds = Optional.ofNullable(msgExpireSeconds).orElse(10);
        this.msgSizeLimit = Optional.ofNullable(msgSizeLimit).orElse(4 * 1024 * 1024);
        this.msgDecode = msgDecode;
        this.msgEncode = msgEncode;
        this.readCacheMap = Caffeine.newBuilder()
                .expireAfterAccess(msgExpireSeconds, TimeUnit.SECONDS)
                .removalListener((ChannelId key, SocketMsgDto<CompositeByteBuf> value, RemovalCause removalCause) -> {
                    if (value != null) {
                        CompositeByteBuf msg = value.getMsg();
                        while (msg.refCnt() > 0) {
                            ReferenceCountUtil.release(msg);
                        }
                    }
                })
                .build();
        this.dataConsumer = dataConsumer;

        int corePoolSize = Runtime.getRuntime().availableProcessors();
        int maxPoolSize = Math.max(maxDataThreadCount, corePoolSize);
        this.dataConsumerThreadPoolExecutor = new ThreadPoolExecutor(corePoolSize, maxPoolSize, TimeUnit.SECONDS.toNanos(60), TimeUnit.NANOSECONDS, new LinkedBlockingQueue<>());

    }

    public void putData(ChannelHandlerContext channel, byte[] data) throws InterruptedException {
        //如果队列已满，需阻塞
        dataConsumerThreadPoolExecutor.execute(() -> dataConsumer.accept(channel, data));
    }

    public void readMsg(ChannelHandlerContext ctx, ByteBuf msg) throws InterruptedException {
        Channel socketChannel = ctx.channel();
        ChannelId channelId = socketChannel.id();
        try {
            parsingMsg(ctx, msg);
        } catch (Exception e) {
            log.error("数据解析异常：{}", e.getMessage());
            //丢弃数据并关闭连接
            log.debug("msg.refCnt：{}", msg.refCnt());
            socketChannel.close();
            //异常才释放
            readCacheMap.invalidate(channelId);
            while (msg.refCnt() > 0) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    public void parsingMsg(ChannelHandlerContext ctx, ByteBuf msg) throws InterruptedException {
        Channel socketChannel = ctx.channel();
        ChannelId channelId = socketChannel.id();
        CompositeByteBuf compositeByteBuf;
        int readableBytes;
        Integer msgSize;
        int writeIndex;
        SocketMsgDto<CompositeByteBuf> socketMsgDto = readCacheMap.getIfPresent(channelId);
        if (socketMsgDto == null) {
            readableBytes = msg.readableBytes();
            if (readableBytes < 5) {
                compositeByteBuf = ByteBufAllocator.DEFAULT.compositeBuffer();
                //数据不足，继续等待
                compositeByteBuf.addComponent(true, msg);
                readCacheMap.put(channelId, new SocketMsgDto<>(null, compositeByteBuf));
                return;
            }
            ByteBuf headMsg = msg.readRetainedSlice(5);
            msgSize = SocketMessageUtil.checkMsgFirst(headMsg, msgSizeLimit);
            //5+2+3+1
            if (msgSize < 11) {
                //丢弃并关闭连接
                throw new SocketException("非法报文");
            }
            compositeByteBuf = ByteBufAllocator.DEFAULT.compositeBuffer(msgSize);
            compositeByteBuf.addComponent(true, headMsg);
            compositeByteBuf.readerIndex(5);
            writeIndex = compositeByteBuf.writerIndex();
            readCacheMap.put(channelId, new SocketMsgDto<>(msgSize, compositeByteBuf));
        } else {
            compositeByteBuf = socketMsgDto.getMsg();
            msgSize = socketMsgDto.getSize();
            if (msgSize == null) {
                //补全数据
                int writerIndex = compositeByteBuf.writerIndex();
                readableBytes = msg.readableBytes();
                if (writerIndex < 4) {
                    if (writerIndex + readableBytes < 4) {
                        //数据不足，继续等待
                        compositeByteBuf.addComponent(true, msg);
                        socketMsgDto.setMsg(compositeByteBuf);
                        //刷新
                        //readCacheMap.put(channelId, socketMsgDto);
                        return;
                    }
                }
                ByteBuf headMsg = msg.readRetainedSlice(5);
                msgSize = SocketMessageUtil.checkMsgFirst(headMsg, msgSizeLimit);
                if (msgSize < 11) {
                    //丢弃并关闭连接
                    throw new SocketException("非法报文");
                }
                compositeByteBuf = ByteBufAllocator.DEFAULT.compositeBuffer(msgSize);
                compositeByteBuf.addComponent(true, headMsg);
                compositeByteBuf.readerIndex(5);
                socketMsgDto.setSize(msgSize);
                //刷新
                //readCacheMap.put(channelId, socketMsgDto);
            }
            writeIndex = compositeByteBuf.writerIndex();
        }
        ByteBuf stickMsg = null;
        int leftDataLength = msgSize - writeIndex;
        if (leftDataLength > 0) {
            readableBytes = msg.readableBytes();
            int i = leftDataLength - readableBytes;
            if (i > 0) {
                //继续读取
                compositeByteBuf.addComponent(true, msg);
                //已读取完，可释放
                        /*if (log.isDebugEnabled()) {
                            log.debug("byteBuf处理中 已读：{}，已写：{}，容量，{}", byteBuf.readerIndex(), byteBuf.writerIndex(), byteBuf.capacity());
                            log.debug("msg处理中 已读：{}，已写：{}，容量，{}", msg.readerIndex(), msg.writerIndex(), msg.capacity());
                        }*/
                return;
            } else if (i < 0) {
                //此处粘包了，手动切割
                compositeByteBuf.addComponent(true, msg.slice(msg.readerIndex(), leftDataLength));
                stickMsg = msg.retainedSlice(msg.readerIndex() + leftDataLength, -i);
            } else {
                compositeByteBuf.addComponent(true, msg);
            }
        } else if (leftDataLength < 0) {
            //丢弃并关闭连接
            throw new SocketException("报文数据异常");
        }
        Byte secretByte = SocketMessageUtil.checkMsgTail(compositeByteBuf, msgSize);
        if (secretByte == null) {
            //丢弃并关闭连接
            throw new SocketException("报文尾部验证失败");
        }
        //读取完成，写入队列
        byte[] decodeBytes;
        try {
            byte[] bytes = new byte[msgSize - 8];
            compositeByteBuf.readBytes(bytes);
                    /*if (log.isDebugEnabled()) {
                        log.debug("byteBuf完成 已读：{}，已写：{}，容量，{}", byteBuf.readerIndex(), byteBuf.writerIndex(), byteBuf.capacity());
                        log.debug("msg完成 已读：{}，已写：{}，容量，{}", msg.readerIndex(), msg.writerIndex(), msg.capacity());
                    }*/
            decodeBytes = msgDecode.decode(ctx.channel(), bytes, secretByte);
        } catch (Exception e) {
            log.debug("解码错误", e);
            //丢弃并关闭连接
            throw new SocketException("报文解码错误");
        }
        int length = decodeBytes.length;
        boolean isAckData = SocketMessageUtil.isAckData(decodeBytes);
        if (length > 3) {
            log.debug("数据已接收，channelId：{}", channelId);
            putData(ctx, SocketMessageUtil.unPackageData(decodeBytes));
        } else {
            if (!isAckData) {
                //接收ackBytes
                byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                int ackKey = SocketMessageUtil.threeByteArrayToInt(ackBytes);
                String key = Integer.toString(ackKey).concat(channelId.asShortText());
                SocketAckThreadDto socketAckThreadDto = ackDataMap.get(key);
                if (socketAckThreadDto != null) {
                    synchronized (socketAckThreadDto) {
                        socketAckThreadDto.setIsAck(true);
                        socketAckThreadDto.notify();
                    }
//                            LockSupport.unpark(socketAckThreadDto.getThread());
                    log.debug("接收ack字节：{}，已完成", ackBytes);
                    readCacheMap.invalidate(channelId);
                    return;
                } else {
                    log.error("接收ack字节：{}，未命中或请求超时", ackBytes);
                }
            } else {
                //关闭连接
                throw new SocketException("报文数据异常");
            }
            //丢弃
            readCacheMap.invalidate(channelId);
            return;
        }
        if (isAckData) {
            byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
            try {
                socketChannel.writeAndFlush(ackBytes);
                log.debug("发送ack字节：{}", ackBytes);
                readCacheMap.invalidate(channelId);
                return;
            } catch (Exception e) {
                log.error("ack编码错误", e);
            }
        }
        //正常丢弃
        readCacheMap.invalidate(channelId);
        if (stickMsg != null) {
            parsingMsg(ctx, stickMsg);
        }
    }

    public void handlerWrite(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof byte[]) {
            ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(msgEncode.encode(ctx.channel(), (byte[]) msg))), promise);
        } else {
            //传输其他类型数据时暂不支持ACK，需使用byte[]
            ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(msgEncode.encode(ctx.channel(), SocketMessageUtil.packageData(JsonUtil.toJsonString(msg).getBytes(StandardCharsets.UTF_8), false)))), promise);
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
     * 同步写
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
                log.debug("等待ack字节：{}", needAckBytes);
                ackThreadDto.wait(TimeUnit.SECONDS.toMillis(seconds));
            }
//            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(Math.min(seconds, 10)));
            SocketAckThreadDto socketAckThreadDto = ackDataMap.remove(key);
            return socketAckThreadDto != null && socketAckThreadDto.getIsAck();
        } catch (InterruptedException e) {
            log.error("同步写异常", e);
            ackDataMap.remove(key);
            throw new SocketException("同步写异常");
        } catch (Exception e) {
            log.error("同步写异常", e);
            ackDataMap.remove(key);
            throw e;
        }
    }

    public void cleanUpReadCacheMap() {
        readCacheMap.cleanUp();
    }

    public boolean shutdownNow() {
        try {
            shutdownAndAwaitTermination(dataConsumerThreadPoolExecutor, 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("socket消费线程池关闭失败");
            throw new SocketException(e, "socket消费线程池关闭异常");
        }
        cleanUpReadCacheMap();
        ackDataMap.clear();
        return true;
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool, long timeout, TimeUnit unit) {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    if (!pool.awaitTermination(timeout, unit)) {
                        log.info("线程池暂时无法关闭");
                    }
                }
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    public interface MsgDecode {
        byte[] decode(Channel channel, byte[] data, byte secretByte);
    }

    @FunctionalInterface
    public interface MsgEncode {
        SocketEncodeDto encode(Channel channel, byte[] data);
    }
}