package com.tk.socket.server;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.tk.socket.*;
import com.tk.utils.JsonUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
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

    private final AttributeKey<SocketMsgDto> msgKey = AttributeKey.valueOf("msg");

    private final AttributeKey<Map<String, SocketAckThreadDto>> ackMapKey = AttributeKey.valueOf("ackMap");

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
                                    10
                                    , setDataConsumer()
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

    public abstract SocketEncodeDto encode(Channel channel, byte[] data);

    public abstract byte[] decode(Channel channel, byte[] data, byte secretByte);

    public abstract BiConsumer<ChannelHandlerContext, byte[]> setDataConsumer();

    public abstract Boolean isClient(Channel channel);

    protected void channelRegisteredEvent(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        InetSocketAddress inetSocketAddress = (InetSocketAddress) channel.remoteAddress();
        log.info("客户端channelId：{}，address：{}，port：{}，已注册", channel.id(), inetSocketAddress.getAddress(), inetSocketAddress.getPort());
    }

    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        log.info("客户端channelId：{}，已注销", ctx.channel().id());
    }

    private void setUnknownChannelCache(Channel channel) {
        if (!isClient(channel)) {
            unknownChannelCache.put(channel.id(), channel);
        }
    }

    private void delUnknownChannelCache(ChannelId channelId) {
        unknownChannelCache.invalidate(channelId);
    }

    @ChannelHandler.Sharable
    class ServerInHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            super.channelRegistered(ctx);
            setUnknownChannelCache(ctx.channel());
            channelRegisteredEvent(ctx);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            super.channelUnregistered(ctx);
            delUnknownChannelCache(ctx.channel().id());
            channelUnregisteredEvent(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msgObj) throws Exception {
            ByteBuf msg = (ByteBuf) msgObj;

            Channel socketChannel = ctx.channel();
            ChannelId channelId = socketChannel.id();
            Attribute<SocketMsgDto> socketMsgDtoAttribute = socketChannel.attr(msgKey);
            SocketMsgDto socketMsgDto = socketMsgDtoAttribute.setIfAbsent(new SocketMsgDto(msgSizeLimit));
            if (socketMsgDto == null) {
                synchronized (socketMsgDtoAttribute) {
                    socketMsgDto = new SocketMsgDto(msgSizeLimit);
                    socketMsgDtoAttribute.set(socketMsgDto);
                }
            }
            SocketMsgDto prevSocketMsgDto = null;
            Boolean prevDone;
            try {
                do {
                    if (socketMsgDto.parsingMsg(msg)) {
                        //正常丢弃
                        while (msg.refCnt() > 0) {
                            ReferenceCountUtil.release(msg);
                        }
                    }
                    Byte secretByte = socketMsgDto.getSecretByte();
                    //读取完成，写入队列
                    byte[] decodeBytes;
                    try {
//            byte[] bytes = new byte[size - 8];
//            full.readBytes(bytes);
                    /*if (log.isDebugEnabled()) {
                        log.debug("byteBuf完成 已读：{}，已写：{}，容量，{}", byteBuf.readerIndex(), byteBuf.writerIndex(), byteBuf.capacity());
                        log.debug("msg完成 已读：{}，已写：{}，容量，{}", msg.readerIndex(), msg.writerIndex(), msg.capacity());
                    }*/
                        decodeBytes = decode(ctx.channel(), socketMsgDto.getBody(), secretByte);
                    } catch (Exception e) {
                        log.debug("解码错误", e);
                        //丢弃并关闭连接
                        throw new SocketException("报文解码错误");
                    }
                    prevSocketMsgDto = socketMsgDto;

                    int length = decodeBytes.length;
                    boolean isAckData = SocketMessageUtil.isAckData(decodeBytes);
                    if (length > 3) {
                        log.debug("数据已接收，channelId：{}", channelId);
                        socketMsgHandler.putData(ctx, SocketMessageUtil.unPackageData(decodeBytes));
                    } else {
                        if (!isAckData) {
                            //接收ackBytes
                            byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                            int ackKey = SocketMessageUtil.threeByteArrayToInt(ackBytes);
                            Attribute<Map<String, SocketAckThreadDto>> attr = socketChannel.attr(ackMapKey);
                            Map<String, SocketAckThreadDto> ackThreadDtoMap;
                            synchronized (attr) {
                                ackThreadDtoMap = attr.get();
                                if (ackThreadDtoMap == null) {
                                    ackThreadDtoMap = new ConcurrentHashMap<>();
                                    attr.set(ackThreadDtoMap);
                                }
                            }
                            SocketAckThreadDto socketAckThreadDto = ackThreadDtoMap.get(Integer.toString(ackKey));
                            if (socketAckThreadDto != null) {
                                synchronized (socketAckThreadDto) {
                                    socketAckThreadDto.setIsAck(true);
                                    socketAckThreadDto.notify();
                                }
//                            LockSupport.unpark(socketAckThreadDto.getThread());
                                log.debug("接收ack字节：{}，已完成", ackBytes);
                                break;
                            } else {
                                log.error("接收ack字节：{}，未命中或请求超时", ackBytes);
                            }
                        } else {
                            //关闭连接
                            throw new SocketException("报文数据异常");
                        }
                        //丢弃
                        break;
                    }
                    if (isAckData) {
                        byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                        try {
                            socketChannel.writeAndFlush(ackBytes);
                            log.debug("发送ack字节：{}", ackBytes);
                            break;
                        } catch (Exception e) {
                            log.error("ack编码错误", e);
                        }
                    }
                    socketMsgDto = socketMsgDto.getNext();
                } while (socketMsgDto != null);
            } catch (Exception e) {
                log.error("数据解析异常", e);
                //丢弃数据并关闭连接
                //log.debug("msg.refCnt：{}", msg.refCnt());
                socketChannel.close();
                //异常丢弃
                if (prevSocketMsgDto != null) {
                    CompositeByteBuf full = prevSocketMsgDto.getFull();
                    while (full.refCnt() > 0) {
                        ReferenceCountUtil.release(full);
                    }
                }
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
            if (msg instanceof byte[]) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(encode(ctx.channel(), (byte[]) msg))), promise);
            } else {
                //传输其他类型数据时暂不支持ACK，需使用byte[]
                ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(encode(ctx.channel(), SocketMessageUtil.packageData(JsonUtil.toJsonString(msg).getBytes(StandardCharsets.UTF_8), false)))), promise);
            }
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
        return socketMsgHandler.writeAck(socketChannel, data, Math.min(seconds, 10));
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
