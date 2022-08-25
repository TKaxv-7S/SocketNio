package com.tk.socket.client;

import com.tk.socket.SocketException;
import com.tk.socket.SocketMessageUtil;
import com.tk.socket.SocketMsgHandler;
import com.tk.socket.SocketNioChannelPool;
import com.tk.utils.JsonUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    protected AbstractSocketClientConfig getConfig() {
        return config;
    }

    protected Consumer<AbstractSocketNioClient> connCallback;

    public AbstractSocketNioClient(AbstractSocketClientConfig config) {
        this.config = config;
        this.initRunnable = () -> {
            try {
                synchronized (lockObj) {
                    if (!getIsInit()) {
                        bootstrap = new Bootstrap()
                                .group(new NioEventLoopGroup(Math.max(config.getBossLoopThreadCount(), 2)))
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
                                    30
                                    , config.getMsgSizeLimit()
                                    , (channelId, data, secretByte) -> decode(data, secretByte)
                                    , (channelId, data) -> encode(data)
                                    , (channelHandlerContext, bytes) -> setDataConsumer().accept(bytes)
                                    , config.getMaxHandlerDataThreadCount()
                            );
                        }

                        channelPool = new SocketNioChannelPool(bootstrap, host, port, config.getPoolConfig());
                        log.info("SocketNioClient已连接，地址：{}，端口: {}", host, port);
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

    public abstract ByteBuf encode(Channel channel, byte[] data);

    public abstract byte[] decode(byte[] data, byte secretByte);

    public abstract Consumer<byte[]> setDataConsumer();

    protected void channelRegisteredEvent(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        InetSocketAddress inetSocketAddress = (InetSocketAddress) channel.remoteAddress();
        if (inetSocketAddress != null) {
            log.info("服务端channelId：{}，address：{}，port：{}，已注册", channel.id(), inetSocketAddress.getAddress(), inetSocketAddress.getPort());
        } else {
            log.info("服务端channelId：{}，address：null，port：null，已注册", channel.id());
        }
    }

    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        log.info("服务端channelId：{}，已注销", ctx.channel().id());
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
            socketMsgHandler.readMsg(ctx, msg);
        }
    }

    @ChannelHandler.Sharable
    class ClientOutHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            handlerWrite(ctx, msg, promise);
        }
    }

    private void handlerWrite(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof byte[]) {
            encode()
            ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(encode(ctx.channel(), (byte[]) msg))), promise);
        } else {
            //传输其他类型数据时暂不支持ACK，需使用byte[]
            ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(encode(ctx.channel(), SocketMessageUtil.packageData(JsonUtil.toJsonString(msg).getBytes(StandardCharsets.UTF_8), false)))), promise);
        }
        log.debug("数据已发送，channelId：{}", ctx.channel().id());
    }

    public void write(byte[] data) {
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

    public boolean writeAck(byte[] data, int seconds) {
        if (!getIsInit()) {
            initNioClientSync();
        }
        Channel channel = channelPool.borrowChannel();
        try {
            return socketMsgHandler.writeAck(channel, data, Math.min(seconds, 10));
        } finally {
            channelPool.returnChannel(channel);
        }
    }

    public boolean writeAck(byte[] data) {
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
                        log.info("SocketNioClient已关闭");
                    }
                }
            }
        }
    }
}
