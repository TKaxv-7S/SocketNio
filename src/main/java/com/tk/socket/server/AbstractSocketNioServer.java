package com.tk.socket.server;

import cn.hutool.core.thread.ThreadUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.tk.socket.SocketEncodeDto;
import com.tk.socket.SocketException;
import com.tk.socket.SocketMsgHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
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

    private final Runnable initRunnable;

    public final SocketServerConfig config;

    private final Cache<ChannelId, Channel> unknownChannelCache;

    public AbstractSocketNioServer(SocketServerConfig config) {
        this.config = config;
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
                                .group(new NioEventLoopGroup(config.getBossLoopThreadCount()), new NioEventLoopGroup(config.getEventLoopThreadCount()))
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
                                    , config.getMsgSizeLimit()
                                    , this::decode
                                    , this::encode
                                    , setDataConsumer()
                                    , config.getMaxHandlerDataThreadCount()
                                    , config.getSingleThreadDataConsumerCount()
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
            socketMsgHandler.readMsg(ctx, msg);
        }
    }

    @ChannelHandler.Sharable
    class ServerOutHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            socketMsgHandler.handlerWrite(ctx, msg, promise);
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
            ThreadUtil.execute(initRunnable);
        }
    }

    public void initNioServerSync() {
        initNioServerSync(10);
    }

    public void initNioServerSync(int seconds) {
        if (!getIsInit()) {
            synchronized (this) {
                ThreadUtil.execute(initRunnable);
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

    public void shutdown() {
        if (getIsInit()) {
            synchronized (this) {
                if (getIsInit()) {
                    if (socketMsgHandler.shutdown()) {
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
