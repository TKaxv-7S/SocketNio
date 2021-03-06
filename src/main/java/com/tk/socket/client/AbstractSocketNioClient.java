package com.tk.socket.client;

import cn.hutool.core.thread.ThreadUtil;
import com.tk.socket.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
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

    private final Runnable initRunnable;

    public final SocketClientConfig config;

    protected SocketClientConfig getConfig() {
        return config;
    }

    protected Consumer<AbstractSocketNioClient> connCallback;

    public AbstractSocketNioClient(SocketClientConfig config) {
        this.config = config;
        this.initRunnable = () -> {
            try {
                synchronized (lockObj) {
                    if (!getIsInit()) {
                        bootstrap = new Bootstrap()
                                .group(new NioEventLoopGroup(config.getBossLoopThreadCount()))
                                .channel(NioSocketChannel.class)
                                .handler(new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                                        socketChannel.pipeline()
                                                .addLast(new ClientInHandler())
                                                .addLast(new ClientOutHandler());
                                    }
                                })
                                //??????????????????
                                .option(ChannelOption.ALLOCATOR, PreferredDirectByteBufAllocator.DEFAULT)
                                //??????????????????
                                //.option(ChannelOption.SO_BACKLOG, 1024)
                                .option(ChannelOption.TCP_NODELAY, true)
                                .option(ChannelOption.SO_KEEPALIVE, true)
                                .option(ChannelOption.SO_RCVBUF, 4096 * 1024)
                                .option(ChannelOption.SO_SNDBUF, 1024 * 1024)
                                //??????????????????????????????1M????????????????????????2M
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
                        log.info("SocketNioClient?????????????????????{}?????????: {}", host, port);
                        if (connCallback != null) {
                            connCallback.accept(this);
                        }
                        lockObj.notify();
                    }
                }
                if (channelPool == null) {
                    throw new SocketException("TCP???????????????????????????");
                }
            } catch (Exception e) {
                log.error("TCP???????????????????????????", e);
                throw new SocketException("TCP???????????????????????????");
            }
        };
    }

    public abstract SocketEncodeDto encode(byte[] data);

    public abstract byte[] decode(byte[] data, byte secretByte);

    public abstract Consumer<byte[]> setDataConsumer();

    protected void channelRegisteredEvent(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        InetSocketAddress inetSocketAddress = (InetSocketAddress) channel.remoteAddress();
        if (inetSocketAddress != null) {
            log.info("?????????channelId???{}???address???{}???port???{}????????????", channel.id(), inetSocketAddress.getAddress(), inetSocketAddress.getPort());
        } else {
            log.info("?????????channelId???{}???address???null???port???null????????????", channel.id());
        }
    }

    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        log.info("?????????channelId???{}????????????", ctx.channel().id());
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
            socketMsgHandler.handlerWrite(ctx, msg, promise);
        }
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
            ThreadUtil.execute(initRunnable);
        }
    }

    public synchronized void initNioClientSync() {
        initNioClientSync(10);
    }

    public synchronized void initNioClientSync(int seconds) {
        if (!getIsInit()) {
            synchronized (lockObj) {
                ThreadUtil.execute(initRunnable);
                try {
                    lockObj.wait(TimeUnit.SECONDS.toMillis(seconds));
                } catch (InterruptedException e) {
                    log.error("TCP?????????????????????????????????", e);
                    throw new SocketException("TCP?????????????????????????????????");
                }
            }
            if (!getIsInit()) {
                throw new SocketException("TCP?????????????????????????????????");
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
                            //???????????????
                            bootstrap.config().group().shutdownGracefully();
                        }
                        bootstrap = null;
                        socketMsgHandler = null;
                        log.info("SocketNioClient?????????");
                    }
                }
            }
        }
    }
}
