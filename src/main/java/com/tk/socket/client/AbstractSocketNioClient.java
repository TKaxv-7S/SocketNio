package com.tk.socket.client;

import com.tk.socket.*;
import com.tk.socket.utils.JsonUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public abstract class AbstractSocketNioClient {

    private Bootstrap bootstrap = null;

    public Boolean getIsInit() {
        return bootstrap != null;
    }

    private final Object lockObj = new Object();

    private SocketNioChannelPool channelPool;

    private SocketMsgHandler socketMsgHandler;

    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

    private final Runnable initRunnable;

    public final AbstractSocketClientConfig config;

    private final Integer msgSizeLimit;

    protected AbstractSocketClientConfig getConfig() {
        return config;
    }

    protected Consumer<AbstractSocketNioClient> connCallback;

    private final AttributeKey<SocketParseMsgDto> msgKey = AttributeKey.valueOf("msg");

    private final Map<String, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    private final byte[] heartbeatBytes = JsonUtil.toJsonString(SocketMsgDataDto.build("", null)).getBytes(StandardCharsets.UTF_8);

    private Long heartbeatInterval;

    public AbstractSocketNioClient(AbstractSocketClientConfig config) {
        this.config = config;
        this.msgSizeLimit = Optional.ofNullable(config.getMsgSizeLimit()).orElse(4 * 1024 * 1024);
        Integer heartbeatInterval = config.getHeartbeatInterval();
        this.heartbeatInterval = Math.max(Objects.isNull(heartbeatInterval) ? 30000L : heartbeatInterval * 1000L, 15000L);
        /*this.readCacheMap = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.SECONDS)
                .removalListener((ChannelId key, SocketMsgDto value, RemovalCause removalCause) -> {
                    if (value != null) {
                        CompositeByteBuf msg = value.getFull();
                        while (msg.refCnt() > 0) {
                            ReferenceCountUtil.release(msg);
                        }
                    }
                })
                .build();*/
        this.initRunnable = () -> {
            try {
                synchronized (lockObj) {
                    if (!getIsInit()) {
                        NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(Math.max(config.getBossLoopThreadCount(), 3));
                        bootstrap = new Bootstrap()
                                .group(nioEventLoopGroup)
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
                        String host = config.getHost();
                        Integer port = config.getPort();
                        if (socketMsgHandler == null) {
                            socketMsgHandler = new SocketMsgHandler(
                                    (channelHandlerContext, bytes) -> setDataConsumer().accept(bytes)
                                    , config.getMaxHandlerDataThreadCount()
                            );
                        }

                        channelPool = new SocketNioChannelPool(bootstrap, host, port, config.getPoolConfig());
                        log.info("TCP客户端已连接，地址：{}，端口: {}", host, port);
                        nioEventLoopGroup.execute(() -> {
                            Thread thread = Thread.currentThread();
                            while (!thread.isInterrupted()) {
                                try {
                                    write(Unpooled.wrappedBuffer(heartbeatBytes));
                                } catch (Exception e) {
                                    log.warn("TCP客户端心跳异常", e);
                                }
                                try {
                                    Thread.sleep(this.heartbeatInterval);
                                } catch (InterruptedException e) {
                                    log.warn("TCP客户端心跳线程已关闭");
                                    return;
                                }
                            }
                            log.warn("TCP客户端心跳线程已关闭");
                        });
                        if (connCallback != null) {
                            connCallback.accept(this);
                        }
                        lockObj.notify();
                    }
                }
                if (channelPool == null) {
                    throw new SocketException("TCP客户端创建连接失败");
                }
            } catch (Exception e) {
                log.error("TCP客户端创建连接异常", e);
                throw new SocketException("TCP客户端创建连接异常");
            }
        };
    }

    public abstract SocketClientWrapMsgDto encode(ByteBuf data);

    public abstract ByteBuf decode(ByteBuf data, byte secretByte);

    public abstract Consumer<byte[]> setDataConsumer();

    protected void channelRegisteredEvent(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        InetSocketAddress inetSocketAddress = (InetSocketAddress) channel.remoteAddress();
        Attribute<SocketParseMsgDto> socketMsgDtoAttribute = channel.attr(msgKey);
        socketMsgDtoAttribute.setIfAbsent(new SocketParseMsgDto(msgSizeLimit, 30));
        if (inetSocketAddress != null) {
            log.info("服务端channelId：{}，address：{}，port：{}，已注册", channel.id(), inetSocketAddress.getAddress(), inetSocketAddress.getPort());
        } else {
            log.info("服务端channelId：{}，address：null，port：null，已注册", channel.id());
        }
    }

    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        Attribute<SocketParseMsgDto> socketMsgDtoAttribute = channel.attr(msgKey);
        SocketParseMsgDto socketParseMsgDto = socketMsgDtoAttribute.getAndSet(null);
        try {
            if (socketParseMsgDto != null) {
                socketParseMsgDto.release();
            }
        } catch (Exception e) {
            log.error("消息释放异常", e);
        }
        log.info("服务端channelId：{}，已注销", channel.id());
    }

    @ChannelHandler.Sharable
    class ClientInHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            super.channelRegistered(ctx);
            channelRegisteredEvent(ctx);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            super.channelUnregistered(ctx);
            channelUnregisteredEvent(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msgObj) throws Exception {
            ByteBuf msg = (ByteBuf) msgObj;

            Channel socketChannel = ctx.channel();
            ChannelId channelId = socketChannel.id();
            Attribute<SocketParseMsgDto> socketMsgDtoAttribute = socketChannel.attr(msgKey);
            ByteBuf leftMsg = msg;
            SocketParseMsgDto socketParseMsgDto = socketMsgDtoAttribute.get();
            try {
                do {
                    synchronized (socketParseMsgDto) {
                        leftMsg = socketParseMsgDto.parsingMsg(leftMsg);
                    }
                    if (!socketParseMsgDto.getDone()) {
                        break;
                    }
                    Byte secretByte = socketParseMsgDto.getSecretByte();
                    //读取完成，写入队列
                    ByteBuf decodeBytes;
                    try {
                        decodeBytes = decode(socketParseMsgDto.getMsg(), secretByte);
                    } catch (Exception e) {
                        log.debug("解码错误", e);
                        //丢弃并关闭连接
                        throw new SocketException("报文解码错误");
                    }

                    int length = decodeBytes.writerIndex();
                    boolean sendOrReceiveAck = SocketMessageUtil.isAckData(decodeBytes);
                    if (length > 3) {
                        log.debug("数据已接收，channelId：{}", channelId);
                        socketMsgHandler.putData(ctx, SocketMessageUtil.unPackageData(decodeBytes));
                        if (sendOrReceiveAck) {
                            byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                            try {
                                socketChannel.writeAndFlush(ackBytes);
                                //log.debug("发送ack字节：{}", ackBytes);
                            } catch (Exception e) {
                                log.error("ack编码错误", e);
                            }
                        }
                    } else {
                        if (!sendOrReceiveAck) {
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
                                //log.debug("接收ack字节：{}，已完成", ackBytes);
                            } else {
                                log.error("接收ack字节：{}，未命中或请求超时", ackBytes);
                            }
                        } else {
                            //关闭连接
                            throw new SocketException("报文数据异常");
                        }
                    }
                    socketParseMsgDto.clear();
                } while (leftMsg != null);
            } catch (SocketException e) {
                log.error("数据解析异常：{}", e.getMessage());
                //丢弃数据并关闭连接
                socketChannel.close();
                //异常丢弃
                socketParseMsgDto.release();
                while (msg.refCnt() > 0) {
                    ReferenceCountUtil.release(msg);
                }
            } catch (Exception e) {
                log.error("数据解析异常", e);
                //丢弃数据并关闭连接
                socketChannel.close();
                //异常丢弃
                socketParseMsgDto.release();
                while (msg.refCnt() > 0) {
                    ReferenceCountUtil.release(msg);
                }
            }
        }
    }

    @ChannelHandler.Sharable
    class ClientOutHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            //TODO 优化
            ByteBuf data;
            if (msg instanceof ByteBuf) {
                data = (ByteBuf) msg;
            } else if (msg instanceof byte[]) {
                data = Unpooled.wrappedBuffer((byte[]) msg);
            } else {
                //传输其他类型数据时暂不支持ACK，需使用ByteBuf或byte[]
                data = SocketMessageUtil.packageData(Unpooled.wrappedBuffer(JsonUtil.toJsonString(msg).getBytes(StandardCharsets.UTF_8)), false);
            }
            ctx.writeAndFlush(encode(data).getWrapMsg(), promise);
            log.debug("数据已发送，channelId：{}", ctx.channel().id());
        }
    }

    public void write(ByteBuf data) {
        if (!getIsInit()) {
            initNioClientSync();
        }
        Channel channel = channelPool.borrowChannel();
        try {
            socketMsgHandler.write(channel, data);
        } finally {
            channelPool.returnChannel(channel);
        }
    }

    public boolean writeAck(ByteBuf data, int seconds) {
        if (!getIsInit()) {
            initNioClientSync();
        }
        Channel channel = channelPool.borrowChannel();
        try {
            seconds = Math.min(seconds, 10);
            ByteBuf packageData = SocketMessageUtil.packageData(data, true);
            byte[] needAckBytes = {(byte) (packageData.getByte(0) & (byte) 0x7F), packageData.getByte(1), packageData.getByte(2)};
            int ackKey = SocketMessageUtil.threeByteArrayToInt(needAckBytes);
            String key = Integer.toString(ackKey).concat(channel.id().asShortText());
            try {
                SocketAckThreadDto ackThreadDto = new SocketAckThreadDto();
                ackDataMap.put(key, ackThreadDto);
                synchronized (ackThreadDto) {
                    channel.writeAndFlush(packageData);
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
        } finally {
            channelPool.returnChannel(channel);
        }
    }

    public boolean writeAck(ByteBuf data) {
        return writeAck(data, 10);
    }

    public synchronized void initNioClientAsync() {
        if (!getIsInit()) {
            singleThreadExecutor.execute(initRunnable);
        }
    }

    public synchronized void initNioClientSync() {
        initNioClientSync(10);
    }

    public synchronized void initNioClientSync(int seconds) {
        if (!getIsInit()) {
            synchronized (lockObj) {
                singleThreadExecutor.execute(initRunnable);
                try {
                    lockObj.wait(TimeUnit.SECONDS.toMillis(seconds));
                } catch (InterruptedException e) {
                    log.error("TCP客户端同步创建连接异常", e);
                    throw new SocketException("TCP客户端同步创建连接异常");
                }
            }
            if (!getIsInit()) {
                throw new SocketException("TCP客户端同步创建连接失败");
            }
        }
    }

    public void setHeartbeatInterval(Integer heartbeatInterval) {
        Integer oldHeartbeatInterval = getHeartbeatInterval();
        this.heartbeatInterval = Math.max(Objects.isNull(heartbeatInterval) ? 30000L : heartbeatInterval * 1000L, 15000L);
        log.info("TCP客户端心跳间隔时间已更新，旧：{}秒，新：{}秒", oldHeartbeatInterval, heartbeatInterval);
    }

    public Integer getHeartbeatInterval() {
        return ((Long) (heartbeatInterval / 1000)).intValue();
    }

    public void shutdownNow() {
        if (getIsInit()) {
            synchronized (lockObj) {
                if (getIsInit()) {
                    if (socketMsgHandler.shutdownNow()) {
                        channelPool.close();
                        if (bootstrap != null) {
                            //关闭线程组
                            bootstrap.config().group().shutdownGracefully();
                        }
                        bootstrap = null;
                        socketMsgHandler = null;
                        log.info("TCP客户端已关闭");
                    }
                }
            }
        }
    }
}
