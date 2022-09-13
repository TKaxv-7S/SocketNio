package com.tk.socket.server;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.tk.socket.*;
import com.tk.utils.JsonUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
public abstract class AbstractSocketNioServer {

    private ServerBootstrap bootstrap;

    private Channel channel = null;

    public Boolean getIsInit() {
        return channel != null;
    }

    private SocketMsgHandler socketMsgHandler;

    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

    private final Runnable initRunnable;

    public final SocketServerConfig config;

    private final Integer msgSizeLimit;

    private final Cache<ChannelId, Channel> unknownChannelCache;

    private final AttributeKey<SocketParseMsgDto> msgKey = AttributeKey.valueOf("msg");

    private final Map<String, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    public AbstractSocketNioServer(SocketServerConfig config) {
        this.config = config;
        this.msgSizeLimit = Optional.ofNullable(config.getMsgSizeLimit()).orElse(4 * 1024 * 1024);
        this.unknownChannelCache = Caffeine.newBuilder()
                .expireAfterWrite(config.getUnknownWaitMsgTimeoutSeconds(), TimeUnit.SECONDS)
                .removalListener((ChannelId key, Channel value, RemovalCause removalCause) -> {
                    if (value != null) {
                        if (!isClient(value)) {
                            value.close();
                            log.info("客户端channelId：{}，已关闭", value.id());
                        }
                    }
                })
                .build();
        /*this.readCacheMap = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.SECONDS)
                .removalListener((ChannelId key, SocketMsgDto value, RemovalCause removalCause) -> {
                    if (value != null) {
                        CompositeByteBuf msg = value.getFull();
                        while (msg.refCnt() > 0) {
                            int numComponents = msg.numComponents();
                            for (int i = 0; i < numComponents; i++) {
                                ByteBuf component = msg.component(i);
                                log.info("component index:{}，refCnt:{}", i, component.refCnt());
                            }
                            ReferenceCountUtil.release(msg);
                        }
                    }
                })
                .build();*/
        this.initRunnable = () -> {
            try {
                synchronized (this) {
                    if (!getIsInit()) {
                        bootstrap = new ServerBootstrap()
                                .group(new NioEventLoopGroup((Math.max(config.getBossLoopThreadCount(), 2))), new NioEventLoopGroup(config.getEventLoopThreadCount()))
                                .channel(NioServerSocketChannel.class)
                                .childHandler(new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                                        socketChannel.pipeline()
                                                .addLast(new ServerInHandler())
                                                .addLast(new ServerOutHandler());
                                    }
                                })
                                .option(ChannelOption.SO_BACKLOG, 128)
                                //首选直接内存
                                .option(ChannelOption.ALLOCATOR, PreferredDirectByteBufAllocator.DEFAULT)
                                //设置队列大小
//                .option(ChannelOption.SO_BACKLOG, 1024)
                                // 两小时内没有数据的通信时,TCP会自动发送一个活动探测数据报文
                                .childOption(ChannelOption.TCP_NODELAY, true)
                                .childOption(ChannelOption.SO_KEEPALIVE, true)
                                .childOption(ChannelOption.SO_RCVBUF, 128 * 1024)
                                .childOption(ChannelOption.SO_SNDBUF, 1024 * 1024)
                                //服务端低水位线设置为3M，高水位线设置为6M
                                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(3 * 1024 * 1024, 6 * 1024 * 1024));
                        Integer port = config.getPort();
                        ChannelFuture future = bootstrap.bind(port).sync();
                        channel = future.channel();
                        if (socketMsgHandler == null) {
                            socketMsgHandler = new SocketMsgHandler(
                                    setDataConsumer()
                                    , config.getMaxHandlerDataThreadCount()
                            );
                        }
                        log.info("SocketNioServer已启动，开始监听端口: {}", port);
                        this.notify();
                    }
                }
                if (channel == null) {
                    throw new SocketException("TCP服务端初始化连接失败");
                }
            } catch (Exception e) {
                log.error("TCP服务端初始化连接异常", e);
                throw new SocketException("TCP服务端初始化连接异常");
            }
        };
    }

    public abstract SocketServerWrapMsgDto encode(Channel channel, byte[] data);

    public abstract byte[] decode(Channel channel, byte[] data, byte secretByte);

    public abstract BiConsumer<ChannelHandlerContext, byte[]> setDataConsumer();

    public abstract Boolean isClient(Channel channel);

    protected void channelRegisteredEvent(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        if (!isClient(channel)) {
            unknownChannelCache.put(channel.id(), channel);
        }
        InetSocketAddress inetSocketAddress = (InetSocketAddress) channel.remoteAddress();
        Attribute<SocketParseMsgDto> socketMsgDtoAttribute = channel.attr(msgKey);
        socketMsgDtoAttribute.setIfAbsent(new SocketParseMsgDto(msgSizeLimit, 10));
        log.info("客户端channelId：{}，address：{}，port：{}，已注册", channel.id(), inetSocketAddress.getAddress(), inetSocketAddress.getPort());
    }

    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        ChannelId channelId = channel.id();
        unknownChannelCache.invalidate(channelId);
        Attribute<SocketParseMsgDto> socketMsgDtoAttribute = channel.attr(msgKey);
        SocketParseMsgDto socketParseMsgDto = socketMsgDtoAttribute.getAndSet(null);
        try {
            if (socketParseMsgDto != null) {
                socketParseMsgDto.release();
            }
        } catch (Exception e) {
            log.error("消息释放异常", e);
        }
        log.info("客户端channelId：{}，已注销", channelId);
    }

    @ChannelHandler.Sharable
    class ServerInHandler extends ChannelInboundHandlerAdapter {

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
                    byte[] decodeBytes;
                    try {
                        decodeBytes = decode(ctx.channel(), socketParseMsgDto.getMsg(), secretByte);
                    } catch (Exception e) {
                        log.debug("解码错误", e);
                        //丢弃并关闭连接
                        throw new SocketException("报文解码错误");
                    }

                    int length = decodeBytes.length;
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
    class ServerOutHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            //TODO 优化
            byte[] data;
            if (msg instanceof byte[]) {
                data = (byte[]) msg;
            } else {
                //传输其他类型数据时暂不支持ACK，需使用byte[]
                data = SocketMessageUtil.packageData(JsonUtil.toJsonString(msg).getBytes(StandardCharsets.UTF_8), false);
            }
            ctx.writeAndFlush(encode(ctx.channel(), data).getWrapMsg(), promise);
            log.debug("数据已发送，channelId：{}", ctx.channel().id());
        }
    }

    public void write(Channel socketChannel, byte[] data) {
        socketMsgHandler.write(socketChannel, data);
    }

    /**
     * 服务端等待确认时间最久10秒
     *
     * @param socketChannel
     * @param data
     * @param seconds
     * @return
     */
    public boolean writeAck(Channel socketChannel, byte[] data, int seconds) {
        seconds = Math.min(seconds, 10);
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

    public boolean writeAck(Channel socketChannel, byte[] data) {
        return writeAck(socketChannel, data, 10);
    }

    public void initNioServerAsync() {
        if (!getIsInit()) {
            singleThreadExecutor.execute(initRunnable);
        }
    }

    public void initNioServerSync() {
        initNioServerSync(10);
    }

    public void initNioServerSync(int seconds) {
        if (!getIsInit()) {
            synchronized (this) {
                singleThreadExecutor.execute(initRunnable);
                try {
                    this.wait(TimeUnit.SECONDS.toMillis(seconds));
                } catch (InterruptedException e) {
                    log.error("TCP服务端同步初始化连接异常", e);
                    throw new SocketException("TCP服务端同步初始化连接异常");
                }
            }
            if (!getIsInit()) {
                throw new SocketException("TCP服务端同步初始化连接失败");
            }
        }
    }

    public void shutdownNow() {
        if (getIsInit()) {
            synchronized (this) {
                if (getIsInit()) {
                    if (socketMsgHandler.shutdownNow()) {
                        channel.close();
                        if (bootstrap != null) {
                            ServerBootstrapConfig config = bootstrap.config();
                            //关闭主线程组
                            config.group().shutdownGracefully();
                            //关闭工作线程组
                            config.childGroup().shutdownGracefully();
                        }
                        bootstrap = null;
                        channel = null;
                        socketMsgHandler = null;
                    }
                }
            }
        }
        log.info("SocketNioServer已关闭");
    }

}
