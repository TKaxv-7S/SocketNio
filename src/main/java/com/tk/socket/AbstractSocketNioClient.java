package com.tk.socket;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public abstract class AbstractSocketNioClient {

    private Boolean isInit = false;

    public Boolean getIsInit() {
        return isInit;
    }

    private final Object lockObj = new Object();

    private Channel channel;

    public Channel getChannel() {
        return channel;
    }

    private final Cache<ChannelId, SocketMsgDto<ByteBuf>> readCacheMap = Caffeine.newBuilder()
            .expireAfterAccess(30, TimeUnit.SECONDS)
            .removalListener((ChannelId key, SocketMsgDto<ByteBuf> value, RemovalCause removalCause) -> {
                ReferenceCountUtil.release(value.getMsg());
            })
            //key必须使用弱引用
            .weakKeys()
            .build();

    private DataConsumerThreadPoolExecutor<ChannelHandlerContext, byte[]> dataConsumerThreadPoolExecutor;

    private final int dataSizeLimit = Optional.ofNullable(setBytesSizeLimit()).orElse(4 * 1024 * 1024);

    private final Map<Integer, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    public abstract String setHost();

    public abstract Integer setPort();

    public abstract SocketEncodeDto encode(byte[] data);

    public abstract byte[] decode(byte[] data, byte secretByte);

    public abstract Consumer<byte[]> setDataConsumer();

    public abstract Integer setBytesSizeLimit();

    public abstract int setMaxHandlerDataThreadCount();

    public abstract int setSingleThreadDataConsumerCount();

    @ChannelHandler.Sharable
    class ClientInHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            super.channelRegistered(ctx);
            log.info("服务端channelId：{}，已注册", ctx.channel().id());
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            super.channelUnregistered(ctx);
            log.info("服务端channelId：{}，已注销", ctx.channel().id());
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msgObj) throws Exception {
            ByteBuf msg = (ByteBuf) msgObj;
            Channel socketChannel = ctx.channel();
            ChannelId channelId = socketChannel.id();
            ByteBuf byteBuf = null;
            boolean isReleaseMsg = false;
            try {
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
                        isReleaseMsg = true;
                        readCacheMap.put(channelId, new SocketMsgDto<>(null, byteBuf));
                        return;
                    }
                    byteBuf.writeBytes(msg, 5);
                    msgSize = SocketMessageUtil.checkMsgFirst(byteBuf, dataSizeLimit);
                    //5+2+3
                    if (msgSize < 11) {
                        //丢弃
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
                                isReleaseMsg = true;
                                socketMsgDto.setMsg(byteBuf);
                                //刷新
                                //readCacheMap.put(channelId, socketMsgDto);
                                return;
                            }
                        }
                        msgSize = SocketMessageUtil.checkMsgFirst(byteBuf, dataSizeLimit);
                        if (msgSize < 11) {
                            //丢弃
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
                        isReleaseMsg = true;
                        /*if (log.isDebugEnabled()) {
                            log.debug("byteBuf处理中 已读：{}，已写：{}，容量，{}", byteBuf.readerIndex(), byteBuf.writerIndex(), byteBuf.capacity());
                            log.debug("msg处理中 已读：{}，已写：{}，容量，{}", msg.readerIndex(), msg.writerIndex(), msg.capacity());
                        }*/
                        return;
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
                    //丢弃
                    isReleaseMsg = true;
                    readCacheMap.invalidate(channelId);
                    return;
                }
                Byte secretByte = SocketMessageUtil.checkMsgTail(byteBuf, msgSize);
                if (secretByte == null) {
                    //丢弃
                    isReleaseMsg = true;
                    readCacheMap.invalidate(channelId);
                    return;
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
                    decodeBytes = decode(bytes, secretByte);
                } catch (Exception e) {
                    log.error("客户端解码错误", e);
                    throw new BusinessException("报文解码错误");
                }
                int length = decodeBytes.length;
                boolean isAckData = SocketMessageUtil.isAckData(decodeBytes);
                log.debug("客户端接收isAckData：{}", isAckData);
                if (length > 3) {
                    log.debug("客户端开始处理数据，channelId：{}", channelId);
                    dataConsumerThreadPoolExecutor.putData(ctx, SocketMessageUtil.unPackageData(decodeBytes));
                } else {
                    if (!isAckData) {
                        //接收ackBytes
                        byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                        log.debug("客户端ack接收，ackBytes：{}", ackBytes);
                        Integer ackKey = SocketMessageUtil.threeByteArrayToInt(ackBytes);
                        SocketAckThreadDto socketAckThreadDto = ackDataMap.get(ackKey);
                        if (socketAckThreadDto != null) {
                            synchronized (socketAckThreadDto) {
                                socketAckThreadDto.setIsAck(true);
                                socketAckThreadDto.notify();
                            }
//                            LockSupport.unpark(socketAckThreadDto.getThread());
                            log.debug("客户端ack成功，ackBytes：{}", ackBytes);
                            readCacheMap.invalidate(channelId);
                            return;
                        }
                    }
                    //丢弃
                    readCacheMap.invalidate(channelId);
                    return;
                }
                if (isAckData) {
                    byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                    try {
                        socketChannel.writeAndFlush(ackBytes);
                        log.debug("客户端已发送ackBytes：{}", ackBytes);
                        readCacheMap.invalidate(channelId);
                        return;
                    } catch (Exception e) {
                        log.error("客户端ack编码错误", e);
                    }
                }
                //正常丢弃
                readCacheMap.invalidate(channelId);
                if (isStick) {
                    int newReadableBytes = msg.readableBytes();
                    ByteBuf newByteBuf = Unpooled.buffer();
                    if (newReadableBytes < 5) {
                        //数据不足，继续等待
                        newByteBuf.writeBytes(msg);
                        //已读取完，可释放
                        isReleaseMsg = true;
                        readCacheMap.put(channelId, new SocketMsgDto<>(null, newByteBuf));
                        return;
                    }
                    newByteBuf.writeBytes(msg, newReadableBytes);
                    int newMsgSize = SocketMessageUtil.checkMsgFirst(newByteBuf, dataSizeLimit);
                    //5+2+3+1
                    if (newMsgSize < 11) {
                        //丢弃并关闭连接
                        throw new BusinessException("报文格式错误");
                    }
                    readCacheMap.put(channelId, new SocketMsgDto<>(newMsgSize, newByteBuf));
                }
            } catch (Exception e) {
                log.error("客户端数据解析异常", e);
                //丢弃数据并关闭连接
                isReleaseMsg = true;
                close();
                //异常才释放
                readCacheMap.invalidate(channelId);
            } finally {
                if (isReleaseMsg) {
                    ReferenceCountUtil.release(msg);
                }
            }
        }
    }

    @ChannelHandler.Sharable
    class ClientOutHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof byte[]) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(encode((byte[]) msg))), promise);
            } else {
                //传输其他类型数据时暂不支持ACK，需使用byte[]
                ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(encode(SocketMessageUtil.packageData(JSONUtil.toJsonStr(msg).getBytes(StandardCharsets.UTF_8), false)))), promise);
            }
            log.debug("客户端数据已发送，channelId：{}", ctx.channel().id());
        }
    }

    public boolean isClosed() {
        return channel == null || !channel.isActive();
    }

    public synchronized void close() {
        if (!isClosed()) {
            synchronized (channel) {
                readCacheMap.cleanUp();
                channel.close();
                isInit = false;
            }
        }
        log.info("连接已关闭");
    }

    public void write(byte[] data) {
        if (isClosed()) {
            initNioClientAsync();
            throw new BusinessException("连接未就绪");
        }
        try {
            channel.writeAndFlush(SocketMessageUtil.packageData(data, false));
        } catch (Exception e) {
            log.error("SocketNioClient写入异常", e);
            throw e;
        } finally {
        }
    }

    public boolean writeAck(byte[] data, int seconds) {
        if (isClosed()) {
            initNioClientAsync();
            return false;
        }
        byte[] packageData = SocketMessageUtil.packageData(data, true);
        byte[] needAckBytes = {(byte) (packageData[0] & (byte) 0x7F), packageData[1], packageData[2]};
        Integer ackKey = SocketMessageUtil.threeByteArrayToInt(needAckBytes);
        try {
            SocketAckThreadDto ackThreadDto = new SocketAckThreadDto();
            ackDataMap.put(ackKey, ackThreadDto);
            synchronized (ackThreadDto) {
                try {
                    channel.writeAndFlush(packageData);
                } catch (Exception e) {
                    log.error("SocketNioClient写入异常", e);
                    throw e;
                }
                ackThreadDto.wait(TimeUnit.SECONDS.toMillis(seconds));
            }
//            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(seconds));
            SocketAckThreadDto socketAckThreadDto = ackDataMap.remove(ackKey);
            return socketAckThreadDto != null && socketAckThreadDto.getIsAck();
        } catch (InterruptedException e) {
            log.error("SocketNioClient写入异常", e);
            ackDataMap.remove(ackKey);
            throw new BusinessException("SocketNioClient写入异常");
        } catch (Exception e) {
            log.error("setAndWaitAck异常", e);
            ackDataMap.remove(ackKey);
            throw e;
        }
    }

    public boolean writeAck(byte[] data) {
        return writeAck(data, 10);
    }

    public synchronized void initNioClientAsync() {
        if (!isInit || isClosed()) {
            ThreadUtil.execute(() -> {
                EventLoopGroup bossGroup = null;
                synchronized (lockObj) {
                    try {
                        if (!isInit || isClosed()) {
                            //主线程组
                            String host = setHost();
                            Integer port = setPort();
                            Bootstrap bootstrap = new Bootstrap()
                                    .group(bossGroup = new NioEventLoopGroup(2))
                                    .channel(NioSocketChannel.class)
                                    .handler(new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                                            socketChannel.pipeline()
                                                    .addLast(new ClientInHandler())
                                                    .addLast(new ClientOutHandler());
                                        }
                                    })
                                    //首选直接内存
                                    .option(ChannelOption.ALLOCATOR, PreferredDirectByteBufAllocator.DEFAULT)
                                    //设置队列大小
                                    //.option(ChannelOption.SO_BACKLOG, 1024)
                                    .option(ChannelOption.TCP_NODELAY, true)
                                    .option(ChannelOption.SO_KEEPALIVE, true)
                                    .option(ChannelOption.SO_RCVBUF, 4096 * 1024)
                                    .option(ChannelOption.SO_SNDBUF, 1024 * 1024)
                                    //客户端低水位线设置为1M，高水位线设置为2M
                                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1024 * 1024, 2 * 1024 * 1024));
                            ChannelFuture channelFuture = bootstrap.connect(host, port);
                            channel = channelFuture.sync().channel();
                            if (dataConsumerThreadPoolExecutor == null) {
                                dataConsumerThreadPoolExecutor = new DataConsumerThreadPoolExecutor<>(
                                        (c, d) -> setDataConsumer().accept(d)
                                        , setMaxHandlerDataThreadCount()
                                        , setSingleThreadDataConsumerCount()
                                );
                            }
                            log.info("SocketNioClient已连接，地址：{}，端口: {}", host, port);
                            isInit = true;
                            channel.closeFuture().sync();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        //关闭主线程组
                        if (bossGroup != null) {
                            bossGroup.shutdownGracefully();
                        }
                    }
                }
            });
        }
    }
}
